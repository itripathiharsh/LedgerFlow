package com.ledgerflow.app.domain

import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.data.local.PlannedPaymentEntity
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.local.TransactionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

enum class AnalyticsRangePreset(val label: String) {
    WEEKS_4("4W"),
    WEEKS_12("12W"),
    MONTH_1("1M"),
    MONTHS_3("3M"),
    MONTHS_6("6M"),
    YEAR_1("1Y"),
    CUSTOM("Custom"),
}

data class AnalyticsDateRange(
    val preset: AnalyticsRangePreset,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val label: String,
)

data class TrendPoint(
    val label: String,
    val epochMs: Long,
    val valueMinor: Long,
)

data class BalanceTrendReport(
    val points: List<TrendPoint>,
    val startBalanceMinor: Long,
    val endBalanceMinor: Long,
    val minMinor: Long,
    val maxMinor: Long,
    val changeMinor: Long,
    val changePercent: Float,
)

data class AccountBalanceRow(
    val id: String,
    val name: String,
    val accountType: String,
    val typeLabel: String,
    val currency: String,
    val nativeBalanceMinor: Long,
    val baseBalanceMinor: Long,
    val isLiability: Boolean,
    val shareRatio: Float,
)

data class CategoryNetBalanceRow(
    val categoryId: String,
    val name: String,
    val colorHex: String,
    val inflowMinor: Long,
    val outflowMinor: Long,
    val netMinor: Long,
    val txCount: Int,
)

data class PlannedTimelineItem(
    val id: String,
    val title: String,
    val txType: String,
    val cadence: String,
    val dueAtEpochMs: Long,
    val dueDateLabel: String,
    val amountBaseMinor: Long,
    val runningBalanceMinor: Long,
    val isOverdue: Boolean,
    val autoPay: Boolean,
    val accountName: String?,
)

data class BalanceForecastReport(
    val horizonDays: Int,
    val startingBalanceMinor: Long,
    val projectedEndBalanceMinor: Long,
    val netProjectedDeltaMinor: Long,
    val scheduledInflowMinor: Long,
    val scheduledOutflowMinor: Long,
    val curvePoints: List<TrendPoint>,
    val timeline: List<PlannedTimelineItem>,
)

data class CashFlowBucket(
    val label: String,
    val inflowMinor: Long,
    val outflowMinor: Long,
    val netMinor: Long,
)

data class PeriodMoverRow(
    val name: String,
    val currentMinor: Long,
    val previousMinor: Long,
    val deltaMinor: Long,
)

data class PeriodComparisonReport(
    val currentLabel: String,
    val previousLabel: String,
    val currentInflowMinor: Long,
    val previousInflowMinor: Long,
    val currentOutflowMinor: Long,
    val previousOutflowMinor: Long,
    val currentNetMinor: Long,
    val previousNetMinor: Long,
    val inflowDeltaPct: Float?,
    val outflowDeltaPct: Float?,
    val topExpenseMovers: List<PeriodMoverRow>,
)

data class SpendingNatureSummary(
    val needsMinor: Long,
    val wantsMinor: Long,
    val savingsAndDebtMinor: Long,
    val totalClassifiedMinor: Long,
    val needsRatio: Float,
    val wantsRatio: Float,
    val savingsRatio: Float,
    val weekdaySpendMinor: Long,
    val weekendSpendMinor: Long,
    val topMerchants: List<BreakdownItem>,
)

data class CardUtilizationRow(
    val accountId: String,
    val name: String,
    val lastFour: String,
    val usedBaseMinor: Long,
    val limitBaseMinor: Long,
    val availableBaseMinor: Long,
    val utilizationPercent: Float?,
)

data class RecurringDebtRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val balanceOrAmountMinor: Long,
    val monthlyEquivalentMinor: Long,
    val isAccountDebt: Boolean,
)

data class CreditAndDebtReport(
    val cards: List<CardUtilizationRow>,
    val totalCreditUsedMinor: Long,
    val totalCreditLimitMinor: Long,
    val overallUtilizationPct: Float?,
    val totalLoansOwedMinor: Long,
    val totalDebtMinor: Long,
    val avgMonthlyIncomeMinor: Long,
    val monthlyDebtServiceMinor: Long,
    val debtToIncomePct: Float?,
    val recurringDebtItems: List<RecurringDebtRow>,
)

data class FinancialHealthExtras(
    val liquidAssetsMinor: Long,
    val investmentValueMinor: Long,
    val investmentCostMinor: Long,
    val totalGrossAssetsMinor: Long,
    val totalLiabilitiesMinor: Long,
    val netWorthMinor: Long,
    val avgMonthlySpendMinor: Long,
    val emergencyRunwayMonths: Float?,
    val annualizedSubscriptionsMinor: Long,
    val avgDailySpendMinor: Long,
)

data class BreakdownItem(
    val id: String,
    val name: String,
    val colorHex: String,
    val amountMinor: Long,
    val txCount: Int,
    val ratio: Float,
)

data class AnalyticsBundle(
    val range: AnalyticsDateRange,
    val balanceTrend: BalanceTrendReport,
    val accountBalances: List<AccountBalanceRow>,
    val categoryNetBalances: List<CategoryNetBalanceRow>,
    val balanceForecast: BalanceForecastReport,
    val periodInflowMinor: Long,
    val periodOutflowMinor: Long,
    val periodNetMinor: Long,
    val periodSavingsRatePct: Int,
    val cashFlowBuckets: List<CashFlowBucket>,
    val periodComparison: PeriodComparisonReport,
    val spendingByParentCategory: List<BreakdownItem>,
    val spendingBySubcategory: List<BreakdownItem>,
    val spendingByLabels: List<BreakdownItem>,
    val spendingNature: SpendingNatureSummary,
    val creditAndDebt: CreditAndDebtReport,
    val financialHealth: FinancialHealthExtras,
)

object CategoryHierarchy {
    private val needParentIds = setOf(
        SeedData.CAT_GROCERIES,
        SeedData.CAT_FUEL,
        SeedData.CAT_TRANSPORT,
        SeedData.CAT_BILLS,
        SeedData.CAT_HEALTH,
        SeedData.CAT_EDUCATION,
        SeedData.CAT_EMI_DEBT,
    )

    private val needKeywords = listOf(
        "grocer", "fuel", "petrol", "diesel", "ev ", "rent", "utilit", "electric",
        "water", "gas", "bill", "health", "medic", "doctor", "pharma", "transport",
        "commute", "metro", "bus", "edu", "tuition", "emi", "loan", "debt", "tax", "insur",
    )

    fun TransactionEntity.allAssignedCategoryIds(): List<String> {
        val list = ArrayList<String>(4)
        categoryId?.trim()?.takeIf { it.isNotEmpty() }?.let { list.add(it) }
        if (categoryIdsCsv.isNotBlank()) {
            categoryIdsCsv.split(',').forEach { raw ->
                val clean = raw.trim()
                if (clean.isNotEmpty() && !list.contains(clean)) {
                    list.add(clean)
                }
            }
        }
        return list
    }

    fun resolveTopLevelCategory(
        catId: String?,
        catMap: Map<String, CategoryEntity>,
    ): CategoryEntity? {
        val cat = catId?.let { catMap[it] } ?: return null
        val parent = cat.parentCategoryId?.let { catMap[it] }
        return parent ?: cat
    }

    fun isEssentialNeed(
        catId: String?,
        catMap: Map<String, CategoryEntity>,
    ): Boolean {
        val direct = catId?.let { catMap[it] }
        val parent = direct?.parentCategoryId?.let { catMap[it] }
        val topId = parent?.id ?: direct?.id
        if (topId != null && topId in needParentIds) return true
        val combinedName = listOfNotNull(parent?.name, direct?.name).joinToString(" ").lowercase(Locale.US)
        return needKeywords.any { combinedName.contains(it) }
    }

    /**
     * Splits [amountMinor] across [ids] deterministically without losing remainder minor units
     * or inflating totals across multi-selected categories.
     */
    fun allocateAcrossIds(amountMinor: Long, ids: List<String>): List<Pair<String?, Long>> {
        if (ids.isEmpty()) return listOf(null to amountMinor)
        if (ids.size == 1) return listOf(ids.first() to amountMinor)
        val count = ids.size.toLong()
        val baseShare = amountMinor / count
        var rem = amountMinor - (baseShare * count)
        return ids.mapIndexed { idx, id ->
            val extra = if (rem > 0L) {
                rem--
                1L
            } else {
                0L
            }
            id to (baseShare + extra)
        }
    }
}

object AnalyticsEngine {

    fun buildDateRange(
        preset: AnalyticsRangePreset,
        nowMs: Long = System.currentTimeMillis(),
        customStartMs: Long? = null,
        customEndMs: Long? = null,
    ): AnalyticsDateRange {
        val endCal = Calendar.getInstance().apply {
            timeInMillis = if (preset == AnalyticsRangePreset.CUSTOM && customEndMs != null) customEndMs else nowMs
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val startCal = Calendar.getInstance().apply {
            timeInMillis = endCal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (preset) {
            AnalyticsRangePreset.WEEKS_4 -> startCal.add(Calendar.DAY_OF_YEAR, -27)
            AnalyticsRangePreset.WEEKS_12 -> startCal.add(Calendar.DAY_OF_YEAR, -83)
            AnalyticsRangePreset.MONTH_1 -> startCal.set(Calendar.DAY_OF_MONTH, 1)
            AnalyticsRangePreset.MONTHS_3 -> {
                startCal.set(Calendar.DAY_OF_MONTH, 1)
                startCal.add(Calendar.MONTH, -2)
            }
            AnalyticsRangePreset.MONTHS_6 -> {
                startCal.set(Calendar.DAY_OF_MONTH, 1)
                startCal.add(Calendar.MONTH, -5)
            }
            AnalyticsRangePreset.YEAR_1 -> {
                startCal.set(Calendar.DAY_OF_MONTH, 1)
                startCal.add(Calendar.MONTH, -11)
            }
            AnalyticsRangePreset.CUSTOM -> {
                if (customStartMs != null) {
                    startCal.timeInMillis = customStartMs
                    startCal.set(Calendar.HOUR_OF_DAY, 0)
                    startCal.set(Calendar.MINUTE, 0)
                    startCal.set(Calendar.SECOND, 0)
                    startCal.set(Calendar.MILLISECOND, 0)
                } else {
                    startCal.add(Calendar.DAY_OF_YEAR, -29)
                }
            }
        }
        val finalStart = minOf(startCal.timeInMillis, endCal.timeInMillis)
        val finalEnd = maxOf(startCal.timeInMillis, endCal.timeInMillis)
        val fmt = SimpleDateFormat("MMM d", Locale.US)
        val fmtYear = SimpleDateFormat("MMM d, yy", Locale.US)
        val spanDays = ((finalEnd - finalStart) / 86_400_000L) + 1L
        val label = if (spanDays > 180L) {
            "${fmtYear.format(Date(finalStart))} - ${fmtYear.format(Date(finalEnd))}"
        } else {
            "${fmt.format(Date(finalStart))} - ${fmt.format(Date(finalEnd))}"
        }
        return AnalyticsDateRange(
            preset = preset,
            startEpochMs = finalStart,
            endEpochMs = finalEnd,
            label = label,
        )
    }

    fun computeFullAnalytics(
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        planned: List<PlannedPaymentEntity>,
        holdings: List<HoldingEntity>,
        baseCurrency: String,
        range: AnalyticsDateRange,
        nowMs: Long = System.currentTimeMillis(),
    ): AnalyticsBundle {
        val activeAccounts = accounts.filter { it.deletedAtEpochMs == null }
        val activeCategories = categories.filter { it.deletedAtEpochMs == null }
        val catMap = activeCategories.associateBy { it.id }
        val activeTxs = transactions.filter { it.deletedAtEpochMs == null }
        val activePlanned = planned.filter { it.deletedAtEpochMs == null }

        val periodTxs = activeTxs.filter { it.occurredAtEpochMs in range.startEpochMs..range.endEpochMs }

        var inflow = 0L
        var outflow = 0L
        for (tx in periodTxs) {
            val amt = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
            when (tx.txType) {
                "INCOME" -> inflow += amt
                "EXPENSE" -> outflow += amt
            }
        }
        val netFlow = inflow - outflow
        val savingsRate = if (inflow > 0L) {
            ((netFlow.toDouble() / inflow.toDouble()) * 100.0).roundToLong().toInt().coerceIn(-999, 100)
        } else {
            0
        }

        val spanDays = (((range.endEpochMs - range.startEpochMs) / 86_400_000L) + 1L).coerceAtLeast(1L)

        val accountSummary = AnalyticsCalculator.computeAccountSummary(activeAccounts, baseCurrency)
        val balanceTrend = computeBalanceTrend(activeAccounts, activeTxs, baseCurrency, range, nowMs)
        val accountBalances = computeBalanceByAccounts(activeAccounts, baseCurrency, accountSummary)
        val categoryNetBalances = computeCategoryNetBalances(periodTxs, catMap, baseCurrency)
        val forecastHorizonDays = spanDays.toInt().coerceIn(30, 120)
        val balanceForecast = computeForecastAndTimeline(
            startingBalanceMinor = accountSummary.netWorthMinor,
            planned = activePlanned,
            accounts = activeAccounts,
            baseCurrency = baseCurrency,
            horizonDays = forecastHorizonDays,
            nowMs = nowMs,
        )
        val cashFlowBuckets = computeCashFlowBuckets(periodTxs, baseCurrency, range)
        val periodComparison = computePeriodComparison(activeTxs, catMap, baseCurrency, range)

        val spendingByParent = computeSpendingBreakdown(periodTxs, catMap, baseCurrency, rollUpToParent = true)
        val spendingBySub = computeSpendingBreakdown(periodTxs, catMap, baseCurrency, rollUpToParent = false)
        val spendingByLabels = computeSpendingByLabels(periodTxs, baseCurrency)
        val nature = computeSpendingNature(periodTxs, activeAccounts, catMap, baseCurrency)
        val creditDebt = computeCreditAndDebt(
            activeAccounts = activeAccounts,
            activePlanned = activePlanned,
            periodTxs = periodTxs,
            catMap = catMap,
            baseCurrency = baseCurrency,
            spanDays = spanDays,
        )
        val health = computeFinancialHealth(
            activeAccounts = activeAccounts,
            holdings = holdings,
            periodTxs = periodTxs,
            activePlanned = activePlanned,
            catMap = catMap,
            baseCurrency = baseCurrency,
            spanDays = spanDays,
            periodOutflowMinor = outflow,
            accountSummary = accountSummary,
        )

        return AnalyticsBundle(
            range = range,
            balanceTrend = balanceTrend,
            accountBalances = accountBalances,
            categoryNetBalances = categoryNetBalances,
            balanceForecast = balanceForecast,
            periodInflowMinor = inflow,
            periodOutflowMinor = outflow,
            periodNetMinor = netFlow,
            periodSavingsRatePct = savingsRate,
            cashFlowBuckets = cashFlowBuckets,
            periodComparison = periodComparison,
            spendingByParentCategory = spendingByParent,
            spendingBySubcategory = spendingBySub,
            spendingByLabels = spendingByLabels,
            spendingNature = nature,
            creditAndDebt = creditDebt,
            financialHealth = health,
        )
    }

    private fun computeBalanceTrend(
        activeAccounts: List<AccountEntity>,
        activeTxs: List<TransactionEntity>,
        baseCurrency: String,
        range: AnalyticsDateRange,
        nowMs: Long,
    ): BalanceTrendReport {
        val currentNetWorth = AnalyticsCalculator.computeAccountSummary(activeAccounts, baseCurrency).netWorthMinor
        val effectiveEnd = minOf(range.endEpochMs, nowMs)
        val spanMs = (effectiveEnd - range.startEpochMs).coerceAtLeast(86_400_000L)
        val bucketCount = when {
            spanMs <= 35L * 86_400_000L -> ((spanMs / 86_400_000L).toInt() + 1).coerceIn(4, 15)
            spanMs <= 120L * 86_400_000L -> 12
            else -> 12
        }
        val dayFmt = SimpleDateFormat("MMM d", Locale.US)

        val timestamps = (0 until bucketCount).map { idx ->
            if (idx == bucketCount - 1) effectiveEnd
            else range.startEpochMs + ((spanMs.toDouble() * idx.toDouble()) / (bucketCount - 1).coerceAtLeast(1)).roundToLong()
        }

        val points = timestamps.map { t ->
            // Reconstruct point-in-time balance: current net worth minus net inflow/outflow occurring strictly after t
            val deltaAfter = activeTxs
                .asSequence()
                .filter { it.occurredAtEpochMs > t && it.occurredAtEpochMs <= nowMs }
                .sumOf { tx ->
                    val mag = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
                    when (tx.txType) {
                        "INCOME" -> mag
                        "EXPENSE" -> -mag
                        else -> 0L
                    }
                }
            TrendPoint(
                label = dayFmt.format(Date(t)),
                epochMs = t,
                valueMinor = currentNetWorth - deltaAfter,
            )
        }

        val startBal = points.firstOrNull()?.valueMinor ?: currentNetWorth
        val endBal = points.lastOrNull()?.valueMinor ?: currentNetWorth
        val minBal = points.minOfOrNull { it.valueMinor } ?: currentNetWorth
        val maxBal = points.maxOfOrNull { it.valueMinor } ?: currentNetWorth
        val change = endBal - startBal
        val changePct = if (startBal != 0L) ((change.toDouble() / abs(startBal).toDouble()) * 100.0).toFloat() else 0f
        return BalanceTrendReport(
            points = points,
            startBalanceMinor = startBal,
            endBalanceMinor = endBal,
            minMinor = minBal,
            maxMinor = maxBal,
            changeMinor = change,
            changePercent = changePct,
        )
    }

    fun computeAccountBalanceTrend(
        account: AccountEntity,
        transactions: List<TransactionEntity>,
        days: Int = 30,
        nowMs: Long = System.currentTimeMillis(),
    ): BalanceTrendReport {
        val activeTxs = transactions.filter {
            it.deletedAtEpochMs == null && (it.accountId == account.id || it.transferAccountId == account.id)
        }
        val effectiveSpanDays = days.coerceIn(7, 90)
        val startMs = nowMs - (effectiveSpanDays.toLong() * 86_400_000L)
        val bucketCount = 8
        val spanMs = (nowMs - startMs).coerceAtLeast(86_400_000L)
        val fmt = SimpleDateFormat("MMM d", Locale.US)

        val timestamps = (0 until bucketCount).map { idx ->
            if (idx == bucketCount - 1) nowMs
            else startMs + ((spanMs.toDouble() * idx.toDouble()) / (bucketCount - 1).coerceAtLeast(1)).roundToLong()
        }

        val points = timestamps.map { t ->
            val deltaAfter = activeTxs
                .asSequence()
                .filter { it.occurredAtEpochMs > t && it.occurredAtEpochMs <= nowMs }
                .sumOf { tx ->
                    val magInAccCurrency = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, account.currency))
                    when {
                        tx.accountId == account.id && tx.txType == "INCOME" -> magInAccCurrency
                        tx.accountId == account.id && (tx.txType == "EXPENSE" || tx.txType == "TRANSFER") -> -magInAccCurrency
                        tx.txType == "TRANSFER" && tx.transferAccountId == account.id -> magInAccCurrency
                        else -> 0L
                    }
                }
            TrendPoint(
                label = fmt.format(Date(t)),
                epochMs = t,
                valueMinor = account.balanceMinor - deltaAfter,
            )
        }

        val startBal = points.firstOrNull()?.valueMinor ?: account.balanceMinor
        val endBal = points.lastOrNull()?.valueMinor ?: account.balanceMinor
        val minBal = points.minOfOrNull { it.valueMinor } ?: account.balanceMinor
        val maxBal = points.maxOfOrNull { it.valueMinor } ?: account.balanceMinor
        val change = endBal - startBal
        val changePct = if (startBal != 0L) ((change.toDouble() / abs(startBal).toDouble()) * 100.0).toFloat() else 0f
        return BalanceTrendReport(
            points = points,
            startBalanceMinor = startBal,
            endBalanceMinor = endBal,
            minMinor = minBal,
            maxMinor = maxBal,
            changeMinor = change,
            changePercent = changePct,
        )
    }

    private fun computeBalanceByAccounts(
        activeAccounts: List<AccountEntity>,
        baseCurrency: String,
        summary: AnalyticsCalculator.AccountSummaryMetrics,
    ): List<AccountBalanceRow> {
        val assetDenom = summary.assetsMinor.coerceAtLeast(1L).toFloat()
        val debtDenom = summary.liabilitiesMinor.coerceAtLeast(1L).toFloat()
        return activeAccounts.map { acc ->
            val converted = FxConverter.convertMinor(acc.balanceMinor, acc.currency, baseCurrency)
            val isDebt = converted < 0L || AccountTypeCatalog.isLiability(acc.accountType)
            val ratio = if (converted < 0L) {
                (abs(converted).toFloat() / debtDenom).coerceIn(0f, 1f)
            } else {
                (converted.toFloat() / assetDenom).coerceIn(0f, 1f)
            }
            AccountBalanceRow(
                id = acc.id,
                name = acc.name,
                accountType = acc.accountType,
                typeLabel = AccountTypeCatalog.displayLabel(acc.accountType),
                currency = acc.currency,
                nativeBalanceMinor = acc.balanceMinor,
                baseBalanceMinor = converted,
                isLiability = isDebt,
                shareRatio = ratio,
            )
        }.sortedByDescending { abs(it.baseBalanceMinor) }
    }

    private fun computeCategoryNetBalances(
        periodTxs: List<TransactionEntity>,
        catMap: Map<String, CategoryEntity>,
        baseCurrency: String,
    ): List<CategoryNetBalanceRow> {
        val inflowAcc = HashMap<String, Long>()
        val outflowAcc = HashMap<String, Long>()
        val counts = HashMap<String, Int>()
        val names = HashMap<String, String>()
        val colors = HashMap<String, String>()

        for (tx in periodTxs) {
            if (tx.txType != "INCOME" && tx.txType != "EXPENSE") continue
            val mag = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
            if (mag == 0L) continue
            val assignedIds = with(CategoryHierarchy) { tx.allAssignedCategoryIds() }
            val topIds = assignedIds.map { CategoryHierarchy.resolveTopLevelCategory(it, catMap)?.id ?: "__uncat__" }.distinct()
            val allocations = CategoryHierarchy.allocateAcrossIds(mag, topIds.filter { it != "__uncat__" }.ifEmpty { emptyList() })
            for ((catId, share) in allocations) {
                val cat = catId?.let { catMap[it] }
                val key = cat?.id ?: "__uncat__"
                names.putIfAbsent(key, cat?.name ?: "Uncategorized")
                colors.putIfAbsent(key, cat?.colorHex ?: "#64748B")
                if (tx.txType == "INCOME") {
                    inflowAcc[key] = (inflowAcc[key] ?: 0L) + share
                } else {
                    outflowAcc[key] = (outflowAcc[key] ?: 0L) + share
                }
                counts[key] = (counts[key] ?: 0) + 1
            }
        }

        return names.keys.map { key ->
            val inf = inflowAcc[key] ?: 0L
            val out = outflowAcc[key] ?: 0L
            CategoryNetBalanceRow(
                categoryId = key,
                name = names[key] ?: "Other",
                colorHex = colors[key] ?: "#64748B",
                inflowMinor = inf,
                outflowMinor = out,
                netMinor = inf - out,
                txCount = counts[key] ?: 0,
            )
        }.sortedByDescending { abs(it.netMinor) }
    }

    fun computeForecastAndTimeline(
        startingBalanceMinor: Long,
        planned: List<PlannedPaymentEntity>,
        accounts: List<AccountEntity>,
        baseCurrency: String,
        horizonDays: Int = 60,
        nowMs: Long = System.currentTimeMillis(),
    ): BalanceForecastReport {
        val horizonMs = nowMs + horizonDays.toLong() * 86_400_000L
        val accNames = accounts.associate { it.id to it.name }
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        data class RawEvent(
            val id: String,
            val title: String,
            val txType: String,
            val cadence: String,
            val dueMs: Long,
            val amountBaseMinor: Long,
            val isOverdue: Boolean,
            val autoPay: Boolean,
            val accountName: String?,
        )

        val events = mutableListOf<RawEvent>()
        for (p in planned) {
            if (p.deletedAtEpochMs != null || p.amountMinor <= 0L) continue
            val amountBase = abs(FxConverter.convertMinor(p.amountMinor, p.currency, baseCurrency))
            var due = p.nextDueAtEpochMs
            var steps = 0
            // Include overdue occurrence first at today's effective milestone, then step future cycles within horizon
            if (due < todayStart) {
                events.add(
                    RawEvent(
                        id = "${p.id}_overdue",
                        title = p.title,
                        txType = p.txType,
                        cadence = p.cadence,
                        dueMs = due,
                        amountBaseMinor = amountBase,
                        isOverdue = true,
                        autoPay = p.autoPay,
                        accountName = p.accountId?.let { accNames[it] },
                    )
                )
                while (due < todayStart && steps < 24) {
                    val next = AnalyticsCalculator.advanceDueDate(due, p.cadence)
                    if (next <= due) break
                    due = next
                    steps++
                }
            }
            var horizonSteps = 0
            while (due <= horizonMs && horizonSteps < 24) {
                events.add(
                    RawEvent(
                        id = "${p.id}_$horizonSteps",
                        title = p.title,
                        txType = p.txType,
                        cadence = p.cadence,
                        dueMs = due,
                        amountBaseMinor = amountBase,
                        isOverdue = false,
                        autoPay = p.autoPay,
                        accountName = p.accountId?.let { accNames[it] },
                    )
                )
                val next = AnalyticsCalculator.advanceDueDate(due, p.cadence)
                if (next <= due) break
                due = next
                horizonSteps++
            }
        }

        val sorted = events.sortedBy { it.dueMs }
        val fmt = SimpleDateFormat("MMM d", Locale.US)
        var running = startingBalanceMinor
        var scheduledIn = 0L
        var scheduledOut = 0L

        val timeline = sorted.map { ev ->
            val signed = if (ev.txType.equals("INCOME", ignoreCase = true)) ev.amountBaseMinor else -ev.amountBaseMinor
            if (signed >= 0L) scheduledIn += signed else scheduledOut += abs(signed)
            running += signed
            PlannedTimelineItem(
                id = ev.id,
                title = ev.title,
                txType = ev.txType,
                cadence = ev.cadence,
                dueAtEpochMs = ev.dueMs,
                dueDateLabel = fmt.format(Date(ev.dueMs)),
                amountBaseMinor = ev.amountBaseMinor,
                runningBalanceMinor = running,
                isOverdue = ev.isOverdue,
                autoPay = ev.autoPay,
                accountName = ev.accountName,
            )
        }

        val curveCount = 8
        val stepMs = (horizonDays.toLong() * 86_400_000L) / (curveCount - 1)
        val curve = (0 until curveCount).map { idx ->
            val targetMs = nowMs + idx * stepMs
            var bal = startingBalanceMinor
            for (ev in sorted) {
                val effectiveMs = maxOf(ev.dueMs, nowMs)
                if (effectiveMs <= targetMs) {
                    bal += if (ev.txType.equals("INCOME", ignoreCase = true)) ev.amountBaseMinor else -ev.amountBaseMinor
                }
            }
            TrendPoint(
                label = if (idx == 0) "Today" else fmt.format(Date(targetMs)),
                epochMs = targetMs,
                valueMinor = bal,
            )
        }

        return BalanceForecastReport(
            horizonDays = horizonDays,
            startingBalanceMinor = startingBalanceMinor,
            projectedEndBalanceMinor = running,
            netProjectedDeltaMinor = running - startingBalanceMinor,
            scheduledInflowMinor = scheduledIn,
            scheduledOutflowMinor = scheduledOut,
            curvePoints = curve,
            timeline = timeline,
        )
    }

    private fun computeCashFlowBuckets(
        periodTxs: List<TransactionEntity>,
        baseCurrency: String,
        range: AnalyticsDateRange,
    ): List<CashFlowBucket> {
        val spanDays = ((range.endEpochMs - range.startEpochMs) / 86_400_000L) + 1L
        val useMonthly = spanDays > 92L
        val monthFmt = SimpleDateFormat("MMM yy", Locale.US)
        val weekFmt = SimpleDateFormat("MMM d", Locale.US)

        data class BucketSlot(val key: String, val startMs: Long, val endMs: Long, var inflow: Long = 0L, var outflow: Long = 0L)
        val slots = mutableListOf<BucketSlot>()
        val cursor = Calendar.getInstance().apply {
            timeInMillis = range.startEpochMs
            if (useMonthly) set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var guard = 0
        while (cursor.timeInMillis <= range.endEpochMs && guard < 26) {
            val s = cursor.timeInMillis
            val label = if (useMonthly) monthFmt.format(Date(s)) else weekFmt.format(Date(s))
            if (useMonthly) cursor.add(Calendar.MONTH, 1) else cursor.add(Calendar.DAY_OF_YEAR, 7)
            val e = minOf(cursor.timeInMillis - 1L, range.endEpochMs)
            slots.add(BucketSlot(label, s, e))
            guard++
        }
        for (tx in periodTxs) {
            val slot = slots.firstOrNull { tx.occurredAtEpochMs in it.startMs..it.endMs } ?: continue
            val mag = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
            when (tx.txType) {
                "INCOME" -> slot.inflow += mag
                "EXPENSE" -> slot.outflow += mag
            }
        }
        return slots.map {
            CashFlowBucket(
                label = it.key,
                inflowMinor = it.inflow,
                outflowMinor = it.outflow,
                netMinor = it.inflow - it.outflow,
            )
        }
    }

    private fun computePeriodComparison(
        activeTxs: List<TransactionEntity>,
        catMap: Map<String, CategoryEntity>,
        baseCurrency: String,
        range: AnalyticsDateRange,
    ): PeriodComparisonReport {
        val durationMs = (range.endEpochMs - range.startEpochMs + 1L).coerceAtLeast(86_400_000L)
        val prevEnd = range.startEpochMs - 1L
        val prevStart = prevEnd - durationMs + 1L

        val currTxs = activeTxs.filter { it.occurredAtEpochMs in range.startEpochMs..range.endEpochMs }
        val prevTxs = activeTxs.filter { it.occurredAtEpochMs in prevStart..prevEnd }

        var curIn = 0L; var curOut = 0L
        var prevIn = 0L; var prevOut = 0L
        val curByCat = HashMap<String, Long>()
        val prevByCat = HashMap<String, Long>()
        val catNames = HashMap<String, String>()

        fun accumulate(txs: List<TransactionEntity>, targetMap: HashMap<String, Long>, onInOut: (Long, Long) -> Unit) {
            var inf = 0L; var out = 0L
            for (tx in txs) {
                val mag = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
                when (tx.txType) {
                    "INCOME" -> inf += mag
                    "EXPENSE" -> {
                        out += mag
                        val ids = with(CategoryHierarchy) { tx.allAssignedCategoryIds() }
                        val topIds = ids.map { CategoryHierarchy.resolveTopLevelCategory(it, catMap)?.id ?: "__uncat__" }.distinct()
                        val shares = CategoryHierarchy.allocateAcrossIds(mag, topIds.filter { it != "__uncat__" })
                        for ((cid, share) in shares) {
                            val key = cid ?: "__uncat__"
                            catNames.putIfAbsent(key, cid?.let { catMap[it]?.name } ?: "Uncategorized")
                            targetMap[key] = (targetMap[key] ?: 0L) + share
                        }
                    }
                }
            }
            onInOut(inf, out)
        }

        accumulate(currTxs, curByCat) { i, o -> curIn = i; curOut = o }
        accumulate(prevTxs, prevByCat) { i, o -> prevIn = i; prevOut = o }

        val fmt = SimpleDateFormat("MMM d", Locale.US)
        val prevLabel = "${fmt.format(Date(prevStart))} - ${fmt.format(Date(prevEnd))}"

        val movers = (curByCat.keys + prevByCat.keys).distinct().map { key ->
            val c = curByCat[key] ?: 0L
            val p = prevByCat[key] ?: 0L
            PeriodMoverRow(
                name = catNames[key] ?: "Other",
                currentMinor = c,
                previousMinor = p,
                deltaMinor = c - p,
            )
        }.sortedByDescending { abs(it.deltaMinor) }.take(5)

        val infPct = if (prevIn > 0L) ((curIn - prevIn).toFloat() / prevIn.toFloat()) * 100f else null
        val outPct = if (prevOut > 0L) ((curOut - prevOut).toFloat() / prevOut.toFloat()) * 100f else null

        return PeriodComparisonReport(
            currentLabel = range.label,
            previousLabel = prevLabel,
            currentInflowMinor = curIn,
            previousInflowMinor = prevIn,
            currentOutflowMinor = curOut,
            previousOutflowMinor = prevOut,
            currentNetMinor = curIn - curOut,
            previousNetMinor = prevIn - prevOut,
            inflowDeltaPct = infPct,
            outflowDeltaPct = outPct,
            topExpenseMovers = movers,
        )
    }

    private fun computeSpendingBreakdown(
        periodTxs: List<TransactionEntity>,
        catMap: Map<String, CategoryEntity>,
        baseCurrency: String,
        rollUpToParent: Boolean,
    ): List<BreakdownItem> {
        val amounts = HashMap<String, Long>()
        val counts = HashMap<String, Int>()
        val names = HashMap<String, String>()
        val colors = HashMap<String, String>()
        var grandTotal = 0L

        for (tx in periodTxs) {
            if (tx.txType != "EXPENSE") continue
            val mag = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
            if (mag <= 0L) continue
            grandTotal += mag
            val assignedIds = with(CategoryHierarchy) { tx.allAssignedCategoryIds() }
            val mappedIds = if (rollUpToParent) {
                assignedIds.map { CategoryHierarchy.resolveTopLevelCategory(it, catMap)?.id }.filterNotNull().distinct()
            } else {
                assignedIds
            }
            val shares = CategoryHierarchy.allocateAcrossIds(mag, mappedIds)
            for ((cid, share) in shares) {
                val cat = cid?.let { catMap[it] }
                val key = cat?.id ?: "__uncat__"
                val parent = cat?.parentCategoryId?.let { catMap[it] }
                val displayName = if (!rollUpToParent && parent != null) {
                    "${parent.name} › ${cat.name}"
                } else {
                    cat?.name ?: "Uncategorized"
                }
                names.putIfAbsent(key, displayName)
                colors.putIfAbsent(key, cat?.colorHex ?: "#64748B")
                amounts[key] = (amounts[key] ?: 0L) + share
                counts[key] = (counts[key] ?: 0) + 1
            }
        }

        if (grandTotal <= 0L) return emptyList()
        return amounts.entries
            .sortedByDescending { it.value }
            .map { (key, amt) ->
                BreakdownItem(
                    id = key,
                    name = names[key] ?: "Uncategorized",
                    colorHex = colors[key] ?: "#64748B",
                    amountMinor = amt,
                    txCount = counts[key] ?: 0,
                    ratio = (amt.toFloat() / grandTotal.toFloat()).coerceIn(0f, 1f),
                )
            }
    }

    private fun computeSpendingByLabels(
        periodTxs: List<TransactionEntity>,
        baseCurrency: String,
    ): List<BreakdownItem> {
        val amounts = HashMap<String, Long>()
        val counts = HashMap<String, Int>()
        val labelPalette = listOf("#059669", "#0284C7", "#D97706", "#8B5CF6", "#EC4899", "#14B8A6")

        for (tx in periodTxs) {
            if (tx.txType != "EXPENSE") continue
            val mag = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
            if (mag <= 0L || tx.labelsCsv.isBlank()) continue
            val labels = tx.labelsCsv.split(',')
                .map { it.trim().trimStart('#').lowercase(Locale.US) }
                .filter { it.isNotEmpty() }
                .distinct()
            if (labels.isEmpty()) continue
            val shares = CategoryHierarchy.allocateAcrossIds(mag, labels)
            for ((lbl, share) in shares) {
                val key = lbl ?: continue
                amounts[key] = (amounts[key] ?: 0L) + share
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val totalLabeled = amounts.values.sum()
        if (totalLabeled <= 0L) return emptyList()
        return amounts.entries
            .sortedByDescending { it.value }
            .mapIndexed { idx, (lbl, amt) ->
                BreakdownItem(
                    id = lbl,
                    name = "#$lbl",
                    colorHex = labelPalette[idx % labelPalette.size],
                    amountMinor = amt,
                    txCount = counts[lbl] ?: 0,
                    ratio = (amt.toFloat() / totalLabeled.toFloat()).coerceIn(0f, 1f),
                )
            }
    }

    private fun computeSpendingNature(
        periodTxs: List<TransactionEntity>,
        activeAccounts: List<AccountEntity>,
        catMap: Map<String, CategoryEntity>,
        baseCurrency: String,
    ): SpendingNatureSummary {
        val accMap = activeAccounts.associateBy { it.id }
        var needs = 0L
        var wants = 0L
        var savingsDebt = 0L
        var weekday = 0L
        var weekend = 0L
        val merchantTotals = HashMap<String, Long>()
        val merchantCounts = HashMap<String, Int>()
        val cal = Calendar.getInstance()

        for (tx in periodTxs) {
            val mag = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
            if (mag <= 0L) continue
            when (tx.txType) {
                "EXPENSE" -> {
                    cal.timeInMillis = tx.occurredAtEpochMs
                    val dow = cal.get(Calendar.DAY_OF_WEEK)
                    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) weekend += mag else weekday += mag

                    val assignedIds = with(CategoryHierarchy) { tx.allAssignedCategoryIds() }
                    val shares = CategoryHierarchy.allocateAcrossIds(mag, assignedIds)
                    for ((cid, share) in shares) {
                        if (CategoryHierarchy.isEssentialNeed(cid, catMap)) needs += share else wants += share
                    }

                    val mKey = tx.merchant.trim().ifBlank { "General Expense" }
                    merchantTotals[mKey] = (merchantTotals[mKey] ?: 0L) + mag
                    merchantCounts[mKey] = (merchantCounts[mKey] ?: 0) + 1
                }
                "TRANSFER" -> {
                    val targetAcc = tx.transferAccountId?.let { accMap[it] }
                    val targetNorm = targetAcc?.let { AccountTypeCatalog.normalize(it.accountType) }
                    if (targetNorm == "CREDIT_CARD" || targetNorm == "LOAN" || targetNorm == "SAVINGS" || targetNorm == "INVESTMENT") {
                        savingsDebt += mag
                    }
                }
            }
        }

        val totalClassified = needs + wants + savingsDebt
        val denom = totalClassified.coerceAtLeast(1L).toFloat()
        val expTotal = (weekday + weekend).coerceAtLeast(1L).toFloat()
        val topMerchants = merchantTotals.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { (m, amt) ->
                BreakdownItem(
                    id = m,
                    name = m,
                    colorHex = "#059669",
                    amountMinor = amt,
                    txCount = merchantCounts[m] ?: 0,
                    ratio = (amt.toFloat() / expTotal).coerceIn(0f, 1f),
                )
            }

        return SpendingNatureSummary(
            needsMinor = needs,
            wantsMinor = wants,
            savingsAndDebtMinor = savingsDebt,
            totalClassifiedMinor = totalClassified,
            needsRatio = if (totalClassified > 0L) needs / denom else 0f,
            wantsRatio = if (totalClassified > 0L) wants / denom else 0f,
            savingsRatio = if (totalClassified > 0L) savingsDebt / denom else 0f,
            weekdaySpendMinor = weekday,
            weekendSpendMinor = weekend,
            topMerchants = topMerchants,
        )
    }

    private fun computeCreditAndDebt(
        activeAccounts: List<AccountEntity>,
        activePlanned: List<PlannedPaymentEntity>,
        periodTxs: List<TransactionEntity>,
        catMap: Map<String, CategoryEntity>,
        baseCurrency: String,
        spanDays: Long,
    ): CreditAndDebtReport {
        val accMap = activeAccounts.associateBy { it.id }
        val cardRows = mutableListOf<CardUtilizationRow>()
        val recurringRows = mutableListOf<RecurringDebtRow>()
        var totalUsed = 0L
        var totalLimit = 0L
        var totalLoans = 0L

        for (acc in activeAccounts) {
            val normType = AccountTypeCatalog.normalize(acc.accountType)
            val balBase = FxConverter.convertMinor(acc.balanceMinor, acc.currency, baseCurrency)
            val usedBase = if (balBase < 0L) abs(balBase) else 0L
            if (normType == "CREDIT_CARD" || (normType == "CARD" && (acc.creditLimitMinor > 0L || balBase < 0L))) {
                val limitBase = FxConverter.convertMinor(acc.creditLimitMinor.coerceAtLeast(0L), acc.currency, baseCurrency)
                val availBase = if (limitBase > 0L) (limitBase - usedBase).coerceAtLeast(0L) else 0L
                val utilPct = if (limitBase > 0L) (usedBase.toDouble() / limitBase.toDouble() * 100.0).toFloat() else null
                cardRows.add(
                    CardUtilizationRow(
                        accountId = acc.id,
                        name = acc.name,
                        lastFour = acc.lastFour,
                        usedBaseMinor = usedBase,
                        limitBaseMinor = limitBase,
                        availableBaseMinor = availBase,
                        utilizationPercent = utilPct,
                    )
                )
                totalUsed += usedBase
                if (limitBase > 0L) totalLimit += limitBase
                if (usedBase > 0L) {
                    recurringRows.add(
                        RecurringDebtRow(
                            id = acc.id,
                            title = acc.name,
                            subtitle = if (acc.lastFour.isNotBlank()) "Credit Card ••${acc.lastFour}" else "Credit Card",
                            balanceOrAmountMinor = usedBase,
                            monthlyEquivalentMinor = usedBase,
                            isAccountDebt = true,
                        )
                    )
                }
            } else if (normType == "LOAN") {
                totalLoans += usedBase
                if (usedBase > 0L) {
                    recurringRows.add(
                        RecurringDebtRow(
                            id = acc.id,
                            title = acc.name,
                            subtitle = acc.institution.ifBlank { "Loan Account" },
                            balanceOrAmountMinor = usedBase,
                            monthlyEquivalentMinor = 0L,
                            isAccountDebt = true,
                        )
                    )
                }
            }
        }

        var scheduledMonthlyDebt = 0L
        for (p in activePlanned) {
            if (!p.txType.equals("EXPENSE", ignoreCase = true) || p.amountMinor <= 0L) continue
            val linkedAcc = p.accountId?.let { accMap[it] }
            val linkedType = linkedAcc?.let { AccountTypeCatalog.normalize(it.accountType) }
            val cat = CategoryHierarchy.resolveTopLevelCategory(p.categoryId, catMap)
            val isDebtBill = linkedType == "LOAN" ||
                linkedType == "CREDIT_CARD" ||
                cat?.id == SeedData.CAT_EMI_DEBT ||
                p.title.contains("emi", ignoreCase = true) ||
                p.title.contains("loan", ignoreCase = true) ||
                p.title.contains("credit card", ignoreCase = true)
            if (isDebtBill) {
                val baseAmt = abs(FxConverter.convertMinor(p.amountMinor, p.currency, baseCurrency))
                val monthlyEq = when (p.cadence.uppercase(Locale.US)) {
                    "WEEKLY" -> ((baseAmt * 52.0) / 12.0).roundToLong()
                    "YEARLY" -> (baseAmt / 12.0).roundToLong()
                    else -> baseAmt
                }
                scheduledMonthlyDebt += monthlyEq
                recurringRows.add(
                    RecurringDebtRow(
                        id = p.id,
                        title = p.title,
                        subtitle = "${p.cadence.lowercase().replaceFirstChar { it.uppercase() }} scheduled",
                        balanceOrAmountMinor = baseAmt,
                        monthlyEquivalentMinor = monthlyEq,
                        isAccountDebt = false,
                    )
                )
            }
        }

        val monthsInSpan = (spanDays.toDouble() / 30.4375).coerceAtLeast(1.0)
        var periodIncome = 0L
        var periodDebtRepaid = 0L
        for (tx in periodTxs) {
            val mag = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
            if (tx.txType == "INCOME") {
                periodIncome += mag
            } else if (tx.txType == "TRANSFER") {
                val targetType = tx.transferAccountId?.let { accMap[it] }?.let { AccountTypeCatalog.normalize(it.accountType) }
                if (targetType == "LOAN" || targetType == "CREDIT_CARD") {
                    periodDebtRepaid += mag
                }
            } else if (tx.txType == "EXPENSE" && CategoryHierarchy.resolveTopLevelCategory(tx.categoryId, catMap)?.id == SeedData.CAT_EMI_DEBT) {
                periodDebtRepaid += mag
            }
        }
        val avgMonthlyIncome = (periodIncome.toDouble() / monthsInSpan).roundToLong()
        val avgMonthlyRepaid = (periodDebtRepaid.toDouble() / monthsInSpan).roundToLong()
        val effectiveMonthlyDebtService = maxOf(scheduledMonthlyDebt, avgMonthlyRepaid)
        val dtiPct = if (avgMonthlyIncome > 0L) {
            ((effectiveMonthlyDebtService.toDouble() / avgMonthlyIncome.toDouble()) * 100.0).toFloat()
        } else {
            null
        }
        val overallUtilPct = if (totalLimit > 0L) {
            ((totalUsed.toDouble() / totalLimit.toDouble()) * 100.0).toFloat()
        } else {
            null
        }

        return CreditAndDebtReport(
            cards = cardRows.sortedByDescending { it.utilizationPercent ?: -1f },
            totalCreditUsedMinor = totalUsed,
            totalCreditLimitMinor = totalLimit,
            overallUtilizationPct = overallUtilPct,
            totalLoansOwedMinor = totalLoans,
            totalDebtMinor = totalUsed + totalLoans,
            avgMonthlyIncomeMinor = avgMonthlyIncome,
            monthlyDebtServiceMinor = effectiveMonthlyDebtService,
            debtToIncomePct = dtiPct,
            recurringDebtItems = recurringRows,
        )
    }

    private fun computeFinancialHealth(
        activeAccounts: List<AccountEntity>,
        holdings: List<HoldingEntity>,
        periodTxs: List<TransactionEntity>,
        activePlanned: List<PlannedPaymentEntity>,
        catMap: Map<String, CategoryEntity>,
        baseCurrency: String,
        spanDays: Long,
        periodOutflowMinor: Long,
        accountSummary: AnalyticsCalculator.AccountSummaryMetrics,
    ): FinancialHealthExtras {
        val portfolio = AnalyticsCalculator.computePortfolio(holdings, baseCurrency)
        val liquidAssets = activeAccounts
            .filter { !AccountTypeCatalog.isLiability(it.accountType) }
            .sumOf { FxConverter.convertMinor(it.balanceMinor, it.currency, baseCurrency).coerceAtLeast(0L) }
        val totalGrossAssets = liquidAssets + portfolio.totalValueMinor
        val netWorthWithInvestments = totalGrossAssets - accountSummary.liabilitiesMinor

        val monthsInSpan = (spanDays.toDouble() / 30.4375).coerceAtLeast(1.0)
        val avgMonthlySpend = (periodOutflowMinor.toDouble() / monthsInSpan).roundToLong()
        val runway = if (avgMonthlySpend > 0L) (liquidAssets.toDouble() / avgMonthlySpend.toDouble()).toFloat() else null
        val avgDailySpend = periodOutflowMinor / spanDays.coerceAtLeast(1L)

        var annualSubs = 0L
        for (p in activePlanned) {
            if (!p.txType.equals("EXPENSE", ignoreCase = true) || p.amountMinor <= 0L) continue
            val topCatId = CategoryHierarchy.resolveTopLevelCategory(p.categoryId, catMap)?.id
            if (topCatId == SeedData.CAT_SUBS) {
                val baseAmt = abs(FxConverter.convertMinor(p.amountMinor, p.currency, baseCurrency))
                annualSubs += when (p.cadence.uppercase(Locale.US)) {
                    "WEEKLY" -> baseAmt * 52L
                    "YEARLY" -> baseAmt
                    else -> baseAmt * 12L
                }
            }
        }
        if (annualSubs == 0L) {
            val subPeriod = periodTxs
                .filter { it.txType == "EXPENSE" && CategoryHierarchy.resolveTopLevelCategory(it.categoryId, catMap)?.id == SeedData.CAT_SUBS }
                .sumOf { abs(FxConverter.convertMinor(it.amountMinor, it.currency, baseCurrency)) }
            annualSubs = ((subPeriod.toDouble() / monthsInSpan) * 12.0).roundToLong()
        }

        return FinancialHealthExtras(
            liquidAssetsMinor = liquidAssets,
            investmentValueMinor = portfolio.totalValueMinor,
            investmentCostMinor = portfolio.totalCostMinor,
            totalGrossAssetsMinor = totalGrossAssets,
            totalLiabilitiesMinor = accountSummary.liabilitiesMinor,
            netWorthMinor = netWorthWithInvestments,
            avgMonthlySpendMinor = avgMonthlySpend,
            emergencyRunwayMonths = runway,
            annualizedSubscriptionsMinor = annualSubs,
            avgDailySpendMinor = avgDailySpend,
        )
    }
}

object ReportExporter {
    private fun csvCell(v: String): String = "\"${v.replace("\"", "\"\"")}\""

    private fun fmtDecimal(minor: Long, currency: String): String {
        val decimals = if (currency.equals("JPY", ignoreCase = true)) 0 else 2
        val div = if (decimals == 0) 1.0 else 100.0
        return if (decimals == 0) minor.toString() else String.format(Locale.US, "%.2f", minor / div)
    }

    fun buildCashFlowReportCsv(bundle: AnalyticsBundle, currency: String): String {
        val sb = StringBuilder()
        sb.append("Report,LedgerFlow Cash Flow Statement\n")
        sb.append("Period,${csvCell(bundle.range.label)}\n")
        sb.append("Currency,$currency\n")
        sb.append("Total Inflow,${fmtDecimal(bundle.periodInflowMinor, currency)}\n")
        sb.append("Total Outflow,${fmtDecimal(bundle.periodOutflowMinor, currency)}\n")
        sb.append("Net Cash Flow,${fmtDecimal(bundle.periodNetMinor, currency)}\n")
        sb.append("Savings Rate %,${bundle.periodSavingsRatePct}%\n\n")
        sb.append("Bucket,Inflow ($currency),Outflow ($currency),Net Cash Flow ($currency)\n")
        for (b in bundle.cashFlowBuckets) {
            sb.append(csvCell(b.label)).append(',')
                .append(fmtDecimal(b.inflowMinor, currency)).append(',')
                .append(fmtDecimal(b.outflowMinor, currency)).append(',')
                .append(fmtDecimal(b.netMinor, currency)).append('\n')
        }
        return sb.toString()
    }

    fun buildIncomeExpenseReportCsv(
        bundle: AnalyticsBundle,
        transactions: List<TransactionEntity>,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        currency: String,
    ): String {
        val catMap = categories.associateBy { it.id }
        val accMap = accounts.associateBy { it.id }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder()
        sb.append("Report,LedgerFlow Income & Expense Statement\n")
        sb.append("Period,${csvCell(bundle.range.label)}\n")
        sb.append("Currency,$currency\n")
        sb.append("Income Total,${fmtDecimal(bundle.periodInflowMinor, currency)}\n")
        sb.append("Expense Total,${fmtDecimal(bundle.periodOutflowMinor, currency)}\n")
        sb.append("Net Surplus,${fmtDecimal(bundle.periodNetMinor, currency)}\n\n")
        sb.append("Date,Type,Merchant,Category,Account,Amount ($currency),Original Currency,Labels,Note\n")
        val filtered = transactions
            .filter { it.deletedAtEpochMs == null && it.occurredAtEpochMs in bundle.range.startEpochMs..bundle.range.endEpochMs }
            .sortedByDescending { it.occurredAtEpochMs }
        for (tx in filtered) {
            val baseAmt = FxConverter.convertMinor(tx.amountMinor, tx.currency, currency)
            val catNames = with(CategoryHierarchy) { tx.allAssignedCategoryIds() }
                .mapNotNull { catMap[it]?.name }
                .joinToString(" | ")
                .ifBlank { "Uncategorized" }
            val accName = accMap[tx.accountId]?.name ?: tx.accountId
            sb.append(csvCell(fmt.format(Date(tx.occurredAtEpochMs)))).append(',')
                .append(csvCell(tx.txType)).append(',')
                .append(csvCell(tx.merchant)).append(',')
                .append(csvCell(catNames)).append(',')
                .append(csvCell(accName)).append(',')
                .append(fmtDecimal(baseAmt, currency)).append(',')
                .append(csvCell(tx.currency)).append(',')
                .append(csvCell(tx.labelsCsv)).append(',')
                .append(csvCell(tx.note)).append('\n')
        }
        return sb.toString()
    }

    fun buildFinancialAssetsReportCsv(
        bundle: AnalyticsBundle,
        holdings: List<HoldingEntity>,
        currency: String,
    ): String {
        val sb = StringBuilder()
        sb.append("Report,LedgerFlow Financial Assets & Net Worth Statement\n")
        sb.append("Currency,$currency\n")
        sb.append("Liquid Cash & Bank Assets,${fmtDecimal(bundle.financialHealth.liquidAssetsMinor, currency)}\n")
        sb.append("Investments Portfolio,${fmtDecimal(bundle.financialHealth.investmentValueMinor, currency)}\n")
        sb.append("Total Gross Assets,${fmtDecimal(bundle.financialHealth.totalGrossAssetsMinor, currency)}\n")
        sb.append("Total Liabilities (Cards & Loans),${fmtDecimal(bundle.financialHealth.totalLiabilitiesMinor, currency)}\n")
        sb.append("Total Net Worth,${fmtDecimal(bundle.financialHealth.netWorthMinor, currency)}\n\n")
        sb.append("Section,Item Name,Type / Class,Currency,Native Balance,Value ($currency)\n")
        for (acc in bundle.accountBalances) {
            sb.append("Account,").append(csvCell(acc.name)).append(',')
                .append(csvCell(acc.typeLabel)).append(',')
                .append(csvCell(acc.currency)).append(',')
                .append(fmtDecimal(acc.nativeBalanceMinor, acc.currency)).append(',')
                .append(fmtDecimal(acc.baseBalanceMinor, currency)).append('\n')
        }
        for (h in holdings.filter { it.deletedAtEpochMs == null && it.quantity > 0.0 }) {
            val valNative = AnalyticsCalculator.safeMultiplyMinor(h.quantity, h.currentPriceMinor)
            val valBase = FxConverter.convertMinor(valNative, h.currency, currency)
            sb.append("Holding,").append(csvCell("${h.symbol} (${h.name})")).append(',')
                .append(csvCell(h.assetClass)).append(',')
                .append(csvCell(h.currency)).append(',')
                .append(fmtDecimal(valNative, h.currency)).append(',')
                .append(fmtDecimal(valBase, currency)).append('\n')
        }
        return sb.toString()
    }
}
