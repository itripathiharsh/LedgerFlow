package com.ledgerflow.app.domain

import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.BudgetEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.data.local.MagicRuleEntity
import com.ledgerflow.app.data.local.PlannedPaymentEntity
import com.ledgerflow.app.data.local.TransactionEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object MoneyFormatter {
    private val symbols = DecimalFormatSymbols(Locale.US)

    fun currencySymbol(code: String): String = when (code.uppercase(Locale.US)) {
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "INR" -> "₹"
        "JPY" -> "¥"
        "CAD" -> "CA$"
        "AUD" -> "A$"
        "CHF" -> "CHF "
        "SGD" -> "S$"
        else -> "${code.uppercase(Locale.US)} "
    }

    fun formatMinor(
        amountMinor: Long,
        currency: String,
        showPlus: Boolean = false,
        masked: Boolean = false,
    ): String {
        if (masked) return "${currencySymbol(currency)}••••"
        val decimals = if (currency.equals("JPY", ignoreCase = true)) 0 else 2
        val divisor = if (decimals == 0) 1.0 else 100.0
        val sign = when {
            amountMinor < 0 -> "-"
            showPlus && amountMinor > 0 -> "+"
            else -> ""
        }
        val pattern = if (decimals == 0) "#,##0" else "#,##0.00"
        val formatted = DecimalFormat(pattern, symbols).format(abs(amountMinor) / divisor)
        return "$sign${currencySymbol(currency)}$formatted"
    }

    private const val maxSafeMinor = 1_000_000_000_000_00L

    fun parseDecimalToMinor(input: String, currency: String = "USD"): Long? {
        val clean = input.trim().replace(",", "").replace(" ", "")
        if (clean.isEmpty()) return null
        val bd = clean.toBigDecimalOrNull() ?: return null
        val scaleFactor = if (currency.equals("JPY", ignoreCase = true)) BigDecimal.ONE else BigDecimal(100)
        return runCatching {
            val scaled = bd.multiply(scaleFactor).setScale(0, RoundingMode.HALF_UP).longValueExact()
            if (abs(scaled) > maxSafeMinor) null else scaled
        }.getOrNull()
    }

    fun sanitizeDecimalInput(
        raw: String,
        currency: String = "USD",
        allowNegative: Boolean = false,
        maxDecimalsOverride: Int? = null,
    ): String {
        val maxDecimals = maxDecimalsOverride ?: if (currency.equals("JPY", ignoreCase = true)) 0 else 2
        val sb = StringBuilder()
        var seenDot = false
        var decimals = 0
        for ((idx, ch) in raw.withIndex()) {
            when {
                ch == '-' && allowNegative && idx == 0 && sb.isEmpty() -> sb.append('-')
                ch.isDigit() -> {
                    if (seenDot && maxDecimals == 0) continue
                    if (!seenDot || decimals < maxDecimals) {
                        sb.append(ch)
                        if (seenDot) decimals++
                    }
                }
                ch == '.' && !seenDot -> {
                    if (maxDecimals == 0) {
                        seenDot = true
                    } else {
                        if (sb.isEmpty() || sb.toString() == "-") sb.append('0')
                        sb.append('.')
                        seenDot = true
                    }
                }
            }
        }
        return sb.toString()
    }
}

object FxConverter {
    private val defaultUsdRates = linkedMapOf(
        "USD" to 1.0,
        "EUR" to 0.925,
        "GBP" to 0.79,
        "INR" to 84.4,
        "JPY" to 154.2,
        "CAD" to 1.4,
        "AUD" to 1.54,
        "CHF" to 0.88,
        "CZK" to 23.4,
        "PLN" to 4.1,
        "SGD" to 1.34,
        "AED" to 3.6725,
    )

    @Volatile
    private var usdRates: Map<String, Double> = defaultUsdRates

    @Volatile
    var lastRatesSource: String = "Offline baseline"
        private set

    fun supportedCurrencies(): List<String> = defaultUsdRates.keys.toList()

    fun updateRatesFromUsdBase(rates: Map<String, Double>, sourceLabel: String? = null) {
        if (rates.isEmpty()) return
        val merged = defaultUsdRates.toMutableMap()
        rates.forEach { (k, v) ->
            if (v > 0.0) merged[k.uppercase(Locale.US)] = v
        }
        usdRates = merged
        if (!sourceLabel.isNullOrBlank()) {
            lastRatesSource = sourceLabel
        }
    }

    internal fun resetToBaselineForTests() {
        usdRates = defaultUsdRates
        lastRatesSource = "Offline baseline"
    }

    fun convertMinor(amountMinor: Long, fromCurrency: String, toCurrency: String): Long {
        val from = fromCurrency.uppercase(Locale.US)
        val to = toCurrency.uppercase(Locale.US)
        if (from == to) return amountMinor
        val fromRate = usdRates[from] ?: 1.0
        val toRate = usdRates[to] ?: 1.0
        val ratio = toRate / fromRate
        return BigDecimal.valueOf(amountMinor)
            .multiply(BigDecimal.valueOf(ratio))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }
}

object CalculatorEngine {
    /**
     * Evaluates basic left-to-right / precedence arithmetic expressions cleanly for fast mobile entry.
     * Supports +, -, *, / (plus unicode × and ÷) and decimal values. Returns minor cents on success.
     */
    fun evaluateToMinor(expr: String, currency: String = "USD"): Long? {
        val clean = expr
            .replace("×", "*")
            .replace("÷", "/")
            .replace("−", "-")
            .replace(" ", "")
            .trim()
        if (clean.isEmpty()) return null
        val trimmed = clean.trimEnd('+', '-', '*', '/')
        if (trimmed.isEmpty()) return null
        val value = evaluateExpression(trimmed) ?: return null
        if (!value.isFinite() || value < 0.0 || value > 10_000_000_000_000.0) return null
        val scaleFactor = if (currency.equals("JPY", ignoreCase = true)) 1.0 else 100.0
        val minor = (value * scaleFactor).roundToLong()
        return if (minor < 0L || minor > 1_000_000_000_000_00L) null else minor
    }

    private fun evaluateExpression(input: String): Double? {
        val tokens = mutableListOf<String>()
        val numBuf = StringBuilder()
        for (i in input.indices) {
            val ch = input[i]
            if (ch.isDigit() || ch == '.') {
                numBuf.append(ch)
            } else if (ch in listOf('+', '-', '*', '/')) {
                if (numBuf.isEmpty()) {
                    if (ch == '-' && tokens.isEmpty()) {
                        numBuf.append(ch)
                        continue
                    }
                    return null
                }
                tokens.add(numBuf.toString())
                numBuf.clear()
                tokens.add(ch.toString())
            } else {
                return null
            }
        }
        if (numBuf.isNotEmpty()) tokens.add(numBuf.toString())
        if (tokens.isEmpty() || tokens.size % 2 == 0) return null

        val numbers = mutableListOf<Double>()
        val ops = mutableListOf<String>()
        tokens.first().toDoubleOrNull()?.let { numbers.add(it) } ?: return null

        var i = 1
        while (i < tokens.size) {
            val op = tokens[i]
            val rhs = tokens[i + 1].toDoubleOrNull() ?: return null
            if (op == "*" || op == "/") {
                val lhs = numbers.removeAt(numbers.lastIndex)
                val res = if (op == "*") lhs * rhs else {
                    if (abs(rhs) < 1e-12) return null
                    lhs / rhs
                }
                numbers.add(res)
            } else {
                ops.add(op)
                numbers.add(rhs)
            }
            i += 2
        }

        var total = numbers.first()
        for (j in ops.indices) {
            total = if (ops[j] == "+") total + numbers[j + 1] else total - numbers[j + 1]
        }
        return total
    }
}

object MagicRuleEngine {
    data class MatchResult(
        val categoryId: String?,
        val label: String?,
        val matched: Boolean,
    )

    fun apply(
        merchant: String,
        note: String,
        rules: List<MagicRuleEntity>,
    ): MatchResult {
        val haystack = "$merchant $note".lowercase(Locale.US)
        for (rule in rules) {
            if (!rule.enabled || rule.deletedAtEpochMs != null) continue
            val patterns = rule.matchPattern.split("|", ",").map { it.trim().lowercase(Locale.US) }.filter { it.isNotEmpty() }
            if (patterns.any { haystack.contains(it) }) {
                return MatchResult(
                    categoryId = rule.targetCategoryId,
                    label = rule.targetLabel.ifBlank { null },
                    matched = true,
                )
            }
        }
        return MatchResult(categoryId = null, label = null, matched = false)
    }

    fun sanitizeFtsQuery(userInput: String): String {
        val terms = userInput
            .trim()
            .split(Regex("[^\\p{Alnum}_]+"))
            .filter { it.isNotEmpty() }
        return terms.joinToString(" ") { "$it*" }
    }
}

object NotificationParser {
    private val paidPattern = Regex(
        """(?i)(?:paid|spent|charged|purchase(?:d)?|sent|debited)\s+(?:by\s+|for\s+|of\s+)?(Rs\.?|RS\.?|[€$£₹]|USD|EUR|GBP|INR)?\s*([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)"""
    )
    private val amountPattern = Regex("""(Rs\.?|RS\.?|[€$£₹]|USD|EUR|GBP|INR)\s*([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val merchantAtPattern = Regex(
        """(?i)\b(?:at|to)\s+([A-Za-z0-9][A-Za-z0-9 &'.\-]{1,40}?)(?=\s+(?:via|with|on|from|using|checking|card)\b|\s*[•|(\[]|$)"""
    )

    data class ParsedPayment(
        val merchant: String,
        val amountMinor: Long,
        val currency: String,
    )

    fun parseGooglePayNotification(title: String, text: String, defaultCurrency: String = "USD"): ParsedPayment? {
        val cleanTitle = title.trim()
        val cleanText = text.trim()
        val combined = "$cleanTitle $cleanText".trim()
        if (combined.isBlank()) return null

        val match = paidPattern.find(combined) ?: amountPattern.find(combined) ?: return null
        val symbol = match.groupValues[1].trim().uppercase(Locale.US)
        val rawNumber = match.groupValues[2].replace(",", "")
        val num = rawNumber.toDoubleOrNull() ?: return null
        if (num <= 0.0) return null

        val currency = when (symbol) {
            "$" -> "USD"
            "€" -> "EUR"
            "£" -> "GBP"
            "₹", "RS", "RS." -> "INR"
            "USD", "EUR", "GBP", "INR" -> symbol
            else -> defaultCurrency
        }

        val merchant = (
            merchantAtPattern.find(cleanTitle)
                ?: merchantAtPattern.find(cleanText)
            )?.groupValues?.getOrNull(1)?.trim()
            ?: cleanTitle.takeIf { it.isNotBlank() && !it.contains("Google", ignoreCase = true) }
            ?: "Card / Wallet Payment"

        return ParsedPayment(
            merchant = merchant.take(64),
            amountMinor = -(num * 100.0).roundToLong(),
            currency = currency,
        )
    }
}

object AnalyticsCalculator {
    data class DashboardMetrics(
        val netWorthMinor: Long,
        val inflowMinor: Long,
        val outflowMinor: Long,
        val netSavingsMinor: Long,
        val savingsRatePercent: Int,
        val weeklyInflow: List<Long>,
        val weeklyOutflow: List<Long>,
    )

    data class BudgetProgress(
        val budget: BudgetEntity,
        val spentMinor: Long,
        val remainingMinor: Long,
        val ratio: Float,
        val onTrack: Boolean,
    )

    data class PortfolioSummary(
        val totalValueMinor: Long,
        val totalCostMinor: Long,
        val gainMinor: Long,
        val gainPercent: Double,
        val stocksValueMinor: Long,
        val cryptoValueMinor: Long,
        val forexValueMinor: Long,
    )

    data class CategorySpendSlice(
        val categoryId: String?,
        val name: String,
        val colorHex: String,
        val amountMinor: Long,
        val txCount: Int,
        val ratio: Float,
    )

    data class DailySpendCell(
        val dayStartMs: Long,
        val dayOfMonth: Int,
        val label: String,
        val totalMinor: Long,
        val txCount: Int,
        val level: Int,
        val isFuture: Boolean = false,
    )

    data class MonthlyHeatmapData(
        val monthLabel: String,
        val firstDayWeekdayOffset: Int, // 0 = Mon .. 6 = Sun
        val cells: List<DailySpendCell>,
        val activeDays: Int,
        val maxDailyMinor: Long,
        val totalMonthMinor: Long,
        val todayDayOfMonth: Int,
    )

    data class RollingHeatmapData(
        val weekCount: Int,
        val cells: List<DailySpendCell>, // Always weekCount * 7 ordered week-by-week (Mon..Sun)
        val activeDays: Int,
        val maxDailyMinor: Long,
        val totalPeriodMinor: Long,
    )

    fun computeDashboard(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        baseCurrency: String,
        monthStartEpochMs: Long,
    ): DashboardMetrics {
        val netWorthMinor = accounts
            .filter { it.deletedAtEpochMs == null }
            .sumOf { FxConverter.convertMinor(it.balanceMinor, it.currency, baseCurrency) }

        val monthTx = transactions.filter { it.deletedAtEpochMs == null && it.occurredAtEpochMs >= monthStartEpochMs }
        var inflow = 0L
        var outflow = 0L
        val weekIn = LongArray(4)
        val weekOut = LongArray(4)

        for (tx in monthTx) {
            val converted = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
            val daysOffset = ((tx.occurredAtEpochMs - monthStartEpochMs) / 86_400_000L).toInt().coerceIn(0, 27)
            val weekIdx = (daysOffset / 7).coerceIn(0, 3)
            when (tx.txType) {
                "INCOME" -> {
                    inflow += converted
                    weekIn[weekIdx] += converted
                }
                "EXPENSE" -> {
                    outflow += converted
                    weekOut[weekIdx] += converted
                }
            }
        }

        val net = inflow - outflow
        val rate = if (inflow > 0) ((net.toDouble() / inflow.toDouble()) * 100.0).roundToLong().toInt().coerceIn(-999, 100) else 0
        return DashboardMetrics(
            netWorthMinor = netWorthMinor,
            inflowMinor = inflow,
            outflowMinor = outflow,
            netSavingsMinor = net,
            savingsRatePercent = rate,
            weeklyInflow = weekIn.toList(),
            weeklyOutflow = weekOut.toList(),
        )
    }

    fun computeBudgets(
        budgets: List<BudgetEntity>,
        transactions: List<TransactionEntity>,
        baseCurrency: String,
        monthStartEpochMs: Long,
        dayOfMonthFraction: Float,
    ): List<BudgetProgress> {
        val monthExpenses = transactions.filter {
            it.deletedAtEpochMs == null && it.txType == "EXPENSE" && it.occurredAtEpochMs >= monthStartEpochMs
        }
        return budgets.filter { it.deletedAtEpochMs == null }.map { budget ->
            val spent = monthExpenses
                .filter { budget.categoryId == null || it.categoryId == budget.categoryId }
                .sumOf { abs(FxConverter.convertMinor(it.amountMinor, it.currency, budget.currency)) }
            val limit = budget.limitMinor.coerceAtLeast(1L)
            val ratio = (spent.toFloat() / limit.toFloat()).coerceAtLeast(0f)
            val convertedSpent = FxConverter.convertMinor(spent, budget.currency, baseCurrency)
            val convertedRemaining = FxConverter.convertMinor(budget.limitMinor - spent, budget.currency, baseCurrency)
            BudgetProgress(
                budget = budget,
                spentMinor = convertedSpent,
                remainingMinor = convertedRemaining,
                ratio = ratio,
                onTrack = ratio <= 1.0f && ratio <= dayOfMonthFraction + 0.10f,
            )
        }
    }

    /**
     * Computes monthly expenses covered by active budgets without double-counting transactions
     * when overall (categoryId == null) and per-category budgets coexist.
     */
    fun computeTotalBudgetedSpent(
        budgets: List<BudgetEntity>,
        transactions: List<TransactionEntity>,
        baseCurrency: String,
        monthStartEpochMs: Long,
    ): Long {
        val activeBudgets = budgets.filter { it.deletedAtEpochMs == null }
        if (activeBudgets.isEmpty()) return 0L
        return transactions
            .asSequence()
            .filter { it.deletedAtEpochMs == null && it.txType == "EXPENSE" && it.occurredAtEpochMs >= monthStartEpochMs }
            .filter { tx -> activeBudgets.any { b -> b.categoryId == null || b.categoryId == tx.categoryId } }
            .sumOf { tx -> abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency)) }
    }

    fun computeTotalBudgetLimit(
        budgets: List<BudgetEntity>,
        baseCurrency: String,
    ): Long {
        val active = budgets.filter { it.deletedAtEpochMs == null }
        if (active.isEmpty()) return 0L
        val overallSum = active
            .filter { it.categoryId == null }
            .sumOf { FxConverter.convertMinor(it.limitMinor.coerceAtLeast(0L), it.currency, baseCurrency) }
        val categorySum = active
            .filter { it.categoryId != null }
            .sumOf { FxConverter.convertMinor(it.limitMinor.coerceAtLeast(0L), it.currency, baseCurrency) }
        return if (overallSum > 0L) maxOf(overallSum, categorySum) else categorySum
    }

    fun computePortfolio(holdings: List<HoldingEntity>, baseCurrency: String): PortfolioSummary {
        var total = 0L
        var cost = 0L
        var stocks = 0L
        var crypto = 0L
        var forex = 0L
        for (h in holdings.filter { it.deletedAtEpochMs == null }) {
            if (!h.quantity.isFinite() || h.quantity <= 0.0) continue
            val rawValue = safeMultiplyMinor(h.quantity, h.currentPriceMinor)
            val rawCost = safeMultiplyMinor(h.quantity, h.avgCostMinor)
            val valBase = FxConverter.convertMinor(rawValue, h.currency, baseCurrency)
            val costBase = FxConverter.convertMinor(rawCost, h.currency, baseCurrency)
            total += valBase
            cost += costBase
            when (h.assetClass) {
                "STOCKS" -> stocks += valBase
                "CRYPTO" -> crypto += valBase
                "FOREX" -> forex += valBase
            }
        }
        val gain = total - cost
        val gainPct = if (cost > 0) (gain.toDouble() / cost.toDouble()) * 100.0 else 0.0
        return PortfolioSummary(
            totalValueMinor = total,
            totalCostMinor = cost,
            gainMinor = gain,
            gainPercent = gainPct,
            stocksValueMinor = stocks,
            cryptoValueMinor = crypto,
            forexValueMinor = forex,
        )
    }

    fun safeMultiplyMinor(quantity: Double, unitPriceMinor: Long): Long {
        if (!quantity.isFinite() || quantity <= 0.0 || unitPriceMinor <= 0L) return 0L
        return runCatching {
            val res = BigDecimal.valueOf(quantity)
                .multiply(BigDecimal.valueOf(unitPriceMinor))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
            res.coerceIn(0L, 1_000_000_000_000_00L)
        }.getOrDefault(0L)
    }

    fun computeCategoryBreakdown(
        transactions: List<TransactionEntity>,
        categories: List<CategoryEntity>,
        baseCurrency: String,
        monthStartEpochMs: Long,
    ): List<CategorySpendSlice> {
        val catMap = categories.associateBy { it.id }
        val fallbackColors = listOf("#059669", "#0284C7", "#D97706", "#6366F1", "#E11D48", "#0D9488", "#64748B")
        val grouped = transactions
            .filter { it.deletedAtEpochMs == null && it.txType == "EXPENSE" && it.occurredAtEpochMs >= monthStartEpochMs }
            .groupBy { it.categoryId }

        val rawTotals = grouped.map { (catId, list) ->
            val sum = list.sumOf { abs(FxConverter.convertMinor(it.amountMinor, it.currency, baseCurrency)) }
            Triple(catId, list.size, sum)
        }.filter { it.third > 0L }.sortedByDescending { it.third }

        val grandTotal = rawTotals.sumOf { it.third }.coerceAtLeast(1L)
        return rawTotals.mapIndexed { idx, (catId, count, sum) ->
            val cat = catId?.let { catMap[it] }
            CategorySpendSlice(
                categoryId = catId,
                name = cat?.name ?: "Other",
                colorHex = cat?.colorHex?.takeIf { it.isNotBlank() } ?: fallbackColors[idx % fallbackColors.size],
                amountMinor = sum,
                txCount = count,
                ratio = (sum.toFloat() / grandTotal.toFloat()).coerceIn(0f, 1f),
            )
        }
    }

    fun computeMonthlySpendHeatmap(
        transactions: List<TransactionEntity>,
        baseCurrency: String,
        year: Int? = null,
        monthZeroBased: Int? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): MonthlyHeatmapData {
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val targetYear = year ?: nowCal.get(Calendar.YEAR)
        val targetMonth = monthZeroBased ?: nowCal.get(Calendar.MONTH)

        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, targetYear)
            set(Calendar.MONTH, targetMonth)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val monthStartMs = cal.timeInMillis
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val nextMonthCal = (cal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val monthEndMs = nextMonthCal.timeInMillis

        // Convert Calendar.DAY_OF_WEEK (Sun=1, Mon=2..Sat=7) into Mon=0..Sun=6 offset
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val firstDayOffset = (dow + 5) % 7

        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.US).format(Date(monthStartMs))
        val dayFmt = SimpleDateFormat("MMM d", Locale.US)

        val buckets = LongArray(daysInMonth)
        val counts = IntArray(daysInMonth)
        val cursor = Calendar.getInstance()

        for (tx in transactions) {
            if (tx.deletedAtEpochMs != null || tx.txType != "EXPENSE") continue
            if (tx.occurredAtEpochMs < monthStartMs || tx.occurredAtEpochMs >= monthEndMs) continue
            cursor.timeInMillis = tx.occurredAtEpochMs
            val idx = (cursor.get(Calendar.DAY_OF_MONTH) - 1).coerceIn(0, daysInMonth - 1)
            buckets[idx] += abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
            counts[idx]++
        }

        val maxDaily = buckets.maxOrNull() ?: 0L
        val totalMonth = buckets.sum()
        val activeDays = counts.count { it > 0 }
        val isCurrentMonth = nowCal.get(Calendar.YEAR) == targetYear && nowCal.get(Calendar.MONTH) == targetMonth
        val todayDom = if (isCurrentMonth) nowCal.get(Calendar.DAY_OF_MONTH) else -1

        val cells = (0 until daysInMonth).map { idx ->
            val dayNum = idx + 1
            val dayCal = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, dayNum) }
            val dayMs = dayCal.timeInMillis
            val amount = buckets[idx]
            val future = isCurrentMonth && dayNum > todayDom
            DailySpendCell(
                dayStartMs = dayMs,
                dayOfMonth = dayNum,
                label = dayFmt.format(Date(dayMs)),
                totalMinor = amount,
                txCount = counts[idx],
                level = intensityLevel(amount, maxDaily),
                isFuture = future,
            )
        }

        return MonthlyHeatmapData(
            monthLabel = monthLabel,
            firstDayWeekdayOffset = firstDayOffset,
            cells = cells,
            activeDays = activeDays,
            maxDailyMinor = maxDaily,
            totalMonthMinor = totalMonth,
            todayDayOfMonth = todayDom,
        )
    }

    fun computeRollingSpendHeatmap(
        transactions: List<TransactionEntity>,
        baseCurrency: String,
        weeks: Int = 12,
        nowMs: Long = System.currentTimeMillis(),
    ): RollingHeatmapData {
        val clampedWeeks = weeks.coerceIn(4, 26)
        val todayEnd = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val todayMidnight = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayDowOffset = (todayMidnight.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val thisWeekMonday = (todayMidnight.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, -todayDowOffset)
        }
        val gridStartCal = (thisWeekMonday.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, -(clampedWeeks - 1) * 7)
        }
        val gridStartMs = gridStartCal.timeInMillis
        val totalDays = clampedWeeks * 7
        val gridEndCal = (gridStartCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, totalDays) }
        val gridEndMs = gridEndCal.timeInMillis

        val buckets = LongArray(totalDays)
        val counts = IntArray(totalDays)

        // Group using local calendar day differences rather than raw 86,400,000 ms division to stay DST-safe.
        val dayIndexByKey = HashMap<Long, Int>(totalDays * 2)
        val dayMsArray = LongArray(totalDays)
        val dayDomArray = IntArray(totalDays)
        val dayCursor = gridStartCal.clone() as Calendar
        for (i in 0 until totalDays) {
            val key = dayKey(dayCursor)
            dayIndexByKey[key] = i
            dayMsArray[i] = dayCursor.timeInMillis
            dayDomArray[i] = dayCursor.get(Calendar.DAY_OF_MONTH)
            dayCursor.add(Calendar.DAY_OF_MONTH, 1)
        }

        val txCal = Calendar.getInstance()
        for (tx in transactions) {
            if (tx.deletedAtEpochMs != null || tx.txType != "EXPENSE") continue
            if (tx.occurredAtEpochMs < gridStartMs || tx.occurredAtEpochMs >= gridEndMs) continue
            txCal.timeInMillis = tx.occurredAtEpochMs
            val idx = dayIndexByKey[dayKey(txCal)] ?: continue
            buckets[idx] += abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency))
            counts[idx]++
        }

        val maxDaily = buckets.maxOrNull() ?: 0L
        val dayFmt = SimpleDateFormat("MMM d", Locale.US)
        val cells = (0 until totalDays).map { i ->
            val ms = dayMsArray[i]
            DailySpendCell(
                dayStartMs = ms,
                dayOfMonth = dayDomArray[i],
                label = dayFmt.format(Date(ms)),
                totalMinor = buckets[i],
                txCount = counts[i],
                level = intensityLevel(buckets[i], maxDaily),
                isFuture = ms > todayEnd,
            )
        }

        return RollingHeatmapData(
            weekCount = clampedWeeks,
            cells = cells,
            activeDays = counts.count { it > 0 },
            maxDailyMinor = maxDaily,
            totalPeriodMinor = buckets.sum(),
        )
    }

    private fun dayKey(cal: Calendar): Long =
        (cal.get(Calendar.YEAR).toLong() * 1000L) + cal.get(Calendar.DAY_OF_YEAR).toLong()

    private fun intensityLevel(amountMinor: Long, maxMinor: Long): Int {
        if (amountMinor <= 0L || maxMinor <= 0L) return 0
        val ratio = amountMinor.toDouble() / maxMinor.toDouble()
        return when {
            ratio <= 0.25 -> 1
            ratio <= 0.50 -> 2
            ratio <= 0.75 -> 3
            else -> 4
        }
    }

    data class AccountSummaryMetrics(
        val assetsMinor: Long,
        val liabilitiesMinor: Long,
        val netWorthMinor: Long,
    )

    fun computeAccountSummary(
        accounts: List<AccountEntity>,
        baseCurrency: String,
    ): AccountSummaryMetrics {
        var assets = 0L
        var liabilities = 0L
        for (acc in accounts) {
            if (acc.deletedAtEpochMs != null) continue
            val converted = FxConverter.convertMinor(acc.balanceMinor, acc.currency, baseCurrency)
            if (converted >= 0L) {
                assets += converted
            } else {
                liabilities += abs(converted)
            }
        }
        return AccountSummaryMetrics(
            assetsMinor = assets,
            liabilitiesMinor = liabilities,
            netWorthMinor = assets - liabilities,
        )
    }

    fun advanceDueDate(currentDueMs: Long, cadence: String): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = currentDueMs }
        when (cadence.uppercase(Locale.US)) {
            "DAILY" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            "WEEKLY" -> cal.add(Calendar.DAY_OF_YEAR, 7)
            "YEARLY" -> cal.add(Calendar.YEAR, 1)
            else -> cal.add(Calendar.MONTH, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Counts remaining upcoming scheduled EXPENSE bill occurrences falling inside
     * [monthStartEpochMs .. monthEndEpochMs], preventing yearly bills due months away
     * or already-paid bills (advanced into next month) from distorting Monthly Safe-to-Spend.
     */
    fun computeMonthlyPlannedExpenseMinor(
        planned: List<PlannedPaymentEntity>,
        baseCurrency: String,
        monthStartEpochMs: Long,
        monthEndEpochMs: Long,
    ): Long {
        var total = 0L
        for (p in planned) {
            if (p.deletedAtEpochMs != null || p.amountMinor <= 0L) continue
            if (!p.txType.equals("EXPENSE", ignoreCase = true)) continue
            if (p.nextDueAtEpochMs > monthEndEpochMs) continue
            val perOccurrence = abs(FxConverter.convertMinor(p.amountMinor, p.currency, baseCurrency))
            if (p.nextDueAtEpochMs < monthStartEpochMs) {
                // Overdue unpaid bill counts once against current month obligation.
                total += perOccurrence
            } else {
                var due = p.nextDueAtEpochMs
                var guard = 0
                while (due <= monthEndEpochMs && guard < 32) {
                    total += perOccurrence
                    val next = advanceDueDate(due, p.cadence)
                    if (next <= due) break
                    due = next
                    guard++
                }
            }
        }
        return total
    }
}

object AccountTypeCatalog {
    val creatableTypes: List<String> = listOf(
        "CASH",
        "DEBIT_CARD",
        "CREDIT_CARD",
        "LOAN",
        "CHECKING",
        "SAVINGS",
    )

    fun normalize(raw: String): String = when (raw.trim().uppercase(Locale.US)) {
        "CASH" -> "CASH"
        "DEBIT_CARD", "DEBIT" -> "DEBIT_CARD"
        "CREDIT_CARD", "CREDIT" -> "CREDIT_CARD"
        "LOAN", "LOAD", "MORTGAGE", "EMI" -> "LOAN"
        "CHECKING" -> "CHECKING"
        "SAVINGS" -> "SAVINGS"
        "CARD" -> "CARD"
        "INVESTMENT" -> "INVESTMENT"
        "SHARED" -> "SHARED"
        else -> "CHECKING"
    }

    fun isLiability(accountType: String): Boolean {
        val norm = normalize(accountType)
        return norm == "CREDIT_CARD" || norm == "LOAN"
    }

    fun supportsLastFour(accountType: String): Boolean {
        val norm = normalize(accountType)
        return norm == "DEBIT_CARD" || norm == "CREDIT_CARD" || norm == "CARD"
    }

    fun normalizeOpeningBalanceMinor(accountType: String, enteredMinor: Long): Long =
        when (normalize(accountType)) {
            "CREDIT_CARD", "LOAN" -> if (enteredMinor == 0L) 0L else -abs(enteredMinor)
            "CASH", "CHECKING", "SAVINGS", "DEBIT_CARD" -> abs(enteredMinor)
            else -> enteredMinor
        }

    fun displayLabel(accountType: String): String = when (normalize(accountType)) {
        "CASH" -> "Cash"
        "DEBIT_CARD" -> "Debit Card"
        "CREDIT_CARD" -> "Credit Card"
        "LOAN" -> "Loan"
        "CHECKING" -> "Checking"
        "SAVINGS" -> "Savings"
        "CARD" -> "Card"
        "INVESTMENT" -> "Investment"
        "SHARED" -> "Shared"
        else -> accountType
    }

    fun iconKey(accountType: String): String = when (normalize(accountType)) {
        "CASH" -> "payments"
        "DEBIT_CARD", "CREDIT_CARD", "CARD" -> "credit_card"
        "LOAN" -> "loan"
        else -> "bank"
    }

    fun toSupabaseSafeType(accountType: String): String = when (normalize(accountType)) {
        "CASH" -> "CASH"
        "SAVINGS" -> "SAVINGS"
        "INVESTMENT" -> "INVESTMENT"
        "DEBIT_CARD", "CREDIT_CARD", "CARD" -> "CARD"
        "LOAN", "SHARED" -> "SHARED"
        else -> "CHECKING"
    }
}
