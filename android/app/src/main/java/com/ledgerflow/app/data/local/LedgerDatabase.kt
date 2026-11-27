package com.ledgerflow.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.FxConverter
import java.util.UUID
import kotlin.math.abs

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        TransactionFtsEntity::class,
        BudgetEntity::class,
        PlannedPaymentEntity::class,
        HoldingEntity::class,
        MagicRuleEntity::class,
        DebtEntity::class,
        SavingsGoalEntity::class,
        ShoppingListEntity::class,
        ShoppingItemEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun plannedPaymentDao(): PlannedPaymentDao
    abstract fun holdingDao(): HoldingDao
    abstract fun magicRuleDao(): MagicRuleDao
    abstract fun debtDao(): DebtDao
    abstract fun savingsGoalDao(): SavingsGoalDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun shoppingItemDao(): ShoppingItemDao

    /**
     * Atomically saves a transaction and adjusts the affected account balance(s)
     * inside a single SQLite transaction. Idempotent when updating an existing id.
     */
    suspend fun recordTransactionAtomic(tx: TransactionEntity) {
        withTransaction {
            val now = tx.updatedAtEpochMs
            val previous = transactionDao().getById(tx.id)
            if (previous != null && previous.deletedAtEpochMs == null) {
                applyTransactionBalanceDelta(previous, signMultiplier = -1, nowMs = now)
            }
            transactionDao().upsert(tx)
            if (tx.deletedAtEpochMs == null) {
                applyTransactionBalanceDelta(tx, signMultiplier = 1, nowMs = now)
            }
        }
    }

    suspend fun softDeleteTransactionAtomic(txId: String, nowMs: Long = System.currentTimeMillis()) {
        withTransaction {
            val existing = transactionDao().getById(txId) ?: return@withTransaction
            if (existing.deletedAtEpochMs != null) return@withTransaction
            transactionDao().upsert(existing.copy(deletedAtEpochMs = nowMs, updatedAtEpochMs = nowMs, pendingSync = true))
            applyTransactionBalanceDelta(existing, signMultiplier = -1, nowMs = nowMs)
        }
    }

    private suspend fun applyTransactionBalanceDelta(
        tx: TransactionEntity,
        signMultiplier: Int,
        nowMs: Long,
    ) {
        val sourceAccount = accountDao().getById(tx.accountId)
        val mag = abs(tx.amountMinor)
        when (tx.txType) {
            "EXPENSE" -> {
                val signed = -mag * signMultiplier
                val delta = sourceAccount?.let { FxConverter.convertMinor(signed, tx.currency, it.currency) } ?: signed
                accountDao().adjustBalance(tx.accountId, delta, nowMs)
            }
            "INCOME" -> {
                val signed = mag * signMultiplier
                val delta = sourceAccount?.let { FxConverter.convertMinor(signed, tx.currency, it.currency) } ?: signed
                accountDao().adjustBalance(tx.accountId, delta, nowMs)
            }
            "TRANSFER" -> {
                val outSigned = -mag * signMultiplier
                val outDelta = sourceAccount?.let { FxConverter.convertMinor(outSigned, tx.currency, it.currency) } ?: outSigned
                accountDao().adjustBalance(tx.accountId, outDelta, nowMs)
                tx.transferAccountId?.let { targetId ->
                    val targetAccount = accountDao().getById(targetId)
                    val inSigned = mag * signMultiplier
                    val inDelta = targetAccount?.let { FxConverter.convertMinor(inSigned, tx.currency, it.currency) } ?: inSigned
                    accountDao().adjustBalance(targetId, inDelta, nowMs)
                }
            }
        }
    }

    suspend fun executePlannedPaymentAtomic(
        plannedId: String,
        fallbackAccountId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        return withTransaction {
            val item = plannedPaymentDao().getById(plannedId) ?: return@withTransaction false
            if (item.deletedAtEpochMs != null || item.amountMinor <= 0L) return@withTransaction false
            val targetAccount = resolveActiveAccountForPlannedLocked(item.accountId, fallbackAccountId)
                ?: return@withTransaction false
            val normType = if (item.txType.equals("INCOME", ignoreCase = true)) "INCOME" else "EXPENSE"
            val signedAmount = if (normType == "EXPENSE") -abs(item.amountMinor) else abs(item.amountMinor)
            val tx = TransactionEntity(
                id = UUID.randomUUID().toString(),
                accountId = targetAccount.id,
                categoryId = item.categoryId,
                txType = normType,
                amountMinor = signedAmount,
                currency = item.currency,
                merchant = item.title,
                note = "Planned ${item.cadence.lowercase()} ${if (normType == "INCOME") "income" else "bill"}",
                sourceTag = "PLANNED",
                autoRuleApplied = true,
                occurredAtEpochMs = nowMs,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs,
                pendingSync = true,
            )
            recordTransactionAtomic(tx)
            plannedPaymentDao().upsert(
                item.copy(
                    nextDueAtEpochMs = advanceDueDateEpochMs(item.nextDueAtEpochMs, item.cadence),
                    updatedAtEpochMs = nowMs,
                    pendingSync = true,
                )
            )
            true
        }
    }

    suspend fun skipPlannedPaymentAtomic(
        plannedId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        return withTransaction {
            val item = plannedPaymentDao().getById(plannedId) ?: return@withTransaction false
            if (item.deletedAtEpochMs != null) return@withTransaction false
            plannedPaymentDao().upsert(
                item.copy(
                    nextDueAtEpochMs = advanceDueDateEpochMs(item.nextDueAtEpochMs, item.cadence),
                    updatedAtEpochMs = nowMs,
                    pendingSync = true,
                )
            )
            true
        }
    }

    /**
     * Executes any overdue or due-now Auto-Pay planned items once per due occurrence inside an atomic
     * SQLite transaction. Uses deterministic per-occurrence IDs so concurrent calls cannot double-post.
     */
    suspend fun processDueAutoPayPlannedPayments(nowMs: Long = System.currentTimeMillis()): Int {
        return withTransaction {
            val activePlanned = plannedPaymentDao().getAllActive()
            var postedCount = 0
            for (item in activePlanned) {
                if (item.deletedAtEpochMs != null || !item.autoPay || item.amountMinor <= 0L) continue
                if (item.nextDueAtEpochMs > nowMs) continue
                val targetAccount = resolveActiveAccountForPlannedLocked(item.accountId, null) ?: continue
                val normType = if (item.txType.equals("INCOME", ignoreCase = true)) "INCOME" else "EXPENSE"
                val signedAmount = if (normType == "EXPENSE") -abs(item.amountMinor) else abs(item.amountMinor)

                var dueCursor = item.nextDueAtEpochMs
                var steps = 0
                while (dueCursor <= nowMs && steps < MAX_AUTOPAY_CATCHUP_STEPS) {
                    val txId = UUID.nameUUIDFromBytes("autopay:${item.id}:$dueCursor".toByteArray()).toString()
                    val existingTx = transactionDao().getById(txId)
                    if (existingTx == null) {
                        val tx = TransactionEntity(
                            id = txId,
                            accountId = targetAccount.id,
                            categoryId = item.categoryId,
                            txType = normType,
                            amountMinor = signedAmount,
                            currency = item.currency,
                            merchant = item.title,
                            note = "Auto-paid ${item.cadence.lowercase()} ${if (normType == "INCOME") "income" else "bill"}",
                            sourceTag = "PLANNED",
                            autoRuleApplied = true,
                            occurredAtEpochMs = dueCursor,
                            createdAtEpochMs = nowMs,
                            updatedAtEpochMs = nowMs,
                            pendingSync = true,
                        )
                        recordTransactionAtomic(tx)
                        postedCount++
                    }
                    val nextDue = advanceDueDateEpochMs(dueCursor, item.cadence)
                    if (nextDue <= dueCursor) break
                    dueCursor = nextDue
                    steps++
                }
                plannedPaymentDao().upsert(
                    item.copy(
                        nextDueAtEpochMs = dueCursor,
                        updatedAtEpochMs = nowMs,
                        pendingSync = true,
                    )
                )
            }
            postedCount
        }
    }

    private suspend fun resolveActiveAccountForPlannedLocked(
        preferredAccountId: String?,
        fallbackAccountId: String?,
    ): AccountEntity? {
        preferredAccountId?.let { accountDao().getById(it) }?.takeIf { it.deletedAtEpochMs == null }?.let { return it }
        fallbackAccountId?.let { accountDao().getById(it) }?.takeIf { it.deletedAtEpochMs == null }?.let { return it }
        val active = accountDao().getAllActive()
        return active.firstOrNull { !AccountTypeCatalog.isLiability(it.accountType) } ?: active.firstOrNull()
    }

    /**
     * Atomically saves a new Debt record and optionally posts the initial LENT / BORROWED ledger
     * movement against an active account so account balances match real cash movement.
     */
    suspend fun createDebtAtomic(
        debt: DebtEntity,
        createInitialTransaction: Boolean,
    ): Boolean {
        val cleanMag = abs(debt.totalAmountMinor)
        if (debt.personName.isBlank() || cleanMag <= 0L) return false
        return withTransaction {
            val now = debt.updatedAtEpochMs
            val normDirection = if (debt.direction.equals("BORROWED", ignoreCase = true)) "BORROWED" else "LENT"
            val resolvedAccount = debt.accountId
                ?.let { accountDao().getById(it) }
                ?.takeIf { it.deletedAtEpochMs == null }

            val normalizedDebt = debt.copy(
                personName = debt.personName.trim(),
                direction = normDirection,
                totalAmountMinor = cleanMag,
                remainingAmountMinor = abs(debt.remainingAmountMinor).coerceIn(0L, cleanMag),
                accountId = resolvedAccount?.id,
                status = if (debt.remainingAmountMinor <= 0L) "CLOSED" else "ACTIVE",
            )
            debtDao().upsert(normalizedDebt)

            if (createInitialTransaction && resolvedAccount != null) {
                val txType = if (normDirection == "LENT") "EXPENSE" else "INCOME"
                val signedAmount = if (txType == "EXPENSE") -cleanMag else cleanMag
                val merchant = if (normDirection == "LENT") {
                    "Lent to ${normalizedDebt.personName}"
                } else {
                    "Borrowed from ${normalizedDebt.personName}"
                }
                recordTransactionAtomic(
                    TransactionEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = resolvedAccount.id,
                        txType = txType,
                        amountMinor = signedAmount,
                        currency = normalizedDebt.currency,
                        merchant = merchant,
                        note = normalizedDebt.note.ifBlank { "Debt ledger entry" },
                        sourceTag = "MANUAL",
                        occurredAtEpochMs = now,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        pendingSync = true,
                    )
                )
            }
            true
        }
    }

    /**
     * Records a partial or full debt repayment. Auto-closes the debt when remaining hits 0 and
     * optionally records the corresponding inflow/outflow transaction on the chosen account.
     */
    suspend fun recordDebtRepaymentAtomic(
        debtId: String,
        amountMinor: Long,
        accountId: String?,
        recordTransaction: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val rawMag = abs(amountMinor)
        if (rawMag <= 0L) return false
        return withTransaction {
            val existing = debtDao().getById(debtId) ?: return@withTransaction false
            if (existing.deletedAtEpochMs != null || existing.remainingAmountMinor <= 0L) return@withTransaction false

            val applied = rawMag.coerceAtMost(existing.remainingAmountMinor)
            val newRemaining = (existing.remainingAmountMinor - applied).coerceAtLeast(0L)
            val newStatus = if (newRemaining == 0L) "CLOSED" else "ACTIVE"
            val chosenAcc = (accountId ?: existing.accountId)
                ?.let { accountDao().getById(it) }
                ?.takeIf { it.deletedAtEpochMs == null }

            debtDao().upsert(
                existing.copy(
                    remainingAmountMinor = newRemaining,
                    status = newStatus,
                    updatedAtEpochMs = nowMs,
                    pendingSync = true,
                )
            )

            if (recordTransaction && chosenAcc != null) {
                // If user lent money, repayment brings cash IN (INCOME). If borrowed, repayment sends cash OUT (EXPENSE).
                val txType = if (existing.direction == "LENT") "INCOME" else "EXPENSE"
                val signedAmount = if (txType == "INCOME") applied else -applied
                val merchant = if (existing.direction == "LENT") {
                    "Repayment from ${existing.personName}"
                } else {
                    "Repaid ${existing.personName}"
                }
                recordTransactionAtomic(
                    TransactionEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = chosenAcc.id,
                        txType = txType,
                        amountMinor = signedAmount,
                        currency = existing.currency,
                        merchant = merchant,
                        note = if (newRemaining == 0L) "Debt settled in full" else "Partial debt repayment",
                        sourceTag = "MANUAL",
                        occurredAtEpochMs = nowMs,
                        createdAtEpochMs = nowMs,
                        updatedAtEpochMs = nowMs,
                        pendingSync = true,
                    )
                )
            }
            true
        }
    }

    /**
     * Adds funds toward a Savings Goal, auto-marking REACHED once target is met, and optionally
     * deducts the contribution from the chosen active account.
     */
    suspend fun addGoalContributionAtomic(
        goalId: String,
        amountMinor: Long,
        accountId: String?,
        deductFromAccount: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val mag = abs(amountMinor)
        if (mag <= 0L) return false
        return withTransaction {
            val existing = savingsGoalDao().getById(goalId) ?: return@withTransaction false
            if (existing.deletedAtEpochMs != null) return@withTransaction false

            val newSaved = (existing.savedAmountMinor + mag).coerceAtLeast(0L)
            val newStatus = when {
                existing.targetAmountMinor > 0L && newSaved >= existing.targetAmountMinor -> "REACHED"
                existing.status == "REACHED" -> "ACTIVE"
                else -> existing.status
            }
            val chosenAcc = (accountId ?: existing.linkedAccountId)
                ?.let { accountDao().getById(it) }
                ?.takeIf { it.deletedAtEpochMs == null }

            savingsGoalDao().upsert(
                existing.copy(
                    savedAmountMinor = newSaved,
                    status = newStatus,
                    updatedAtEpochMs = nowMs,
                    pendingSync = true,
                )
            )

            if (deductFromAccount && chosenAcc != null) {
                recordTransactionAtomic(
                    TransactionEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = chosenAcc.id,
                        txType = "EXPENSE",
                        amountMinor = -mag,
                        currency = existing.currency,
                        merchant = "Goal: ${existing.name}",
                        note = "Savings goal contribution",
                        sourceTag = "MANUAL",
                        occurredAtEpochMs = nowMs,
                        createdAtEpochMs = nowMs,
                        updatedAtEpochMs = nowMs,
                        pendingSync = true,
                    )
                )
            }
            true
        }
    }

    /**
     * Converts checked shopping list items into a real EXPENSE transaction and clears those converted
     * items from the active checklist inside a single SQLite transaction.
     */
    suspend fun convertShoppingItemsToExpenseAtomic(
        listId: String,
        itemIds: List<String>,
        accountId: String,
        categoryId: String?,
        totalMinor: Long,
        currency: String,
        merchantTitle: String,
        removeConvertedItems: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val cleanMag = abs(totalMinor)
        if (cleanMag <= 0L || itemIds.isEmpty()) return false
        return withTransaction {
            val account = accountDao().getById(accountId)?.takeIf { it.deletedAtEpochMs == null }
                ?: return@withTransaction false
            val listItems = shoppingItemDao().getActiveForList(listId).filter { it.id in itemIds }
            if (listItems.isEmpty()) return@withTransaction false

            val itemizedNote = listItems.joinToString(", ") { item ->
                if (item.estimatedPriceMinor > 0L) {
                    "${item.name} (${com.ledgerflow.app.domain.MoneyFormatter.formatMinor(item.estimatedPriceMinor, currency)})"
                } else {
                    item.name
                }
            }.take(300)

            recordTransactionAtomic(
                TransactionEntity(
                    id = UUID.randomUUID().toString(),
                    accountId = account.id,
                    categoryId = categoryId,
                    categoryIdsCsv = categoryId.orEmpty(),
                    txType = "EXPENSE",
                    amountMinor = -cleanMag,
                    currency = currency,
                    merchant = merchantTitle.trim().ifBlank { "Shopping List" },
                    note = itemizedNote,
                    sourceTag = "MANUAL",
                    occurredAtEpochMs = nowMs,
                    createdAtEpochMs = nowMs,
                    updatedAtEpochMs = nowMs,
                    pendingSync = true,
                )
            )

            if (removeConvertedItems) {
                val cleared = listItems.map {
                    it.copy(deletedAtEpochMs = nowMs, updatedAtEpochMs = nowMs, pendingSync = true)
                }
                shoppingItemDao().upsertAll(cleared)
            }
            true
        }
    }

    private suspend fun deleteAllRowsLocked() {
        // Delete child transactions first so Room's FTS4 external-content triggers clean transactions_fts cleanly.
        transactionDao().deleteAll()
        shoppingItemDao().deleteAll()
        shoppingListDao().deleteAll()
        savingsGoalDao().deleteAll()
        debtDao().deleteAll()
        plannedPaymentDao().deleteAll()
        budgetDao().deleteAll()
        holdingDao().deleteAll()
        magicRuleDao().deleteAll()
        accountDao().deleteAll()
        categoryDao().deleteAll()
    }

    suspend fun resetAllDataFresh(
        defaultCurrency: String = "USD",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        withTransaction {
            deleteAllRowsLocked()
            SeedData.ensureMinimalSetup(this, defaultCurrency = defaultCurrency, nowMs = nowMs)
        }
    }

    suspend fun alignStarterWalletCurrencyIfEmpty(
        currency: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val cleanCur = currency.trim().uppercase()
        if (cleanCur.length != 3) return
        withTransaction {
            val accounts = accountDao().getAllActive()
            val txs = transactionDao().getAllActive()
            if (accounts.size == 1 && txs.isEmpty()) {
                val only = accounts.first()
                if (only.accountType == "CASH" && only.balanceMinor == 0L && only.currency != cleanCur) {
                    accountDao().upsert(only.copy(currency = cleanCur, updatedAtEpochMs = nowMs, pendingSync = true))
                }
            }
        }
    }

    companion object {
        private const val MAX_AUTOPAY_CATCHUP_STEPS = 24

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN creditLimitMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE categories ADD COLUMN parentCategoryId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN categoryIdsCsv TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `debts` (`id` TEXT NOT NULL, `personName` TEXT NOT NULL, `direction` TEXT NOT NULL, `totalAmountMinor` INTEGER NOT NULL, `remainingAmountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `accountId` TEXT, `dueAtEpochMs` INTEGER, `note` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_debts_updatedAtEpochMs` ON `debts` (`updatedAtEpochMs`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `savings_goals` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `targetAmountMinor` INTEGER NOT NULL, `savedAmountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `targetDateEpochMs` INTEGER, `iconKey` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `linkedAccountId` TEXT, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_goals_updatedAtEpochMs` ON `savings_goals` (`updatedAtEpochMs`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shopping_lists` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `defaultCategoryId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_lists_updatedAtEpochMs` ON `shopping_lists` (`updatedAtEpochMs`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shopping_items` (`id` TEXT NOT NULL, `listId` TEXT NOT NULL, `name` TEXT NOT NULL, `estimatedPriceMinor` INTEGER NOT NULL, `checked` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_listId` ON `shopping_items` (`listId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_updatedAtEpochMs` ON `shopping_items` (`updatedAtEpochMs`)")
            }
        }

        fun advanceDueDateEpochMs(currentDueMs: Long, cadence: String): Long =
            AnalyticsCalculator.advanceDueDate(currentDueMs, cadence)

        @Volatile
        private var INSTANCE: LedgerDatabase? = null

        fun getInstance(context: Context): LedgerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    "ledgerflow_vault.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
