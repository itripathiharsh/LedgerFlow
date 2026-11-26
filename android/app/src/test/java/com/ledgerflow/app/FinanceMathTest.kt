package com.ledgerflow.app

import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.BudgetEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.data.local.MagicRuleEntity
import com.ledgerflow.app.data.local.PlannedPaymentEntity
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.CalculatorEngine
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.NotificationParser
import com.ledgerflow.app.security.VaultCryptoEngine
import com.ledgerflow.app.security.VaultKeyProvider
import com.ledgerflow.app.security.VaultSessionState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class FinanceMathTest {
    @Test
    fun moneyFormatter_formatsNegativesPlusZeroDecimalsAndMasking() {
        assertEquals("-$6.80", MoneyFormatter.formatMinor(-680L, "USD"))
        assertEquals("+$2,710.00", MoneyFormatter.formatMinor(271_000L, "USD", showPlus = true))
        assertEquals("¥1,542", MoneyFormatter.formatMinor(1542L, "JPY"))
        assertEquals("€••••", MoneyFormatter.formatMinor(318_000L, "EUR", masked = true))
        assertEquals(1999L, MoneyFormatter.parseDecimalToMinor("19.99"))
        assertEquals(123_450L, MoneyFormatter.parseDecimalToMinor("1,234.50"))
    }

    @Test
    fun calculatorEngine_evaluatesPrecedenceAndRejectsInvalidMath() {
        assertEquals(4850L, CalculatorEngine.evaluateToMinor("48.50"))
        assertEquals(2000L, CalculatorEngine.evaluateToMinor("10+5*2"))
        assertEquals(2500L, CalculatorEngine.evaluateToMinor("100/4"))
        assertEquals(1500L, CalculatorEngine.evaluateToMinor("15+"))
        assertNull(CalculatorEngine.evaluateToMinor("10/0"))
        assertNull(CalculatorEngine.evaluateToMinor("5-20"))
    }

    @Test
    fun fxConverter_convertsAcrossCurrenciesDeterministically() {
        val usdToEur = FxConverter.convertMinor(10_000L, "USD", "EUR")
        assertEquals(9250L, usdToEur)
        assertEquals(10_000L, FxConverter.convertMinor(10_000L, "USD", "USD"))
    }

    @Test
    fun notificationParser_extractsGoogleWalletPaymentsAndIgnoresNoise() {
        val p1 = NotificationParser.parseGooglePayNotification("Paid \$6.80 at Blue Bottle Coffee", "Checking ••4821")
        assertNotNull(p1)
        assertEquals("Blue Bottle Coffee", p1!!.merchant)
        assertEquals(-680L, p1.amountMinor)
        assertEquals("USD", p1.currency)

        val p2 = NotificationParser.parseGooglePayNotification("Google Pay", "Spent €3.20 at Metro Transit")
        assertNotNull(p2)
        assertEquals(-320L, p2!!.amountMinor)
        assertEquals("EUR", p2.currency)

        val p3 = NotificationParser.parseGooglePayNotification("Paid Rs. 240.50 at Zomato", "UPI Alert")
        assertNotNull(p3)
        assertEquals(-24050L, p3!!.amountMinor)
        assertEquals("INR", p3.currency)
        assertEquals("Zomato", p3.merchant)

        assertNull(NotificationParser.parseGooglePayNotification("Google Wallet", "Your weekly summary is ready"))
    }

    @Test
    fun magicRuleEngine_matchesPatternsAndSanitizesFtsQueries() {
        val rules = listOf(
            MagicRuleEntity("r1", "coffee|espresso", "cat_dining", "coffee", enabled = true, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
            MagicRuleEntity("r2", "disabled", "cat_other", "no", enabled = false, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
        )
        val hit = MagicRuleEngine.apply("Blue Bottle Espresso #12", "", rules)
        assertTrue(hit.matched)
        assertEquals("cat_dining", hit.categoryId)
        assertEquals("coffee", hit.label)

        val miss = MagicRuleEngine.apply("Disabled Store", "", rules)
        assertFalse(miss.matched)

        assertEquals("Trader* Joe* s* groceries*", MagicRuleEngine.sanitizeFtsQuery("Trader Joe's #groceries"))
    }

    @Test
    fun vaultCryptoEngine_encryptsDecryptsAndVerifiesPin() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val provider = object : VaultKeyProvider {
            override fun getOrCreateSecretKey(): SecretKey = key
        }
        val crypto = VaultCryptoEngine(provider)
        val secret = "token-123-offline-vault".encodeToByteArray()
        val encrypted = crypto.encryptBytes(secret)
        assertFalse(encrypted.contentEquals(secret))
        assertEquals("token-123-offline-vault", crypto.decryptBytes(encrypted).decodeToString())

        val salt = VaultCryptoEngine.generateSalt()
        val hash = VaultCryptoEngine.hashPin("2580", salt)
        val saltB64 = java.util.Base64.getEncoder().encodeToString(salt)
        val hashB64 = java.util.Base64.getEncoder().encodeToString(hash)
        assertTrue(VaultCryptoEngine.verifyPin("2580", saltB64, hashB64))
        assertFalse(VaultCryptoEngine.verifyPin("9999", saltB64, hashB64))
    }

    @Test
    fun analyticsCalculator_computesRealPortfolioGainAndZeroWhenEmpty() {
        val empty = AnalyticsCalculator.computePortfolio(emptyList(), "USD")
        assertEquals(0L, empty.totalValueMinor)
        assertEquals(0L, empty.totalCostMinor)
        assertEquals(0L, empty.gainMinor)
        assertEquals(0.0, empty.gainPercent, 0.0001)

        val holdings = listOf(
            HoldingEntity(
                id = "h1",
                symbol = "VTI",
                name = "Vanguard Total Stock",
                assetClass = "STOCKS",
                exchange = "NYSE",
                quantity = 2.0,
                avgCostMinor = 25_000L,
                currentPriceMinor = 27_500L,
                currency = "USD",
                dayChangeBps = 0,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
            ),
            HoldingEntity(
                id = "h2",
                symbol = "ETH",
                name = "Ether",
                assetClass = "CRYPTO",
                exchange = "SPOT",
                quantity = 1.0,
                avgCostMinor = 300_000L,
                currentPriceMinor = 290_000L,
                currency = "USD",
                dayChangeBps = 0,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
            ),
        )
        val summary = AnalyticsCalculator.computePortfolio(holdings, "USD")
        assertEquals(345_000L, summary.totalValueMinor)
        assertEquals(350_000L, summary.totalCostMinor)
        assertEquals(-5_000L, summary.gainMinor)
        assertEquals(55_000L, summary.stocksValueMinor)
        assertEquals(290_000L, summary.cryptoValueMinor)
        assertEquals(0L, summary.forexValueMinor)
    }

    @Test
    fun parseDecimalToMinor_rejectsOverflowInsteadOfWrappingNegativeAndHandlesJpy() {
        assertNull(MoneyFormatter.parseDecimalToMinor("999999999999999999"))
        assertEquals(1500L, MoneyFormatter.parseDecimalToMinor("1500", "JPY"))
        assertEquals(150_000L, MoneyFormatter.parseDecimalToMinor("1500", "INR"))
    }

    @Test
    fun computeBudgetsAndTotals_deduplicatesOverlappingBudgetsAndTracksUncappedOverage() {
        val monthStart = 1_700_000_000_000L
        val overallBudget = BudgetEntity(
            id = "b_all",
            categoryId = null,
            name = "Monthly Budget",
            limitMinor = 100_000L,
            currency = "INR",
            period = "MONTHLY",
            createdAtEpochMs = monthStart,
            updatedAtEpochMs = monthStart,
        )
        val diningBudget = BudgetEntity(
            id = "b_dining",
            categoryId = "cat_dining",
            name = "Dining",
            limitMinor = 20_000L,
            currency = "INR",
            period = "MONTHLY",
            createdAtEpochMs = monthStart,
            updatedAtEpochMs = monthStart,
        )
        val txs = listOf(
            TransactionEntity(
                id = "t1",
                accountId = "acc1",
                categoryId = "cat_dining",
                txType = "EXPENSE",
                amountMinor = -40_000L, // 200% of Dining budget (400 INR of 200 INR limit)
                currency = "INR",
                merchant = "Cafe",
                occurredAtEpochMs = monthStart + 10_000L,
                createdAtEpochMs = monthStart,
                updatedAtEpochMs = monthStart,
            )
        )

        val progress = AnalyticsCalculator.computeBudgets(
            budgets = listOf(overallBudget, diningBudget),
            transactions = txs,
            baseCurrency = "INR",
            monthStartEpochMs = monthStart,
            dayOfMonthFraction = 0.5f,
        )
        val dining = progress.first { it.budget.id == "b_dining" }
        assertEquals(40_000L, dining.spentMinor)
        assertEquals(-20_000L, dining.remainingMinor)
        assertEquals(2.0f, dining.ratio, 0.001f)
        assertFalse(dining.onTrack)

        // Single expense of 400 INR must NOT be double-counted to 800 INR across overall + dining budgets.
        val totalSpent = AnalyticsCalculator.computeTotalBudgetedSpent(
            budgets = listOf(overallBudget, diningBudget),
            transactions = txs,
            baseCurrency = "INR",
            monthStartEpochMs = monthStart,
        )
        assertEquals(40_000L, totalSpent)
        assertEquals(100_000L, AnalyticsCalculator.computeTotalBudgetLimit(listOf(overallBudget, diningBudget), "INR"))
    }

    @Test
    fun analyticsCalculator_buildsMonthlyHeatmapRollingMatrixAndCategoryPieSlices() {
        val nov27 = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 27, 14, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val nov25 = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 25, 10, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val novStart = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 1, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val cats = listOf(
            CategoryEntity("cat_d", "Dining", "EXPENSE", "cafe", "#D97706", novStart, novStart),
            CategoryEntity("cat_g", "Groceries", "EXPENSE", "shopping_cart", "#059669", novStart, novStart),
        )
        val txs = listOf(
            TransactionEntity("t1", "acc1", categoryId = "cat_d", txType = "EXPENSE", amountMinor = -100_000L, currency = "INR", merchant = "Dinner", occurredAtEpochMs = nov27, createdAtEpochMs = nov27, updatedAtEpochMs = nov27),
            TransactionEntity("t2", "acc1", categoryId = "cat_g", txType = "EXPENSE", amountMinor = -25_000L, currency = "INR", merchant = "Mart", occurredAtEpochMs = nov25, createdAtEpochMs = nov25, updatedAtEpochMs = nov25),
            TransactionEntity("t3", "acc1", categoryId = null, txType = "INCOME", amountMinor = 500_000L, currency = "INR", merchant = "Salary", occurredAtEpochMs = nov27, createdAtEpochMs = nov27, updatedAtEpochMs = nov27),
        )

        val heatmap = AnalyticsCalculator.computeMonthlySpendHeatmap(
            transactions = txs,
            baseCurrency = "INR",
            year = 2026,
            monthZeroBased = java.util.Calendar.NOVEMBER,
            nowMs = nov27,
        )
        assertEquals(30, heatmap.cells.size)
        assertEquals(6, heatmap.firstDayWeekdayOffset) // Nov 1, 2026 was Sunday -> offset 6 (Mon=0..Sun=6)
        assertEquals(2, heatmap.activeDays)
        assertEquals(125_000L, heatmap.totalMonthMinor)
        assertEquals(4, heatmap.cells[26].level) // Nov 27 peak spend -> level 4
        assertEquals(1, heatmap.cells[24].level) // Nov 25 is 25% of peak -> level 1
        assertEquals(0, heatmap.cells[0].level)

        val rolling = AnalyticsCalculator.computeRollingSpendHeatmap(txs, "INR", weeks = 12, nowMs = nov27)
        assertEquals(84, rolling.cells.size)
        assertEquals(125_000L, rolling.totalPeriodMinor)

        val slices = AnalyticsCalculator.computeCategoryBreakdown(txs, cats, "INR", novStart)
        assertEquals(2, slices.size)
        assertEquals("Dining", slices[0].name)
        assertEquals(0.8f, slices[0].ratio, 0.001f)
        assertEquals("Groceries", slices[1].name)
        assertEquals(0.2f, slices[1].ratio, 0.001f)
    }

    @Test
    fun moneyFormatterAndCalculator_sanitizeDecimalsAndProtectOverflow() {
        assertEquals("123.45", MoneyFormatter.sanitizeDecimalInput("-123.456", "USD", allowNegative = false))
        assertEquals("-123.45", MoneyFormatter.sanitizeDecimalInput("-123.456", "USD", allowNegative = true))
        assertEquals("500", MoneyFormatter.sanitizeDecimalInput("500.75", "JPY", allowNegative = false))
        assertEquals(1500L, CalculatorEngine.evaluateToMinor("1000+500", "JPY"))
        assertNull(CalculatorEngine.evaluateToMinor("9999999999999999999"))
        assertEquals(0L, AnalyticsCalculator.safeMultiplyMinor(Double.POSITIVE_INFINITY, 100L))
    }

    @Test
    fun accountTypesAndMonthlyPlannedExpenseMath_separateAssetsDebtAndCalendarScope() {
        // Opening balance normalization: credit card & loan store negative owed debt; debit card & cash stay assets
        assertEquals(-150_000L, AccountTypeCatalog.normalizeOpeningBalanceMinor("CREDIT_CARD", 150_000L))
        assertEquals(-500_000L, AccountTypeCatalog.normalizeOpeningBalanceMinor("LOAN", 500_000L))
        assertEquals(200_000L, AccountTypeCatalog.normalizeOpeningBalanceMinor("DEBIT_CARD", 200_000L))
        assertEquals("CARD", AccountTypeCatalog.toSupabaseSafeType("CREDIT_CARD"))
        assertEquals("CARD", AccountTypeCatalog.toSupabaseSafeType("DEBIT_CARD"))
        assertEquals("SHARED", AccountTypeCatalog.toSupabaseSafeType("LOAN"))

        val accs = listOf(
            AccountEntity("a1", "Debit Card", accountType = "DEBIT_CARD", currency = "INR", balanceMinor = 800_000L, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
            AccountEntity("a2", "Credit Card", accountType = "CREDIT_CARD", currency = "INR", balanceMinor = -150_000L, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
            AccountEntity("a3", "Car Loan", accountType = "LOAN", currency = "INR", balanceMinor = -450_000L, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
        )
        val acctSummary = AnalyticsCalculator.computeAccountSummary(accs, "INR")
        assertEquals(800_000L, acctSummary.assetsMinor)
        assertEquals(600_000L, acctSummary.liabilitiesMinor)
        assertEquals(200_000L, acctSummary.netWorthMinor)

        val novStart = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 1, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val novEnd = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 30, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        val nov24 = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 24, 9, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val nov16 = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 16, 9, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dec15NextMonth = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.DECEMBER, 15, 9, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val augNextYear = java.util.Calendar.getInstance().apply {
            set(2027, java.util.Calendar.AUGUST, 10, 9, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val planned = listOf(
            // Weekly starting Nov 16 -> 3 occurrences remaining in Nov (16, 23, 30) * 1000 = 3000
            PlannedPaymentEntity("p_wk", "a1", null, "Transit", 1_000L, "INR", "EXPENSE", "WEEKLY", nov16, false, 1L, 1L),
            // Monthly already paid (due Dec 15) -> 0 this month
            PlannedPaymentEntity("p_paid", "a1", null, "Rent", 50_000L, "INR", "EXPENSE", "MONTHLY", dec15NextMonth, false, 1L, 1L),
            // Yearly due Aug next year -> 0 this month
            PlannedPaymentEntity("p_yr", "a1", null, "Insurance", 200_000L, "INR", "EXPENSE", "YEARLY", augNextYear, false, 1L, 1L),
            // Scheduled income due Nov 24 -> never subtracted as an expense bill
            PlannedPaymentEntity("p_inc", "a1", null, "Salary", 100_000L, "INR", "INCOME", "MONTHLY", nov24, false, 1L, 1L),
        )
        assertEquals(
            3_000L,
            AnalyticsCalculator.computeMonthlyPlannedExpenseMinor(planned, "INR", novStart, novEnd),
        )
    }

    @Test
    fun vaultSessionState_themeModeDefaultsSystemAndRoundTrips() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacyJson = """{"baseCurrency":"INR","onboardingCompleted":true}"""
        val decoded = json.decodeFromString(VaultSessionState.serializer(), legacyJson)
        assertEquals("SYSTEM", decoded.themeMode)

        val dark = decoded.copy(themeMode = "DARK")
        val encoded = json.encodeToString(VaultSessionState.serializer(), dark)
        val decodedDark = json.decodeFromString(VaultSessionState.serializer(), encoded)
        assertEquals("DARK", decodedDark.themeMode)
    }
}
