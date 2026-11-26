package com.ledgerflow.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
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
    ],
    version = 1,
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

    private suspend fun deleteAllRowsLocked() {
        // Delete child transactions first so Room's FTS4 external-content triggers clean transactions_fts cleanly.
        transactionDao().deleteAll()
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
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
