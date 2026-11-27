package com.ledgerflow.app.domain

import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.TransactionEntity
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

data class WidgetFinancialMetrics(
    val todaySpendMinor: Long,
    val liquidBalanceMinor: Long,
    val hasAnyTransactions: Boolean,
    val hasAnyAccounts: Boolean,
)

/**
 * Pure domain calculations for LedgerFlow home screen widgets and quick overlays.
 * Reuses [AccountTypeCatalog] and [FxConverter] so numbers always agree with Analytics & Accounts.
 */
object WidgetMetricsCalculator {

    fun dayBoundsEpochMs(
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Pair<Long, Long> {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val endMs = cal.timeInMillis - 1L
        return startMs to endMs
    }

    fun computeTodaySpendMinor(
        transactions: List<TransactionEntity>,
        baseCurrency: String,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val (startMs, endMs) = dayBoundsEpochMs(nowMs = nowMs, timeZone = timeZone)
        var total = 0L
        for (tx in transactions) {
            if (tx.deletedAtEpochMs != null) continue
            if (!tx.txType.equals("EXPENSE", ignoreCase = true)) continue
            if (tx.occurredAtEpochMs !in startMs..endMs) continue
            total += abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
        }
        return total
    }

    /**
     * Liquid cash, debit card, checking, and savings balances converted to [baseCurrency].
     * Explicitly excludes credit cards, loans, liabilities, and investment accounts.
     */
    fun computeLiquidBalanceMinor(
        accounts: List<AccountEntity>,
        baseCurrency: String,
    ): Long {
        var total = 0L
        for (acc in accounts) {
            if (acc.deletedAtEpochMs != null) continue
            if (AccountTypeCatalog.isLiability(acc.accountType)) continue
            if (AccountTypeCatalog.normalize(acc.accountType) == "INVESTMENT") continue
            total += FxConverter.convertMinor(acc.balanceMinor, acc.currency, baseCurrency).coerceAtLeast(0L)
        }
        return total
    }

    fun computeWidgetMetrics(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        baseCurrency: String,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): WidgetFinancialMetrics {
        val activeAccounts = accounts.filter { it.deletedAtEpochMs == null }
        val activeTxs = transactions.filter { it.deletedAtEpochMs == null }
        return WidgetFinancialMetrics(
            todaySpendMinor = computeTodaySpendMinor(
                transactions = activeTxs,
                baseCurrency = baseCurrency,
                nowMs = nowMs,
                timeZone = timeZone,
            ),
            liquidBalanceMinor = computeLiquidBalanceMinor(
                accounts = activeAccounts,
                baseCurrency = baseCurrency,
            ),
            hasAnyTransactions = activeTxs.isNotEmpty(),
            hasAnyAccounts = activeAccounts.isNotEmpty(),
        )
    }

    /**
     * Ranks up to [limit] real active categories matching [txType] by recent transaction usage.
     */
    fun rankTopCategories(
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        txType: String,
        limit: Int = 6,
    ): List<CategoryEntity> {
        val wantType = txType.trim().uppercase()
        val eligible = categories.filter { cat ->
            cat.deletedAtEpochMs == null &&
                (cat.kind.equals(wantType, ignoreCase = true) || cat.kind.equals("BOTH", ignoreCase = true))
        }
        if (eligible.isEmpty()) return emptyList()

        val counts = HashMap<String, Int>(eligible.size)
        for (tx in transactions) {
            if (tx.deletedAtEpochMs != null) continue
            if (!tx.txType.equals(wantType, ignoreCase = true)) continue
            tx.categoryId?.takeIf { it.isNotBlank() }?.let { cid ->
                counts[cid] = (counts[cid] ?: 0) + 1
            }
            if (tx.categoryIdsCsv.isNotBlank()) {
                for (rawId in tx.categoryIdsCsv.split(',')) {
                    val cleanId = rawId.trim()
                    if (cleanId.isNotEmpty() && cleanId != tx.categoryId) {
                        counts[cleanId] = (counts[cleanId] ?: 0) + 1
                    }
                }
            }
        }

        // Prefer child categories over redundant parents only as a gentle tie-breaker when unused,
        // and keep usage frequency dominant so frequent categories always appear first.
        return eligible
            .sortedWith(
                compareByDescending<CategoryEntity> { counts[it.id] ?: 0 }
                    .thenBy { it.parentCategoryId == null }
                    .thenBy { it.name.lowercase() }
            )
            .take(limit.coerceAtLeast(1))
    }
}
