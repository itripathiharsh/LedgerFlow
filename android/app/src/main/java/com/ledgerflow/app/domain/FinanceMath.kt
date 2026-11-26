package com.ledgerflow.app.domain

import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.BudgetEntity
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.data.local.MagicRuleEntity
import com.ledgerflow.app.data.local.TransactionEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
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
    fun evaluateToMinor(expr: String): Long? {
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
        if (!value.isFinite() || value < 0.0) return null
        return (value * 100.0).roundToLong()
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
            val rawValue = (h.quantity * h.currentPriceMinor.toDouble()).roundToLong()
            val rawCost = (h.quantity * h.avgCostMinor.toDouble()).roundToLong()
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
}
