package com.ledgerflow.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.ledgerflow.app.domain.FxConverter
import java.util.Calendar
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

    suspend fun executePlannedPaymentAtomic(plannedId: String, fallbackAccountId: String?, nowMs: Long = System.currentTimeMillis()) {
        withTransaction {
            val item = plannedPaymentDao().getById(plannedId) ?: return@withTransaction
            if (item.deletedAtEpochMs != null) return@withTransaction
            val accountId = item.accountId ?: fallbackAccountId ?: return@withTransaction
            val signedAmount = if (item.txType == "EXPENSE") -abs(item.amountMinor) else abs(item.amountMinor)
            val tx = TransactionEntity(
                id = UUID.randomUUID().toString(),
                accountId = accountId,
                categoryId = item.categoryId,
                txType = item.txType,
                amountMinor = signedAmount,
                currency = item.currency,
                merchant = item.title,
                note = "Planned ${item.cadence.lowercase()} payment",
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
        }
    }

    companion object {
        fun advanceDueDateEpochMs(currentDueMs: Long, cadence: String): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = currentDueMs }
            when (cadence.uppercase()) {
                "WEEKLY" -> cal.add(Calendar.DAY_OF_YEAR, 7)
                "YEARLY" -> cal.add(Calendar.YEAR, 1)
                else -> cal.add(Calendar.MONTH, 1)
            }
            return cal.timeInMillis
        }
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
