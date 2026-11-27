package com.ledgerflow.app

import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.BudgetEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.DebtEntity
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.data.local.MagicRuleEntity
import com.ledgerflow.app.data.local.PlannedPaymentEntity
import com.ledgerflow.app.data.local.SavingsGoalEntity
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.local.ShoppingItemEntity
import com.ledgerflow.app.data.local.ShoppingListEntity
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.AccountMatcher
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.AnalyticsEngine
import com.ledgerflow.app.domain.AnalyticsRangePreset
import com.ledgerflow.app.domain.CalculatorEngine
import com.ledgerflow.app.domain.CategoryHierarchy
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.NotificationParser
import com.ledgerflow.app.domain.ReportExporter
import com.ledgerflow.app.domain.SupportedPaymentApps
import com.ledgerflow.app.security.VaultCryptoEngine
import com.ledgerflow.app.security.VaultKeyProvider
import com.ledgerflow.app.security.VaultSessionState
import com.ledgerflow.app.ui.theme.ThemeModes
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
            CategoryEntity("cat_d", "Dining", "EXPENSE", "cafe", "#D97706", null, novStart, novStart),
            CategoryEntity("cat_g", "Groceries", "EXPENSE", "shopping_cart", "#059669", null, novStart, novStart),
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

        val dark = decoded.copy(themeMode = ThemeModes.FLOW_DARK)
        val encoded = json.encodeToString(VaultSessionState.serializer(), dark)
        val decodedDark = json.decodeFromString(VaultSessionState.serializer(), encoded)
        assertEquals(ThemeModes.FLOW_DARK, decodedDark.themeMode)

        assertEquals(ThemeModes.FLOW_DARK, ThemeModes.normalize("DARK"))
        assertEquals(ThemeModes.PREMIUM_DARK, ThemeModes.normalize("BLACK"))
        assertEquals("Premium Black", ThemeModes.displayLabel(ThemeModes.PREMIUM_DARK))
        assertEquals("Flow Dark", ThemeModes.displayLabel(ThemeModes.FLOW_DARK))
    }

    @Test
    fun analyticsEngineAndReports_computeBalancesCashFlowUtilizationDtiAndCsv() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 20, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMs = nowCal.timeInMillis
        val nov5 = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 5, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val nov12 = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 12, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val oct12PrevPeriod = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 12, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val nov25Scheduled = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 25, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val categories = SeedData.starterCategories(nowMs)
        val accounts = listOf(
            AccountEntity("a_bank", "Salary Bank", accountType = "BANK", currency = "INR", balanceMinor = 500_000L, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
            AccountEntity("a_card", "SBI Cashback", accountType = "CREDIT_CARD", currency = "INR", balanceMinor = -100_000L, lastFour = "4321", creditLimitMinor = 500_000L, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
            AccountEntity("a_loan", "Bike Loan", accountType = "LOAN", currency = "INR", balanceMinor = -200_000L, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
        )
        val transactions = listOf(
            TransactionEntity("t_sal", "a_bank", categoryId = SeedData.CAT_SALARY, txType = "INCOME", amountMinor = 1_000_000L, currency = "INR", merchant = "Employer", occurredAtEpochMs = nov5, createdAtEpochMs = nov5, updatedAtEpochMs = nov5),
            // Multi-category expense split across Petrol/Diesel (Fuel parent) and Entertainment -> equals 100_000 total (50k Need, 50k Want)
            TransactionEntity("t_multi", "a_card", categoryId = SeedData.SUB_PETROL_DIESEL, categoryIdsCsv = "${SeedData.SUB_PETROL_DIESEL},${SeedData.CAT_ENTERTAINMENT}", txType = "EXPENSE", amountMinor = -100_000L, currency = "INR", merchant = "Shell & PVR", labelsCsv = "weekend", occurredAtEpochMs = nov12, createdAtEpochMs = nov12, updatedAtEpochMs = nov12),
            // Repayment transfer from Bank to Loan (counted under debt repayment / savings)
            TransactionEntity("t_repay", "a_bank", transferAccountId = "a_loan", categoryId = null, txType = "TRANSFER", amountMinor = -50_000L, currency = "INR", merchant = "Loan EMI", occurredAtEpochMs = nov12, createdAtEpochMs = nov12, updatedAtEpochMs = nov12),
            // Prior period spend for period-to-period delta
            TransactionEntity("t_prev", "a_bank", categoryId = SeedData.CAT_DINING, txType = "EXPENSE", amountMinor = -200_000L, currency = "INR", merchant = "Old Bistro", occurredAtEpochMs = oct12PrevPeriod, createdAtEpochMs = oct12PrevPeriod, updatedAtEpochMs = oct12PrevPeriod),
        )
        val planned = listOf(
            PlannedPaymentEntity("p_emi", "a_bank", SeedData.CAT_EMI_DEBT, "Home EMI", 150_000L, "INR", "EXPENSE", "MONTHLY", nov25Scheduled, false, nowMs, nowMs),
        )
        val holdings = listOf(
            HoldingEntity("h_nifty", "NIFTY", "Nifty 50 ETF", "STOCKS", "NSE", 10.0, 20_000L, 25_000L, "INR", 0, nowMs, nowMs),
        )

        val range = AnalyticsEngine.buildDateRange(AnalyticsRangePreset.MONTH_1, nowMs = nowMs)
        val bundle = AnalyticsEngine.computeFullAnalytics(accounts, categories, transactions, planned, holdings, "INR", range, nowMs = nowMs)

        assertEquals(1_000_000L, bundle.periodInflowMinor)
        assertEquals(100_000L, bundle.periodOutflowMinor)
        assertEquals(900_000L, bundle.periodNetMinor)
        assertEquals(90, bundle.periodSavingsRatePct)

        // Multi-category split conserves total across Needs (50k) and Wants (50k) without double counting
        assertEquals(50_000L, bundle.spendingNature.needsMinor)
        assertEquals(50_000L, bundle.spendingNature.wantsMinor)
        assertEquals(50_000L, bundle.spendingNature.savingsAndDebtMinor)
        assertEquals(150_000L, bundle.spendingNature.totalClassifiedMinor)

        // Parent rollup rolls SUB_PETROL_DIESEL under Fuel & Vehicle without inflating 100k spend
        val fuelParent = bundle.spendingByParentCategory.firstOrNull { it.id == SeedData.CAT_FUEL }
        assertEquals(50_000L, fuelParent?.amountMinor)
        assertEquals(100_000L, bundle.spendingByParentCategory.sumOf { it.amountMinor })

        // Label breakdown detects #weekend
        assertEquals(100_000L, bundle.spendingByLabels.firstOrNull()?.amountMinor)

        // Credit card utilization: 100k owed / 500k limit = 20%
        assertEquals(20.0f, bundle.creditAndDebt.overallUtilizationPct ?: -1f, 0.1f)
        assertEquals(1, bundle.creditAndDebt.cards.size)
        // Monthly debt service = max(150k scheduled EMI, 50k loan repayment) = 150k / 1000k income = 15%
        assertNotNull(bundle.creditAndDebt.debtToIncomePct)
        assertEquals(15.0f, bundle.creditAndDebt.debtToIncomePct!!, 0.2f)

        // Planned timeline shows nov25Home EMI and projected net worth drops by 150k (from 200k to 50k)
        assertEquals(1, bundle.balanceForecast.timeline.size)
        assertEquals(50_000L, bundle.balanceForecast.projectedEndBalanceMinor)

        // Prior period comparison (Oct 1 - Oct 20 vs Nov 1 - Nov 20)
        assertEquals(200_000L, bundle.periodComparison.previousOutflowMinor)
        assertEquals(-50.0f, bundle.periodComparison.outflowDeltaPct ?: 0f, 0.5f)

        // Financial health includes Liquid (500k) + Holdings (250k) - Debt (300k) = 450k net worth
        assertEquals(500_000L, bundle.financialHealth.liquidAssetsMinor)
        assertEquals(250_000L, bundle.financialHealth.investmentValueMinor)
        assertEquals(450_000L, bundle.financialHealth.netWorthMinor)

        // CSV reports serialize real metrics cleanly without empty gaps
        val cfCsv = ReportExporter.buildCashFlowReportCsv(bundle, "INR")
        assertTrue(cfCsv.contains("Total Inflow,10000.00"))
        val ieCsv = ReportExporter.buildIncomeExpenseReportCsv(bundle, transactions, categories, accounts, "INR")
        assertTrue(ieCsv.contains("Shell & PVR"))
        val assetsCsv = ReportExporter.buildFinancialAssetsReportCsv(bundle, holdings, "INR")
        assertTrue(assetsCsv.contains("NIFTY"))

        // Subcategory budget rollup in FinanceMath sums child SUB_PETROL_DIESEL spend into CAT_FUEL budget
        val fuelBudget = BudgetEntity("b_fuel", SeedData.CAT_FUEL, "Fuel", 200_000L, "INR", "MONTHLY", nov5, nov5)
        val evaluatedBudgets = AnalyticsCalculator.computeBudgets(listOf(fuelBudget), transactions, "INR", nov5 - 1000L, 0.5f, categories)
        assertEquals(50_000L, evaluatedBudgets.single().spentMinor)

        // Allocation conservation test for odd minor amounts
        val splitOdd = CategoryHierarchy.allocateAcrossIds(100L, listOf("c1", "c2", "c3"))
        assertEquals(100L, splitOdd.sumOf { it.second })

        // Title-case name formatting & per-account 30-day reverse balance trendline
        assertEquals("Harsh", MoneyFormatter.toTitleCaseName("harsh"))
        assertEquals("Harsh Vardhan Tripathi", MoneyFormatter.toTitleCaseName("  harsh   VARDHAN  tripathi "))
        assertEquals("", MoneyFormatter.toTitleCaseName("   "))

        val bankAcc = accounts.first { it.id == "a_bank" }
        val bankTrend = AnalyticsEngine.computeAccountBalanceTrend(bankAcc, transactions, days = 30, nowMs = nowMs)
        assertEquals(8, bankTrend.points.size)
        assertEquals(500_000L, bankTrend.endBalanceMinor)
        // Starting net before Nov 5 +1,000,000 salary and Nov 12 -50,000 loan repayment = 500,000 - (1,000,000 - 50,000) = -450,000
        assertEquals(-450_000L, bankTrend.startBalanceMinor)
    }

    @Test
    fun debtsGoalsAndShoppingCalculators_aggregateAccurately() {
        val debts = listOf(
            DebtEntity("d1", "Rohan", "LENT", 200_000L, 120_000L, "INR", null, null, "Trip", "ACTIVE", 1L, 1L),
            DebtEntity("d2", "Asha", "BORROWED", 100_000L, 40_000L, "INR", null, null, "Tickets", "ACTIVE", 1L, 1L),
            DebtEntity("d3", "Old", "LENT", 50_000L, 0L, "INR", null, null, "", "CLOSED", 1L, 1L),
        )
        val debtSummary = AnalyticsCalculator.computeDebtSummary(debts, "INR")
        assertEquals(120_000L, debtSummary.toReceiveMinor)
        assertEquals(40_000L, debtSummary.toPayMinor)
        assertEquals(1, debtSummary.activeLentCount)
        assertEquals(1, debtSummary.activeBorrowedCount)
        assertEquals(1, debtSummary.closedCount)

        val goal = SavingsGoalEntity(
            id = "g1",
            name = "Emergency Fund",
            targetAmountMinor = 500_000L,
            savedAmountMinor = 150_000L,
            currency = "INR",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val gp = AnalyticsCalculator.computeGoalProgress(goal, "INR")
        assertEquals(30, gp.percent)
        assertEquals(350_000L, gp.remainingBaseMinor)
        assertEquals(0.30f, gp.ratio, 0.01f)

        val list = ShoppingListEntity("l1", "Weekly Groceries", SeedData.CAT_GROCERIES, 1L, 1L)
        val items = listOf(
            ShoppingItemEntity("i1", "l1", "Milk", 6_000L, checked = true, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
            ShoppingItemEntity("i2", "l1", "Bread", 4_500L, checked = true, createdAtEpochMs = 2L, updatedAtEpochMs = 2L),
            ShoppingItemEntity("i3", "l1", "Olive Oil", 50_000L, checked = false, createdAtEpochMs = 3L, updatedAtEpochMs = 3L),
        )
        val sum = AnalyticsCalculator.summarizeShoppingList(list, items)
        assertEquals(3, sum.totalItems)
        assertEquals(2, sum.checkedItems)
        assertEquals(1, sum.uncheckedItems)
        assertEquals(60_500L, sum.estimatedTotalMinor)
        assertEquals(10_500L, sum.checkedTotalMinor)
    }

    @Test
    fun supportedAppsAndPaymentNotifications_parseDebitsCreditsRefundsFailuresAndMissingMerchant() {
        // Supported vs unsupported apps
        assertTrue(SupportedPaymentApps.isSupported("com.google.android.apps.nbu.paisa.user"))
        assertTrue(SupportedPaymentApps.isSupported("com.phonepe.app"))
        assertTrue(SupportedPaymentApps.isSupported("net.one97.paytm"))
        assertTrue(SupportedPaymentApps.isSupported("in.org.npci.upiapp"))
        assertTrue(SupportedPaymentApps.isSupported("com.google.android.apps.walletnfcrel"))
        assertTrue(SupportedPaymentApps.isSupported("com.snapwork.hdfc"))
        assertFalse(SupportedPaymentApps.isSupported("com.whatsapp"))
        assertFalse(SupportedPaymentApps.isSupported("com.instagram.android"))

        // PhonePe UPI debit with ref ID & card/account last-4
        val phonePe = NotificationParser.parsePaymentNotification(
            title = "Paid ₹349.50 to Swiggy",
            text = "Sent using HDFC Bank ••4821 • UPI Ref No 432198765432",
            defaultCurrency = "INR",
            sourceApp = "PhonePe",
        )!!
        assertEquals("EXPENSE", phonePe.txType)
        assertEquals(-34_950L, phonePe.amountMinor)
        assertEquals("INR", phonePe.currency)
        assertEquals("Swiggy", phonePe.merchant)
        assertEquals("432198765432", phonePe.referenceId)
        assertEquals("4821", phonePe.accountLastFour)
        assertEquals("HDFC", phonePe.bankOrAccountHint)

        // Bank alert with trailing available balance must extract transaction amount, NOT balance
        val bankDebit = NotificationParser.parsePaymentNotification(
            title = "SBI Alert",
            text = "Rs. 1,250.00 debited from A/c **1234 on 27-11-26 to Reliance Retail. Avl Bal Rs. 25,430.00. Ref 9988776655",
            defaultCurrency = "INR",
            sourceApp = "SBI YONO",
        )!!
        assertEquals("EXPENSE", bankDebit.txType)
        assertEquals(-125_000L, bankDebit.amountMinor)
        assertEquals("Reliance Retail", bankDebit.merchant)
        assertEquals("1234", bankDebit.accountLastFour)
        assertEquals("9988776655", bankDebit.referenceId)

        // Missing merchant produces null rather than guessing
        val missingMerchant = NotificationParser.parsePaymentNotification(
            title = "BHIM",
            text = "Debited Rs 500.00 from A/c XX4821 via UPI Ref 112233445566",
            defaultCurrency = "INR",
            sourceApp = "BHIM",
        )!!
        assertEquals(-50_000L, missingMerchant.amountMinor)
        assertNull(missingMerchant.merchant)

        // Credit / Received / Refund / Reversal detection
        val received = NotificationParser.parsePaymentNotification(
            title = "Rohan Verma paid you ₹1,500",
            text = "Money received via Google Pay India",
            defaultCurrency = "INR",
            sourceApp = "Google Pay",
        )!!
        assertEquals("INCOME", received.txType)
        assertEquals(150_000L, received.amountMinor)
        assertFalse(received.isRefundOrReversal)
        assertEquals("Rohan Verma", received.merchant)

        val refund = NotificationParser.parsePaymentNotification(
            title = "Paytm Refund",
            text = "Refund of Rs 299.00 from Flipkart credited to HDFC A/c XX4821 UTR 5566778899",
            defaultCurrency = "INR",
            sourceApp = "Paytm",
        )!!
        assertEquals("INCOME", refund.txType)
        assertEquals(29_900L, refund.amountMinor)
        assertTrue(refund.isRefundOrReversal)
        assertEquals("Flipkart", refund.merchant)

        // Credit card expense must NEVER be classified as INCOME just because of the word "Credit Card"
        val creditCardSpend = NotificationParser.parsePaymentNotification(
            title = "HDFC Bank",
            text = "INR 499.00 spent on HDFC Credit Card XX7788 at Starbucks",
            defaultCurrency = "INR",
            sourceApp = "HDFC Bank",
        )!!
        assertEquals("EXPENSE", creditCardSpend.txType)
        assertEquals(-49_900L, creditCardSpend.amountMinor)

        // Failed / declined / collect request notifications must NEVER create transactions
        val failed = NotificationParser.inspectNotification(
            title = "Payment failed",
            text = "Payment of ₹500 to Zomato failed. Any debited amount will be refunded.",
            defaultCurrency = "INR",
        )
        assertEquals(NotificationParser.NotificationParseKind.FAILURE_OR_DECLINED, failed.kind)
        assertNull(failed.payment)

        val declined = NotificationParser.inspectNotification(
            title = "Card Declined",
            text = "Transaction of \$42.00 at Target declined due to insufficient funds",
            defaultCurrency = "USD",
        )
        assertEquals(NotificationParser.NotificationParseKind.FAILURE_OR_DECLINED, declined.kind)
        assertNull(declined.payment)

        val collectReq = NotificationParser.inspectNotification(
            title = "Flipkart is requesting ₹999",
            text = "Approve payment request before it expires",
            defaultCurrency = "INR",
        )
        assertEquals(NotificationParser.NotificationParseKind.FAILURE_OR_DECLINED, collectReq.kind)
        assertNull(collectReq.payment)

        // Deterministic account matching: exact vs ambiguous -> UNASSIGNED ("")
        val hdfc = AccountEntity("a1", "HDFC Salary", "HDFC Bank", "CHECKING", "INR", 100_000L, "4821", 0L, "MANUAL", "OK", null, 1L, 1L)
        val sbi1 = AccountEntity("a2", "SBI Checking", "SBI", "CHECKING", "INR", 50_000L, "1111", 0L, "MANUAL", "OK", null, 1L, 1L)
        val sbi2 = AccountEntity("a3", "SBI Card", "SBI", "CREDIT_CARD", "INR", 0L, "2222", 500_000L, "MANUAL", "OK", null, 1L, 1L)
        assertEquals("a1", AccountMatcher.matchAccountId(listOf(hdfc, sbi1, sbi2), bankHint = "HDFC", lastFour = "4821"))
        assertEquals("", AccountMatcher.matchAccountId(listOf(hdfc, sbi1, sbi2), bankHint = "SBI", lastFour = null))
        assertEquals("", AccountMatcher.matchAccountId(listOf(hdfc, sbi1, sbi2), bankHint = null, lastFour = "9999"))

        // Pending summary calculator
        val p1 = TransactionEntity("p1", "", null, null, "", "EXPENSE", -20_000L, "INR", null, null, "Cafe", "", "", "GPAY_AUTO", false, 1L, 1L, 1L, null, true, reviewStatus = TransactionEntity.STATUS_PENDING_REVIEW)
        val p2 = TransactionEntity("p2", "a1", null, null, "", "INCOME", 15_000L, "INR", null, null, "Refund", "", "", "BANK_SYNC", false, 2L, 2L, 2L, null, true, reviewStatus = TransactionEntity.STATUS_PENDING_REVIEW, isRefundOrReversal = true)
        val pSum = AnalyticsCalculator.computePendingReviewSummary(listOf(p1, p2), "INR")
        assertEquals(2, pSum.count)
        assertEquals(20_000L, pSum.totalOutflowMinor)
        assertEquals(15_000L, pSum.totalInflowMinor)
        assertEquals(35_000L, pSum.totalVolumeMinor)
        assertEquals(1, pSum.unassignedCount)
    }
}
