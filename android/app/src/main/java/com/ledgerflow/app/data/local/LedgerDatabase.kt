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
import com.ledgerflow.app.domain.CategoryTree
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.ParsedShoppingItem
import com.ledgerflow.app.domain.ShoppingUnitCatalog
import com.ledgerflow.app.domain.VoiceShoppingParser
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

enum class ClearDataSection(
    val title: String,
    val subtitle: String,
) {
    CONFIRMED_TRANSACTIONS(
        title = "Confirmed Transactions",
        subtitle = "Recorded expenses, income, transfers & search history",
    ),
    PENDING_REVIEW_PAYMENTS(
        title = "Pending Review Payments",
        subtitle = "Auto-detected & Smart-Pasted payments awaiting review",
    ),
    ACCOUNTS(
        title = "Accounts & Wallets",
        subtitle = "Deletes custom accounts and resets Cash Wallet to zero",
    ),
    CATEGORIES(
        title = "Custom Categories & Edits",
        subtitle = "Deletes user-created categories and restores default tree",
    ),
    BUDGETS(
        title = "Budgets",
        subtitle = "Monthly and periodic category limits",
    ),
    PLANNED_PAYMENTS(
        title = "Planned & Recurring Bills",
        subtitle = "Scheduled bill templates & auto-pay reminders",
    ),
    HOLDINGS(
        title = "Investments & Holdings",
        subtitle = "Stocks, crypto & forex portfolio entries",
    ),
    MAGIC_RULES(
        title = "Auto-Category Rules",
        subtitle = "Merchant & keyword auto-tagging patterns",
    ),
    DEBTS(
        title = "Debts (Lent & Borrowed)",
        subtitle = "Active and closed IOUs",
    ),
    SAVINGS_GOALS(
        title = "Savings Goals",
        subtitle = "Savings targets & tracked progress",
    ),
    SHOPPING_LISTS(
        title = "Shopping Lists & Items",
        subtitle = "Checklists, item quantities & estimated prices",
    ),
}

data class ClearSelectedDataResult(
    val clearedSections: Set<ClearDataSection>,
    val fullReset: Boolean,
)

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
    version = 6,
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
            val keepSyncDirty = existing.reviewStatus != TransactionEntity.STATUS_PENDING_REVIEW
            transactionDao().upsert(existing.copy(deletedAtEpochMs = nowMs, updatedAtEpochMs = nowMs, pendingSync = keepSyncDirty))
            applyTransactionBalanceDelta(existing, signMultiplier = -1, nowMs = nowMs)
        }
    }

    enum class DetectedCaptureAction {
        CREATED_PENDING,
        UPDATED_PENDING,
        IGNORED_DUPLICATE,
        IGNORED_CONFIRMED,
        CANCELLED_BY_FAILURE,
    }

    data class DetectedCaptureResult(
        val action: DetectedCaptureAction,
        val transaction: TransactionEntity? = null,
    )

    suspend fun handleDetectedFailureNotificationAtomic(
        notificationKey: String,
        referenceId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        return withTransaction {
            // A reference id names the payment exactly. A notification key alone only does so while
            // the notification is recent, and never when the two reference ids disagree.
            val byReference = if (referenceId.isNotBlank()) transactionDao().findByReferenceId(referenceId) else null
            val target = byReference
                ?: transactionDao().findAllByNotificationKey(notificationKey).firstOrNull {
                    (referenceId.isBlank() || it.referenceId.isBlank()) &&
                        isWithinNotificationUpdateWindow(it.occurredAtEpochMs, nowMs)
                }
                ?: return@withTransaction false
            if (target.deletedAtEpochMs != null) return@withTransaction false
            if (target.reviewStatus != TransactionEntity.STATUS_PENDING_REVIEW) return@withTransaction false
            softDeleteTransactionAtomic(target.id, nowMs = nowMs)
            true
        }
    }

    /**
     * Whether [draft] is a repost or update of [existing] rather than a new payment that happens to
     * reuse its notification key. When unsure this says no: a spare pending row can be deleted during
     * review, while a payment merged into another one is lost.
     */
    private fun isSameNotifiedPayment(existing: TransactionEntity, draft: TransactionEntity): Boolean {
        if (existing.referenceId.isNotBlank() && draft.referenceId.isNotBlank()) {
            return existing.referenceId == draft.referenceId
        }
        val sameMerchant = existing.merchant.isBlank() || draft.merchant.isBlank() ||
            existing.merchant.equals(draft.merchant, ignoreCase = true)
        // Compare what was parsed, since the user may have corrected the stored amount.
        val parsedAmount = existing.originalParsedAmountMinor ?: existing.amountMinor
        return sameMerchant &&
            parsedAmount == draft.amountMinor &&
            existing.currency == draft.currency &&
            isWithinNotificationUpdateWindow(existing.occurredAtEpochMs, draft.occurredAtEpochMs)
    }

    private fun isWithinNotificationUpdateWindow(firstSeenMs: Long, seenAgainMs: Long): Boolean =
        abs(seenAgainMs - firstSeenMs) <= NOTIFICATION_UPDATE_WINDOW_MS

    /**
     * Atomically captures or updates a detected notification payment while preventing duplicate rows,
     * preserving user-confirmed transactions, and adjusting assigned account balances strictly once.
     */
    suspend fun upsertDetectedPaymentAtomic(
        draft: TransactionEntity,
        nowMs: Long = System.currentTimeMillis(),
    ): DetectedCaptureResult {
        return withTransaction {
            // 1. Updated Android notification with the same StatusBarNotification key
            if (draft.notificationKey.isNotBlank()) {
                val existingByKey = transactionDao().findAllByNotificationKey(draft.notificationKey)
                    .firstOrNull { isSameNotifiedPayment(it, draft) }
                if (existingByKey != null) {
                    if (existingByKey.deletedAtEpochMs != null) {
                        return@withTransaction DetectedCaptureResult(DetectedCaptureAction.IGNORED_DUPLICATE, existingByKey)
                    }
                    if (existingByKey.reviewStatus == TransactionEntity.STATUS_CONFIRMED) {
                        return@withTransaction DetectedCaptureResult(DetectedCaptureAction.IGNORED_CONFIRMED, existingByKey)
                    }
                    if (existingByKey.notificationFingerprint.isNotBlank() &&
                        existingByKey.notificationFingerprint == draft.notificationFingerprint &&
                        existingByKey.amountMinor == draft.amountMinor &&
                        existingByKey.merchant == draft.merchant
                    ) {
                        return@withTransaction DetectedCaptureResult(DetectedCaptureAction.IGNORED_DUPLICATE, existingByKey)
                    }
                    val resolvedAccount = draft.accountId.ifBlank { existingByKey.accountId }
                    val resolvedMerchant = draft.merchant.ifBlank { existingByKey.merchant }
                    val updated = existingByKey.copy(
                        accountId = resolvedAccount,
                        txType = draft.txType,
                        amountMinor = draft.amountMinor,
                        currency = draft.currency,
                        convertedMinor = draft.convertedMinor,
                        convertedCurrency = draft.convertedCurrency,
                        merchant = resolvedMerchant,
                        referenceId = draft.referenceId.ifBlank { existingByKey.referenceId },
                        accountHint = draft.accountHint.ifBlank { existingByKey.accountHint },
                        paymentAppName = draft.paymentAppName.ifBlank { existingByKey.paymentAppName },
                        paymentAppPackage = draft.paymentAppPackage.ifBlank { existingByKey.paymentAppPackage },
                        notificationFingerprint = draft.notificationFingerprint,
                        originalParsedAmountMinor = draft.amountMinor,
                        isRefundOrReversal = draft.isRefundOrReversal,
                        updatedAtEpochMs = nowMs,
                    )
                    recordTransactionAtomic(updated)
                    return@withTransaction DetectedCaptureResult(DetectedCaptureAction.UPDATED_PENDING, updated)
                }
            }

            // 2. Cross-app duplicate or update sharing the same external UPI / UTR / bank reference ID
            if (draft.referenceId.isNotBlank()) {
                val existingByRef = transactionDao().findByReferenceId(draft.referenceId)
                if (existingByRef != null && existingByRef.deletedAtEpochMs == null) {
                    if (existingByRef.reviewStatus == TransactionEntity.STATUS_PENDING_REVIEW) {
                        val improvedAccount = existingByRef.accountId.ifBlank { draft.accountId }
                        val improvedMerchant = if (existingByRef.merchant.isBlank() && draft.merchant.isNotBlank()) {
                            draft.merchant
                        } else {
                            existingByRef.merchant
                        }
                        val improvedHint = existingByRef.accountHint.ifBlank { draft.accountHint }
                        if (improvedAccount != existingByRef.accountId ||
                            improvedMerchant != existingByRef.merchant ||
                            improvedHint != existingByRef.accountHint
                        ) {
                            val enriched = existingByRef.copy(
                                accountId = improvedAccount,
                                merchant = improvedMerchant,
                                accountHint = improvedHint,
                                updatedAtEpochMs = nowMs,
                            )
                            recordTransactionAtomic(enriched)
                            return@withTransaction DetectedCaptureResult(DetectedCaptureAction.UPDATED_PENDING, enriched)
                        }
                    }
                    return@withTransaction DetectedCaptureResult(DetectedCaptureAction.IGNORED_DUPLICATE, existingByRef)
                }
            }

            // 3. Durable SHA-256 fingerprint & time-window dedupe (survives process restarts)
            if (draft.notificationFingerprint.isNotBlank()) {
                val existingByFp = transactionDao().findByFingerprint(draft.notificationFingerprint)
                if (existingByFp != null) {
                    return@withTransaction DetectedCaptureResult(DetectedCaptureAction.IGNORED_DUPLICATE, existingByFp)
                }
            }

            val windowMs = 120_000L
            val recentMatch = transactionDao().findRecentDuplicate(
                amountMinor = draft.amountMinor,
                currency = draft.currency,
                merchant = draft.merchant,
                referenceId = draft.referenceId,
                minEpochMs = draft.occurredAtEpochMs - windowMs,
                maxEpochMs = draft.occurredAtEpochMs + windowMs,
            )
            if (recentMatch != null) {
                return@withTransaction DetectedCaptureResult(DetectedCaptureAction.IGNORED_DUPLICATE, recentMatch)
            }

            recordTransactionAtomic(draft)
            DetectedCaptureResult(DetectedCaptureAction.CREATED_PENDING, draft)
        }
    }

    /**
     * Transitions a PENDING_REVIEW transaction to CONFIRMED (or edits an existing CONFIRMED transaction),
     * preserving original parsed audit metadata and atomically reconciling account balances.
     */
    suspend fun confirmOrUpdateTransactionAtomic(
        txId: String,
        txType: String,
        magnitudeMinor: Long,
        currency: String,
        accountId: String,
        transferAccountId: String?,
        categoryId: String?,
        categoryIdsCsv: String,
        spendingPurpose: String,
        merchant: String,
        note: String,
        labelsCsv: String,
        markConfirmed: Boolean,
        baseCurrency: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val cleanMag = abs(magnitudeMinor)
        if (cleanMag <= 0L) return false
        return withTransaction {
            val existing = transactionDao().getById(txId) ?: return@withTransaction false
            if (existing.deletedAtEpochMs != null) return@withTransaction false
            if (accountId.isBlank()) return@withTransaction false
            val targetAcc = accountDao().getById(accountId)?.takeIf { it.deletedAtEpochMs == null }
                ?: return@withTransaction false
            val normType = when (txType.trim().uppercase()) {
                "INCOME" -> "INCOME"
                "TRANSFER" -> "TRANSFER"
                else -> "EXPENSE"
            }
            if (normType == "TRANSFER" && (transferAccountId.isNullOrBlank() || transferAccountId == targetAcc.id)) {
                return@withTransaction false
            }
            if (markConfirmed && normType != "TRANSFER" && categoryId.isNullOrBlank()) {
                return@withTransaction false
            }
            val signedMinor = when (normType) {
                "EXPENSE" -> -cleanMag
                else -> cleanMag
            }
            val cleanCur = currency.trim().uppercase().ifBlank { targetAcc.currency }
            val converted = if (cleanCur != baseCurrency) {
                FxConverter.convertMinor(signedMinor, cleanCur, baseCurrency)
            } else null

            val updated = existing.copy(
                accountId = targetAcc.id,
                transferAccountId = if (normType == "TRANSFER") transferAccountId else null,
                categoryId = if (normType == "TRANSFER") null else categoryId,
                categoryIdsCsv = if (normType == "TRANSFER") "" else categoryIdsCsv,
                txType = normType,
                amountMinor = signedMinor,
                currency = cleanCur,
                convertedMinor = converted,
                convertedCurrency = if (converted != null) baseCurrency else null,
                merchant = merchant.trim().ifBlank { existing.merchant },
                spendingPurpose = spendingPurpose.trim(),
                note = note.trim(),
                labelsCsv = labelsCsv.trim(),
                reviewStatus = if (markConfirmed) TransactionEntity.STATUS_CONFIRMED else existing.reviewStatus,
                originalParsedAmountMinor = existing.originalParsedAmountMinor ?: existing.amountMinor,
                updatedAtEpochMs = nowMs,
                pendingSync = true,
            )
            recordTransactionAtomic(updated)
            true
        }
    }

    private suspend fun applyTransactionBalanceDelta(
        tx: TransactionEntity,
        signMultiplier: Int,
        nowMs: Long,
    ) {
        if (tx.accountId.isBlank()) return
        val sourceAccount = accountDao().getById(tx.accountId) ?: return
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

    enum class ChildDeleteStrategy { MOVE_CHILDREN_TO_PARENT_OR_TARGET, DELETE_CHILDREN_SUBTREE }

    enum class UsageDeleteStrategy { REASSIGN_TO_TARGET, PRESERVE_HISTORICAL_REFERENCE }

    data class CategoryDeletionImpact(
        val category: CategoryEntity,
        val directChildrenCount: Int,
        val totalDescendantsCount: Int,
        val linkedTransactionsCount: Int,
        val linkedBudgetsCount: Int,
        val linkedPlannedCount: Int,
        val linkedMagicRulesCount: Int,
        val linkedShoppingListsCount: Int,
    ) {
        val hasChildren: Boolean get() = totalDescendantsCount > 0
        val hasLinkedUsages: Boolean
            get() = (linkedTransactionsCount + linkedBudgetsCount + linkedPlannedCount +
                linkedMagicRulesCount + linkedShoppingListsCount) > 0
    }

    sealed class CategoryMutationResult {
        data class Success(val category: CategoryEntity) : CategoryMutationResult()
        data class Error(val message: String) : CategoryMutationResult()
    }

    data class CategoryDeleteResult(
        val deletedCount: Int,
        val movedChildrenCount: Int,
        val reassignedUsagesCount: Int,
    )

    private fun parseCsvIds(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun inspectCategoryDeletionImpact(categoryId: String): CategoryDeletionImpact? {
        val allCats = categoryDao().getAllActive()
        val target = allCats.firstOrNull { it.id == categoryId } ?: return null
        val descendantIds = CategoryTree.collectDescendantIds(categoryId, allCats, includeSelf = false)
        val subtreeIds = descendantIds + categoryId
        val directChildrenCount = allCats.count { it.parentCategoryId == categoryId }

        val linkedTxCount = transactionDao().getAllActive().count { tx ->
            tx.categoryId in subtreeIds || parseCsvIds(tx.categoryIdsCsv).any { it in subtreeIds }
        }
        val linkedBudgetsCount = budgetDao().getAllActive().count { it.categoryId in subtreeIds }
        val linkedPlannedCount = plannedPaymentDao().getAllActive().count { it.categoryId in subtreeIds }
        val linkedRulesCount = magicRuleDao().getAllActive().count { it.targetCategoryId in subtreeIds }
        val linkedListsCount = shoppingListDao().getAllActive().count { it.defaultCategoryId in subtreeIds }

        return CategoryDeletionImpact(
            category = target,
            directChildrenCount = directChildrenCount,
            totalDescendantsCount = descendantIds.size,
            linkedTransactionsCount = linkedTxCount,
            linkedBudgetsCount = linkedBudgetsCount,
            linkedPlannedCount = linkedPlannedCount,
            linkedMagicRulesCount = linkedRulesCount,
            linkedShoppingListsCount = linkedListsCount,
        )
    }

    suspend fun upsertCategorySafeAtomic(
        categoryId: String?,
        name: String,
        kind: String,
        parentCategoryId: String?,
        iconKey: String,
        colorHex: String,
        description: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): CategoryMutationResult {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return CategoryMutationResult.Error("Category name is required")
        val cleanIcon = iconKey.trim().ifBlank { "category" }
        val cleanColor = colorHex.trim().ifBlank { "#059669" }
        val cleanDescription = description.trim()

        return withTransaction {
            val allActive = categoryDao().getAllActive()
            val existing = if (categoryId != null) {
                allActive.firstOrNull { it.id == categoryId }
                    ?: return@withTransaction CategoryMutationResult.Error("Category no longer exists")
            } else {
                null
            }

            val resolvedParent = if (!parentCategoryId.isNullOrBlank()) {
                val p = allActive.firstOrNull { it.id == parentCategoryId }
                    ?: return@withTransaction CategoryMutationResult.Error("Selected parent category was not found")
                if (categoryId != null && CategoryTree.wouldCreateCycle(categoryId, p.id, allActive)) {
                    return@withTransaction CategoryMutationResult.Error(
                        "Cannot move a category inside itself or one of its subcategories"
                    )
                }
                p
            } else {
                null
            }

            val resolvedKind = resolvedParent?.kind
                ?: if (kind.trim().equals("INCOME", ignoreCase = true)) "INCOME" else "EXPENSE"

            if (CategoryTree.hasDuplicateSibling(cleanName, resolvedKind, resolvedParent?.id, categoryId, allActive)) {
                val scopeLabel = resolvedParent?.let { "under \"${it.name}\"" } ?: "at the top level"
                return@withTransaction CategoryMutationResult.Error(
                    "\"$cleanName\" already exists $scopeLabel"
                )
            }

            val saved = if (existing == null) {
                CategoryEntity(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    kind = resolvedKind,
                    iconKey = cleanIcon,
                    colorHex = cleanColor,
                    parentCategoryId = resolvedParent?.id,
                    createdAtEpochMs = nowMs,
                    updatedAtEpochMs = nowMs,
                    pendingSync = true,
                    description = cleanDescription,
                )
            } else {
                existing.copy(
                    name = cleanName,
                    kind = resolvedKind,
                    iconKey = cleanIcon,
                    colorHex = cleanColor,
                    parentCategoryId = resolvedParent?.id,
                    updatedAtEpochMs = nowMs,
                    pendingSync = true,
                    description = cleanDescription,
                )
            }

            val toUpsert = mutableListOf(saved)
            if (existing != null && saved.kind != existing.kind) {
                val descendantIds = CategoryTree.collectDescendantIds(existing.id, allActive, includeSelf = false)
                allActive.filter { it.id in descendantIds && it.kind != saved.kind }.forEach { child ->
                    toUpsert += child.copy(kind = saved.kind, updatedAtEpochMs = nowMs, pendingSync = true)
                }
            }
            categoryDao().upsertAll(toUpsert)
            CategoryMutationResult.Success(saved)
        }
    }

    suspend fun deleteCategoryWithSafeguardsAtomic(
        categoryId: String,
        childStrategy: ChildDeleteStrategy = ChildDeleteStrategy.MOVE_CHILDREN_TO_PARENT_OR_TARGET,
        moveChildrenTargetParentId: String? = null,
        usageStrategy: UsageDeleteStrategy = UsageDeleteStrategy.PRESERVE_HISTORICAL_REFERENCE,
        reassignCategoryId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): CategoryDeleteResult = withTransaction {
        val allCats = categoryDao().getAllActive()
        val target = allCats.firstOrNull { it.id == categoryId }
            ?: return@withTransaction CategoryDeleteResult(0, 0, 0)
        val descendantIds = CategoryTree.collectDescendantIds(categoryId, allCats, includeSelf = false)
        val deletedIds: Set<String> = if (childStrategy == ChildDeleteStrategy.DELETE_CHILDREN_SUBTREE) {
            descendantIds + categoryId
        } else {
            setOf(categoryId)
        }

        val catUpdates = mutableListOf<CategoryEntity>()
        allCats.filter { it.id in deletedIds }.forEach { cat ->
            catUpdates += cat.copy(deletedAtEpochMs = nowMs, updatedAtEpochMs = nowMs, pendingSync = true)
        }

        var movedChildrenCount = 0
        if (childStrategy == ChildDeleteStrategy.MOVE_CHILDREN_TO_PARENT_OR_TARGET) {
            val safeTargetParent = moveChildrenTargetParentId
                ?.takeIf { it !in deletedIds && it !in descendantIds }
                ?.let { pid -> allCats.firstOrNull { it.id == pid } }
            val newParentId = safeTargetParent?.id
                ?: if (moveChildrenTargetParentId == null) target.parentCategoryId?.takeIf { it !in deletedIds } else null
            val targetKind = safeTargetParent?.kind
                ?: newParentId?.let { pid -> allCats.firstOrNull { it.id == pid }?.kind }
            val directKids = allCats.filter { it.parentCategoryId == categoryId }
            directKids.forEach { kid ->
                movedChildrenCount++
                val nextKind = targetKind ?: kid.kind
                catUpdates += kid.copy(
                    parentCategoryId = newParentId,
                    kind = nextKind,
                    updatedAtEpochMs = nowMs,
                    pendingSync = true,
                )
                if (nextKind != kid.kind) {
                    val kidSubtree = CategoryTree.collectDescendantIds(kid.id, allCats, includeSelf = false)
                    allCats.filter { it.id in kidSubtree && it.kind != nextKind }.forEach { sub ->
                        catUpdates += sub.copy(kind = nextKind, updatedAtEpochMs = nowMs, pendingSync = true)
                    }
                }
            }
        }

        if (catUpdates.isNotEmpty()) {
            categoryDao().upsertAll(catUpdates.distinctBy { it.id })
        }

        val validReplacementId = reassignCategoryId
            ?.takeIf { it !in deletedIds }
            ?.let { rid -> allCats.firstOrNull { it.id == rid }?.id }

        var reassignedUsagesCount = 0
        if (usageStrategy == UsageDeleteStrategy.REASSIGN_TO_TARGET && validReplacementId != null) {
            val txUpdates = transactionDao().getAllActive().mapNotNull { tx ->
                val oldCsv = parseCsvIds(tx.categoryIdsCsv)
                val touchesPrimary = tx.categoryId in deletedIds
                val touchesCsv = oldCsv.any { it in deletedIds }
                if (!touchesPrimary && !touchesCsv) {
                    null
                } else {
                    reassignedUsagesCount++
                    val newPrimary = if (touchesPrimary) validReplacementId else tx.categoryId
                    val mappedCsv = oldCsv
                        .map { id -> if (id in deletedIds) validReplacementId else id }
                        .distinct()
                        .ifEmpty { listOfNotNull(newPrimary) }
                    tx.copy(
                        categoryId = newPrimary,
                        categoryIdsCsv = mappedCsv.joinToString(","),
                        updatedAtEpochMs = nowMs,
                        pendingSync = true,
                    )
                }
            }
            if (txUpdates.isNotEmpty()) transactionDao().upsertAll(txUpdates)

            val budgetUpdates = budgetDao().getAllActive()
                .filter { it.categoryId in deletedIds }
                .map {
                    reassignedUsagesCount++
                    it.copy(categoryId = validReplacementId, updatedAtEpochMs = nowMs, pendingSync = true)
                }
            if (budgetUpdates.isNotEmpty()) budgetDao().upsertAll(budgetUpdates)

            val plannedUpdates = plannedPaymentDao().getAllActive()
                .filter { it.categoryId in deletedIds }
                .map {
                    reassignedUsagesCount++
                    it.copy(categoryId = validReplacementId, updatedAtEpochMs = nowMs, pendingSync = true)
                }
            if (plannedUpdates.isNotEmpty()) plannedPaymentDao().upsertAll(plannedUpdates)

            val ruleUpdates = magicRuleDao().getAllActive()
                .filter { it.targetCategoryId in deletedIds }
                .map {
                    reassignedUsagesCount++
                    it.copy(targetCategoryId = validReplacementId, updatedAtEpochMs = nowMs, pendingSync = true)
                }
            if (ruleUpdates.isNotEmpty()) magicRuleDao().upsertAll(ruleUpdates)

            val listUpdates = shoppingListDao().getAllActive()
                .filter { it.defaultCategoryId in deletedIds }
                .map {
                    reassignedUsagesCount++
                    it.copy(defaultCategoryId = validReplacementId, updatedAtEpochMs = nowMs, pendingSync = true)
                }
            if (listUpdates.isNotEmpty()) shoppingListDao().upsertAll(listUpdates)
        }

        CategoryDeleteResult(
            deletedCount = deletedIds.size,
            movedChildrenCount = movedChildrenCount,
            reassignedUsagesCount = reassignedUsagesCount,
        )
    }

    enum class ConvertShoppingOutcomeStatus {
        SUCCESS,
        INVALID_INPUT,
        MISSING_ACCOUNT,
        NO_MATCHING_ITEMS,
        DUPLICATE_ALREADY_CONVERTED,
    }

    data class ConvertShoppingExpenseResult(
        val status: ConvertShoppingOutcomeStatus,
        val transactionId: String? = null,
        val convertedItemCount: Int = 0,
    ) {
        val succeeded: Boolean get() = status == ConvertShoppingOutcomeStatus.SUCCESS
    }

    suspend fun addShoppingItemAtomic(
        listId: String,
        name: String,
        quantityValue: Double?,
        quantityUnit: String,
        estimatedPriceMinor: Long,
        legacyQuantityLabel: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): ShoppingItemEntity? {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return null
        val cleanPrice = estimatedPriceMinor.coerceAtLeast(0L)
        val parsedFallback = if (quantityValue == null && quantityUnit.isBlank() && legacyQuantityLabel.isNotBlank()) {
            ShoppingUnitCatalog.parseLegacyLabel(legacyQuantityLabel)
        } else {
            null
        }
        val finalQty = (quantityValue ?: parsedFallback?.quantityValue)?.takeIf { it.isFinite() && it > 0.0 }
        val finalUnit = ShoppingUnitCatalog.normalizeUnit(quantityUnit.ifBlank { parsedFallback?.quantityUnit.orEmpty() })
        val finalLabel = ShoppingUnitCatalog.formatQuantityLabel(finalQty, finalUnit, legacyQuantityLabel)

        return withTransaction {
            val parentList = shoppingListDao().getById(listId)?.takeIf { it.deletedAtEpochMs == null }
                ?: return@withTransaction null
            val item = ShoppingItemEntity(
                id = UUID.randomUUID().toString(),
                listId = parentList.id,
                name = cleanName,
                estimatedPriceMinor = cleanPrice,
                checked = false,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs,
                pendingSync = true,
                quantityLabel = finalLabel,
                quantityValue = finalQty,
                quantityUnit = finalUnit,
            )
            shoppingItemDao().upsert(item)
            shoppingListDao().upsert(parentList.copy(updatedAtEpochMs = nowMs, pendingSync = true))
            item
        }
    }

    suspend fun updateShoppingItemAtomic(
        itemId: String,
        name: String,
        quantityValue: Double?,
        quantityUnit: String,
        estimatedPriceMinor: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): ShoppingItemEntity? {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return null
        val cleanPrice = estimatedPriceMinor.coerceAtLeast(0L)
        val cleanQty = quantityValue?.takeIf { it.isFinite() && it > 0.0 }
        val cleanUnit = ShoppingUnitCatalog.normalizeUnit(quantityUnit)
        val cleanLabel = ShoppingUnitCatalog.formatQuantityLabel(cleanQty, cleanUnit)

        return withTransaction {
            val existing = shoppingItemDao().getById(itemId)?.takeIf { it.deletedAtEpochMs == null }
                ?: return@withTransaction null
            val updated = existing.copy(
                name = cleanName,
                quantityValue = cleanQty,
                quantityUnit = cleanUnit,
                quantityLabel = cleanLabel,
                estimatedPriceMinor = cleanPrice,
                updatedAtEpochMs = nowMs,
                pendingSync = true,
            )
            shoppingItemDao().upsert(updated)
            shoppingListDao().getById(existing.listId)?.takeIf { it.deletedAtEpochMs == null }?.let { list ->
                shoppingListDao().upsert(list.copy(updatedAtEpochMs = nowMs, pendingSync = true))
            }
            updated
        }
    }

    suspend fun clearCompletedShoppingItemsAtomic(
        listId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Int = withTransaction {
        val checked = shoppingItemDao().getActiveForList(listId).filter { it.checked }
        if (checked.isEmpty()) return@withTransaction 0
        shoppingItemDao().upsertAll(
            checked.map { it.copy(deletedAtEpochMs = nowMs, updatedAtEpochMs = nowMs, pendingSync = true) }
        )
        shoppingListDao().getById(listId)?.takeIf { it.deletedAtEpochMs == null }?.let { list ->
            shoppingListDao().upsert(list.copy(updatedAtEpochMs = nowMs, pendingSync = true))
        }
        checked.size
    }

    suspend fun clearAllShoppingItemsAtomic(
        listId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Int = withTransaction {
        val items = shoppingItemDao().getActiveForList(listId)
        if (items.isNotEmpty()) {
            shoppingItemDao().upsertAll(
                items.map { it.copy(deletedAtEpochMs = nowMs, updatedAtEpochMs = nowMs, pendingSync = true) }
            )
        }
        shoppingListDao().getById(listId)?.takeIf { it.deletedAtEpochMs == null }?.let { list ->
            shoppingListDao().upsert(
                list.copy(
                    lastConvertedTxId = null,
                    lastConvertedSignature = "",
                    updatedAtEpochMs = nowMs,
                    pendingSync = true,
                )
            )
        }
        items.size
    }

    /**
     * Converts selected/completed shopping list items into an EXPENSE transaction while preserving
     * the original shopping list by default and guarding against duplicate conversions.
     */
    suspend fun convertShoppingItemsToExpenseAtomic(
        listId: String,
        itemIds: List<String>,
        accountId: String,
        categoryId: String?,
        totalMinor: Long,
        currency: String,
        merchantTitle: String,
        removeConvertedItems: Boolean = false,
        allowDuplicate: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): ConvertShoppingExpenseResult {
        val cleanMag = abs(totalMinor)
        if (cleanMag <= 0L || itemIds.isEmpty()) {
            return ConvertShoppingExpenseResult(ConvertShoppingOutcomeStatus.INVALID_INPUT)
        }
        return withTransaction {
            val account = accountDao().getById(accountId)?.takeIf { it.deletedAtEpochMs == null }
                ?: return@withTransaction ConvertShoppingExpenseResult(ConvertShoppingOutcomeStatus.MISSING_ACCOUNT)
            val listItems = shoppingItemDao().getActiveForList(listId).filter { it.id in itemIds }
            if (listItems.isEmpty()) {
                return@withTransaction ConvertShoppingExpenseResult(ConvertShoppingOutcomeStatus.NO_MATCHING_ITEMS)
            }

            val signature = buildString {
                append(listId).append('|')
                append(cleanMag).append('|')
                append(currency.trim().uppercase(Locale.US)).append('|')
                append(categoryId.orEmpty()).append('|')
                listItems.sortedBy { it.id }.forEach { item ->
                    val qLabel = ShoppingUnitCatalog.formatQuantityLabel(item.quantityValue, item.quantityUnit, item.quantityLabel)
                    append(item.id).append(':')
                        .append(item.name.trim()).append(':')
                        .append(qLabel).append(':')
                        .append(item.estimatedPriceMinor).append(';')
                }
            }

            val parentList = shoppingListDao().getById(listId)?.takeIf { it.deletedAtEpochMs == null }
            if (!allowDuplicate && parentList != null &&
                parentList.lastConvertedSignature == signature &&
                !parentList.lastConvertedTxId.isNullOrBlank()
            ) {
                val existingTx = transactionDao().getById(parentList.lastConvertedTxId)
                    ?.takeIf { it.deletedAtEpochMs == null }
                if (existingTx != null) {
                    return@withTransaction ConvertShoppingExpenseResult(
                        status = ConvertShoppingOutcomeStatus.DUPLICATE_ALREADY_CONVERTED,
                        transactionId = existingTx.id,
                    )
                }
            }

            val itemizedNote = listItems.joinToString(", ") { item ->
                val qtyLabel = ShoppingUnitCatalog.formatQuantityLabel(item.quantityValue, item.quantityUnit, item.quantityLabel)
                val display = ParsedShoppingItem.formatDisplay(item.name, qtyLabel)
                if (item.estimatedPriceMinor > 0L) {
                    "$display (${MoneyFormatter.formatMinor(item.estimatedPriceMinor, currency)})"
                } else {
                    display
                }
            }.take(300)

            val txId = UUID.randomUUID().toString()
            recordTransactionAtomic(
                TransactionEntity(
                    id = txId,
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

            parentList?.let { list ->
                shoppingListDao().upsert(
                    list.copy(
                        lastConvertedTxId = txId,
                        lastConvertedSignature = signature,
                        updatedAtEpochMs = nowMs,
                        pendingSync = true,
                    )
                )
            }

            if (removeConvertedItems) {
                val cleared = listItems.map {
                    it.copy(deletedAtEpochMs = nowMs, updatedAtEpochMs = nowMs, pendingSync = true)
                }
                shoppingItemDao().upsertAll(cleared)
            }
            ConvertShoppingExpenseResult(
                status = ConvertShoppingOutcomeStatus.SUCCESS,
                transactionId = txId,
                convertedItemCount = listItems.size,
            )
        }
    }

    data class AddVoiceShoppingOutcome(
        val listId: String,
        val listTitle: String,
        val addedCount: Int,
        val skippedDuplicateCount: Int,
        val createdNewList: Boolean,
    )

    suspend fun toggleShoppingItemCheckedAtomic(
        itemId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ShoppingItemEntity? = withTransaction {
        val existing = shoppingItemDao().getById(itemId) ?: return@withTransaction null
        if (existing.deletedAtEpochMs != null) return@withTransaction null
        val updated = existing.copy(
            checked = !existing.checked,
            updatedAtEpochMs = nowMs,
            pendingSync = true,
        )
        shoppingItemDao().upsert(updated)
        shoppingListDao().getById(existing.listId)?.takeIf { it.deletedAtEpochMs == null }?.let { parentList ->
            shoppingListDao().upsert(parentList.copy(updatedAtEpochMs = nowMs, pendingSync = true))
        }
        updated
    }

    suspend fun addParsedShoppingItemsAtomic(
        listId: String?,
        items: List<ParsedShoppingItem>,
        defaultListTitle: String = "Shopping List",
        nowMs: Long = System.currentTimeMillis(),
    ): AddVoiceShoppingOutcome {
        return withTransaction {
            val activeLists = shoppingListDao().getAllActive()
                .filter { it.deletedAtEpochMs == null }
                .sortedByDescending { it.updatedAtEpochMs }
            val existingTarget = listId?.let { targetId ->
                activeLists.firstOrNull { it.id == targetId }
            } ?: activeLists.firstOrNull()

            val createdNew = existingTarget == null
            val targetList = existingTarget ?: ShoppingListEntity(
                id = UUID.randomUUID().toString(),
                title = defaultListTitle.trim().ifBlank { "Shopping List" },
                defaultCategoryId = categoryDao().getById(SeedData.CAT_GROCERIES)
                    ?.takeIf { it.deletedAtEpochMs == null }
                    ?.id,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs,
                pendingSync = true,
            )
            shoppingListDao().upsert(targetList.copy(updatedAtEpochMs = nowMs, pendingSync = true))

            val existingActive = shoppingItemDao().getActiveForList(targetList.id)
            val existingKeys = existingActive
                .filter { !it.checked }
                .map { VoiceShoppingParser.normalizeKey(it.name) }
                .toHashSet()

            val toInsert = mutableListOf<ShoppingItemEntity>()
            var skipped = 0
            items.forEachIndexed { index, parsed ->
                val cleanName = parsed.name.trim()
                val key = VoiceShoppingParser.normalizeKey(cleanName)
                if (cleanName.isBlank() || key.isBlank()) return@forEachIndexed
                if (!existingKeys.add(key)) {
                    skipped++
                    return@forEachIndexed
                }
                val itemTs = nowMs + index.toLong()
                val parsedQty = ShoppingUnitCatalog.parseLegacyLabel(parsed.quantityLabel)
                toInsert += ShoppingItemEntity(
                    id = UUID.randomUUID().toString(),
                    listId = targetList.id,
                    name = cleanName,
                    quantityLabel = parsedQty.label,
                    quantityValue = parsedQty.quantityValue,
                    quantityUnit = parsedQty.quantityUnit,
                    estimatedPriceMinor = 0L,
                    checked = false,
                    createdAtEpochMs = itemTs,
                    updatedAtEpochMs = itemTs,
                    pendingSync = true,
                )
            }
            if (toInsert.isNotEmpty()) {
                shoppingItemDao().upsertAll(toInsert)
            }
            AddVoiceShoppingOutcome(
                listId = targetList.id,
                listTitle = targetList.title,
                addedCount = toInsert.size,
                skippedDuplicateCount = skipped,
                createdNewList = createdNew,
            )
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

    /**
     * Atomically clears only the chosen Room database sections while preserving referential integrity
     * and account balances across untouched modules.
     */
    suspend fun clearSelectedDataAtomic(
        sections: Set<ClearDataSection>,
        zeroKeptAccountBalances: Boolean = false,
        defaultCurrency: String = "USD",
        nowMs: Long = System.currentTimeMillis(),
    ): ClearSelectedDataResult {
        if (sections.isEmpty()) {
            return ClearSelectedDataResult(emptySet(), fullReset = false)
        }
        if (sections.containsAll(ClearDataSection.entries)) {
            resetAllDataFresh(defaultCurrency = defaultCurrency, nowMs = nowMs)
            return ClearSelectedDataResult(ClearDataSection.entries.toSet(), fullReset = true)
        }

        withTransaction {
            val clearConfirmed = ClearDataSection.CONFIRMED_TRANSACTIONS in sections
            val clearPending = ClearDataSection.PENDING_REVIEW_PAYMENTS in sections
            val clearAccounts = ClearDataSection.ACCOUNTS in sections
            val clearShopping = ClearDataSection.SHOPPING_LISTS in sections

            if (clearShopping) {
                shoppingItemDao().deleteAll()
                shoppingListDao().deleteAll()
            }
            if (ClearDataSection.BUDGETS in sections) {
                budgetDao().deleteAll()
            }
            if (ClearDataSection.PLANNED_PAYMENTS in sections) {
                plannedPaymentDao().deleteAll()
            }
            if (ClearDataSection.HOLDINGS in sections) {
                holdingDao().deleteAll()
            }
            if (ClearDataSection.MAGIC_RULES in sections) {
                magicRuleDao().deleteAll()
            }
            if (ClearDataSection.DEBTS in sections) {
                debtDao().deleteAll()
            }
            if (ClearDataSection.SAVINGS_GOALS in sections) {
                savingsGoalDao().deleteAll()
            }

            if (clearConfirmed || clearPending) {
                val existingTxs = transactionDao().getAllIncludingDeleted()
                val deletedTxs = when {
                    clearConfirmed && clearPending -> existingTxs
                    clearConfirmed -> existingTxs.filter { it.reviewStatus != TransactionEntity.STATUS_PENDING_REVIEW }
                    else -> existingTxs.filter { it.reviewStatus == TransactionEntity.STATUS_PENDING_REVIEW }
                }
                val deletedIds = deletedTxs.map { it.id }.toHashSet()

                if (!clearAccounts) {
                    if (clearConfirmed && zeroKeptAccountBalances) {
                        val activeAccounts = accountDao().getAllActive()
                        if (activeAccounts.isNotEmpty()) {
                            accountDao().upsertAll(
                                activeAccounts.map {
                                    it.copy(balanceMinor = 0L, updatedAtEpochMs = nowMs, pendingSync = true)
                                }
                            )
                        }
                    } else {
                        deletedTxs
                            .filter { it.deletedAtEpochMs == null }
                            .forEach { applyTransactionBalanceDelta(it, signMultiplier = -1, nowMs = nowMs) }
                    }
                }

                when {
                    clearConfirmed && clearPending -> transactionDao().deleteAll()
                    clearConfirmed -> transactionDao().deleteConfirmed()
                    else -> transactionDao().deletePendingReview()
                }

                if (!clearShopping && deletedIds.isNotEmpty()) {
                    val lists = shoppingListDao().getAllActive()
                        .filter { it.lastConvertedTxId != null && it.lastConvertedTxId in deletedIds }
                    if (lists.isNotEmpty()) {
                        shoppingListDao().upsertAll(
                            lists.map {
                                it.copy(
                                    lastConvertedTxId = null,
                                    lastConvertedSignature = "",
                                    updatedAtEpochMs = nowMs,
                                    pendingSync = true,
                                )
                            }
                        )
                    }
                }
            }

            if (clearAccounts) {
                accountDao().deleteAll()
                SeedData.ensureMinimalSetup(this, defaultCurrency = defaultCurrency, nowMs = nowMs)

                if (!(clearConfirmed && clearPending)) {
                    val keptTxs = transactionDao().getAllIncludingDeleted()
                    if (keptTxs.isNotEmpty()) {
                        transactionDao().upsertAll(
                            keptTxs.map { tx ->
                                tx.copy(
                                    accountId = TransactionEntity.UNASSIGNED_ACCOUNT_ID,
                                    transferAccountId = null,
                                    updatedAtEpochMs = nowMs,
                                    pendingSync = true,
                                )
                            }
                        )
                    }
                }
                if (ClearDataSection.PLANNED_PAYMENTS !in sections) {
                    val keptPlanned = plannedPaymentDao().getAllActive().filter { it.accountId != null }
                    if (keptPlanned.isNotEmpty()) {
                        plannedPaymentDao().upsertAll(
                            keptPlanned.map { it.copy(accountId = null, updatedAtEpochMs = nowMs, pendingSync = true) }
                        )
                    }
                }
                if (ClearDataSection.DEBTS !in sections) {
                    val keptDebts = debtDao().getAllActive().filter { it.accountId != null }
                    if (keptDebts.isNotEmpty()) {
                        debtDao().upsertAll(
                            keptDebts.map { it.copy(accountId = null, updatedAtEpochMs = nowMs, pendingSync = true) }
                        )
                    }
                }
                if (ClearDataSection.SAVINGS_GOALS !in sections) {
                    val keptGoals = savingsGoalDao().getAllActive().filter { it.linkedAccountId != null }
                    if (keptGoals.isNotEmpty()) {
                        savingsGoalDao().upsertAll(
                            keptGoals.map { it.copy(linkedAccountId = null, updatedAtEpochMs = nowMs, pendingSync = true) }
                        )
                    }
                }
            }

            if (ClearDataSection.CATEGORIES in sections) {
                val starterCategories = SeedData.starterCategories(nowMs)
                val validCategoryIds = starterCategories.map { it.id }.toHashSet()
                categoryDao().deleteCustomCategories(starterCategories.map { it.id })
                categoryDao().upsertAll(starterCategories)

                if (!(clearConfirmed && clearPending)) {
                    val keptTxs = transactionDao().getAllIncludingDeleted().filter { tx ->
                        (tx.categoryId != null && tx.categoryId !in validCategoryIds) ||
                            tx.categoryIdsCsv.split(',').any { raw ->
                                val clean = raw.trim()
                                clean.isNotEmpty() && clean !in validCategoryIds
                            }
                    }
                    if (keptTxs.isNotEmpty()) {
                        transactionDao().upsertAll(
                            keptTxs.map { tx ->
                                val keptIds = tx.categoryIdsCsv.split(',')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() && it in validCategoryIds }
                                val primary = tx.categoryId?.takeIf { it in validCategoryIds } ?: keptIds.firstOrNull()
                                tx.copy(
                                    categoryId = primary,
                                    categoryIdsCsv = keptIds.joinToString(","),
                                    updatedAtEpochMs = nowMs,
                                    pendingSync = true,
                                )
                            }
                        )
                    }
                }
                if (ClearDataSection.BUDGETS !in sections) {
                    val keptBudgets = budgetDao().getAllActive()
                        .filter { it.categoryId != null && it.categoryId !in validCategoryIds }
                    if (keptBudgets.isNotEmpty()) {
                        budgetDao().upsertAll(
                            keptBudgets.map { it.copy(categoryId = null, updatedAtEpochMs = nowMs, pendingSync = true) }
                        )
                    }
                }
                if (ClearDataSection.PLANNED_PAYMENTS !in sections) {
                    val keptPlanned = plannedPaymentDao().getAllActive()
                        .filter { it.categoryId != null && it.categoryId !in validCategoryIds }
                    if (keptPlanned.isNotEmpty()) {
                        plannedPaymentDao().upsertAll(
                            keptPlanned.map { it.copy(categoryId = null, updatedAtEpochMs = nowMs, pendingSync = true) }
                        )
                    }
                }
                if (ClearDataSection.MAGIC_RULES !in sections) {
                    val keptRules = magicRuleDao().getAllActive()
                        .filter { it.targetCategoryId != null && it.targetCategoryId !in validCategoryIds }
                    if (keptRules.isNotEmpty()) {
                        magicRuleDao().upsertAll(
                            keptRules.map { it.copy(targetCategoryId = null, updatedAtEpochMs = nowMs, pendingSync = true) }
                        )
                    }
                }
                if (!clearShopping) {
                    val keptLists = shoppingListDao().getAllActive()
                        .filter { it.defaultCategoryId != null && it.defaultCategoryId !in validCategoryIds }
                    if (keptLists.isNotEmpty()) {
                        shoppingListDao().upsertAll(
                            keptLists.map { it.copy(defaultCategoryId = null, updatedAtEpochMs = nowMs, pendingSync = true) }
                        )
                    }
                }
            }
        }
        return ClearSelectedDataResult(sections.toSet(), fullReset = false)
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
        private const val NOTIFICATION_UPDATE_WINDOW_MS = 10 * 60_000L

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN reviewStatus TEXT NOT NULL DEFAULT 'CONFIRMED'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN spendingPurpose TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN paymentAppName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN paymentAppPackage TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN referenceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN accountHint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN notificationKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN notificationFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN originalParsedAmountMinor INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN isRefundOrReversal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_reviewStatus` ON `transactions` (`reviewStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_referenceId` ON `transactions` (`referenceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_notificationKey` ON `transactions` (`notificationKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_notificationFingerprint` ON `transactions` (`notificationFingerprint`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shopping_items ADD COLUMN quantityLabel TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_parentCategoryId` ON `categories` (`parentCategoryId`)")
                db.execSQL("ALTER TABLE shopping_lists ADD COLUMN lastConvertedTxId TEXT")
                db.execSQL("ALTER TABLE shopping_lists ADD COLUMN lastConvertedSignature TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE shopping_items ADD COLUMN quantityValue REAL")
                db.execSQL("ALTER TABLE shopping_items ADD COLUMN quantityUnit TEXT NOT NULL DEFAULT ''")
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
