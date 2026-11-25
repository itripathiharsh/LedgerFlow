package com.ledgerflow.app.data.local

import androidx.room.withTransaction

object SeedData {
    // Deterministic starter UUIDs so first-launch offline UX mirrors our Stitch screens immediately.
    private const val ACC_CHECKING = "11111111-1111-4111-8111-111111111101"
    private const val ACC_EUR = "11111111-1111-4111-8111-111111111102"
    private const val ACC_FIDELITY = "11111111-1111-4111-8111-111111111103"
    private const val ACC_CASH = "11111111-1111-4111-8111-111111111104"

    private const val ACC_CASH_CLEAN = "11111111-1111-4111-9000-000000000001"

    const val DEMO_STARTER_TX_ID = "33333333-3333-4333-8333-333333333301"

    const val CAT_DINING = "22222222-2222-4222-8222-222222222201"
    const val CAT_GROCERIES = "22222222-2222-4222-8222-222222222202"
    const val CAT_TRANSPORT = "22222222-2222-4222-8222-222222222203"
    const val CAT_SUBS = "22222222-2222-4222-8222-222222222204"
    const val CAT_BILLS = "22222222-2222-4222-8222-222222222205"
    const val CAT_INCOME = "22222222-2222-4222-8222-222222222206"
    const val CAT_HEALTH = "22222222-2222-4222-8222-222222222207"
    const val CAT_GENERAL = "22222222-2222-4222-8222-222222222208"

    private fun starterCategories(nowMs: Long): List<CategoryEntity> = listOf(
        CategoryEntity(CAT_DINING, "Dining & Coffee", "EXPENSE", "cafe", "#D97706", nowMs, nowMs),
        CategoryEntity(CAT_GROCERIES, "Groceries", "EXPENSE", "shopping_cart", "#059669", nowMs, nowMs),
        CategoryEntity(CAT_TRANSPORT, "Transport & Fuel", "EXPENSE", "directions_transit", "#0284C7", nowMs, nowMs),
        CategoryEntity(CAT_SUBS, "Subscriptions", "EXPENSE", "auto_awesome", "#6366F1", nowMs, nowMs),
        CategoryEntity(CAT_BILLS, "Housing & Bills", "EXPENSE", "home", "#475569", nowMs, nowMs),
        CategoryEntity(CAT_INCOME, "Income", "INCOME", "payments", "#059669", nowMs, nowMs),
        CategoryEntity(CAT_HEALTH, "Health", "EXPENSE", "medical_services", "#E11D48", nowMs, nowMs),
        CategoryEntity(CAT_GENERAL, "General", "EXPENSE", "category", "#64748B", nowMs, nowMs),
    )

    suspend fun ensureMinimalSetup(
        db: LedgerDatabase,
        defaultCurrency: String = "USD",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (db.accountDao().countAll() > 0 && db.categoryDao().getAllActive().isNotEmpty()) return

        db.withTransaction {
            if (db.categoryDao().getAllActive().isEmpty()) {
                db.categoryDao().upsertAll(starterCategories(nowMs))
            }
            if (db.accountDao().countAll() == 0) {
                db.accountDao().upsert(
                    AccountEntity(
                        id = ACC_CASH_CLEAN,
                        name = "Cash Wallet",
                        institution = "Primary Wallet",
                        accountType = "CASH",
                        currency = defaultCurrency.uppercase(),
                        balanceMinor = 0L,
                        sourceType = "MANUAL",
                        syncStatus = "OK",
                        createdAtEpochMs = nowMs,
                        updatedAtEpochMs = nowMs,
                    )
                )
            }
        }
    }

    suspend fun ensureSeededIfEmpty(db: LedgerDatabase, nowMs: Long = System.currentTimeMillis()) {
        if (db.accountDao().countAll() > 0) return

        db.withTransaction {
            if (db.accountDao().countAll() > 0) return@withTransaction
            val hour = 3_600_000L
            val day = 86_400_000L

            db.accountDao().upsertAll(
                listOf(
                    AccountEntity(
                        id = ACC_CHECKING,
                        name = "Checking ••4821",
                        institution = "Primary Bank",
                        accountType = "CHECKING",
                        currency = "USD",
                        balanceMinor = 642_010L,
                        lastFour = "4821",
                        sourceType = "OPEN_BANKING",
                        syncStatus = "OK",
                        lastSyncedAtEpochMs = nowMs - 2 * 60_000L,
                        createdAtEpochMs = nowMs - 30 * day,
                        updatedAtEpochMs = nowMs,
                    ),
                    AccountEntity(
                        id = ACC_EUR,
                        name = "EUR Travel",
                        institution = "Multi-FX Card",
                        accountType = "CARD",
                        currency = "EUR",
                        balanceMinor = 318_000L,
                        lastFour = "9012",
                        sourceType = "OPEN_BANKING",
                        syncStatus = "RECONNECT",
                        lastSyncedAtEpochMs = nowMs - 2 * day,
                        createdAtEpochMs = nowMs - 25 * day,
                        updatedAtEpochMs = nowMs,
                    ),
                    AccountEntity(
                        id = ACC_FIDELITY,
                        name = "Brokerage Cash",
                        institution = "Cash Sweep",
                        accountType = "SAVINGS",
                        currency = "USD",
                        balanceMinor = 411_000L,
                        lastFour = "1042",
                        sourceType = "MANUAL",
                        syncStatus = "OK",
                        lastSyncedAtEpochMs = nowMs - hour,
                        createdAtEpochMs = nowMs - 20 * day,
                        updatedAtEpochMs = nowMs,
                    ),
                    AccountEntity(
                        id = ACC_CASH,
                        name = "Physical Cash",
                        institution = "On-Hand",
                        accountType = "CASH",
                        currency = "USD",
                        balanceMinor = 24_000L,
                        sourceType = "MANUAL",
                        createdAtEpochMs = nowMs - 15 * day,
                        updatedAtEpochMs = nowMs,
                    ),
                )
            )

            db.categoryDao().upsertAll(starterCategories(nowMs))

            db.transactionDao().upsertAll(
                listOf(
                    TransactionEntity(
                        id = "33333333-3333-4333-8333-333333333301",
                        accountId = ACC_CHECKING,
                        categoryId = CAT_DINING,
                        txType = "EXPENSE",
                        amountMinor = -680L,
                        currency = "USD",
                        merchant = "Blue Bottle Coffee",
                        note = "Cortado & pastry",
                        labelsCsv = "coffee",
                        sourceTag = "GPAY_AUTO",
                        autoRuleApplied = true,
                        occurredAtEpochMs = nowMs - 2 * hour,
                        createdAtEpochMs = nowMs - 2 * hour,
                        updatedAtEpochMs = nowMs,
                    ),
                    TransactionEntity(
                        id = "33333333-3333-4333-8333-333333333302",
                        accountId = ACC_CHECKING,
                        categoryId = CAT_GROCERIES,
                        txType = "EXPENSE",
                        amountMinor = -8420L,
                        currency = "USD",
                        merchant = "Whole Foods Market",
                        note = "Weekly produce haul",
                        labelsCsv = "weekly,groceries",
                        sourceTag = "BANK_SYNC",
                        autoRuleApplied = true,
                        occurredAtEpochMs = nowMs - 5 * hour,
                        createdAtEpochMs = nowMs - 5 * hour,
                        updatedAtEpochMs = nowMs,
                    ),
                    TransactionEntity(
                        id = "33333333-3333-4333-8333-333333333303",
                        accountId = ACC_CHECKING,
                        categoryId = CAT_INCOME,
                        txType = "INCOME",
                        amountMinor = 271_000L,
                        currency = "USD",
                        merchant = "Payroll Deposit",
                        note = "Employer Inc Direct Deposit",
                        labelsCsv = "salary",
                        sourceTag = "BANK_SYNC",
                        autoRuleApplied = true,
                        occurredAtEpochMs = nowMs - day,
                        createdAtEpochMs = nowMs - day,
                        updatedAtEpochMs = nowMs,
                    ),
                    TransactionEntity(
                        id = "33333333-3333-4333-8333-333333333304",
                        accountId = ACC_EUR,
                        categoryId = CAT_TRANSPORT,
                        txType = "EXPENSE",
                        amountMinor = -320L,
                        currency = "EUR",
                        convertedMinor = -346L,
                        convertedCurrency = "USD",
                        merchant = "Metro Transit",
                        note = "Day pass ticket",
                        labelsCsv = "commute",
                        sourceTag = "GPAY_AUTO",
                        autoRuleApplied = true,
                        occurredAtEpochMs = nowMs - day - 3 * hour,
                        createdAtEpochMs = nowMs - day - 3 * hour,
                        updatedAtEpochMs = nowMs,
                    ),
                    TransactionEntity(
                        id = "33333333-3333-4333-8333-333333333305",
                        accountId = ACC_CHECKING,
                        categoryId = CAT_SUBS,
                        txType = "EXPENSE",
                        amountMinor = -1500L,
                        currency = "USD",
                        merchant = "Figma Pro",
                        note = "Monthly design workspace",
                        labelsCsv = "recurring",
                        sourceTag = "PLANNED",
                        autoRuleApplied = true,
                        occurredAtEpochMs = nowMs - 3 * day,
                        createdAtEpochMs = nowMs - 3 * day,
                        updatedAtEpochMs = nowMs,
                    ),
                    TransactionEntity(
                        id = "33333333-3333-4333-8333-333333333306",
                        accountId = ACC_CHECKING,
                        categoryId = CAT_GROCERIES,
                        txType = "EXPENSE",
                        amountMinor = -32_580L,
                        currency = "USD",
                        merchant = "Trader Joe's",
                        note = "Monthly pantry restock",
                        labelsCsv = "groceries",
                        sourceTag = "MANUAL",
                        occurredAtEpochMs = nowMs - 7 * day,
                        createdAtEpochMs = nowMs - 7 * day,
                        updatedAtEpochMs = nowMs,
                    ),
                    TransactionEntity(
                        id = "33333333-3333-4333-8333-333333333307",
                        accountId = ACC_CHECKING,
                        categoryId = CAT_DINING,
                        txType = "EXPENSE",
                        amountMinor = -25_820L,
                        currency = "USD",
                        merchant = "Local Bistro & Espresso",
                        note = "Dinners this month",
                        labelsCsv = "dining",
                        sourceTag = "GPAY_AUTO",
                        occurredAtEpochMs = nowMs - 10 * day,
                        createdAtEpochMs = nowMs - 10 * day,
                        updatedAtEpochMs = nowMs,
                    ),
                    TransactionEntity(
                        id = "33333333-3333-4333-8333-333333333308",
                        accountId = ACC_CHECKING,
                        categoryId = CAT_INCOME,
                        txType = "INCOME",
                        amountMinor = 271_000L,
                        currency = "USD",
                        merchant = "Payroll Deposit",
                        note = "Mid-month salary",
                        labelsCsv = "salary",
                        sourceTag = "BANK_SYNC",
                        autoRuleApplied = true,
                        occurredAtEpochMs = nowMs - 14 * day,
                        createdAtEpochMs = nowMs - 14 * day,
                        updatedAtEpochMs = nowMs,
                    ),
                )
            )

            db.budgetDao().upsertAll(
                listOf(
                    BudgetEntity("44444444-4444-4444-8444-444444444401", CAT_GROCERIES, "Groceries", 60_000L, "USD", "MONTHLY", nowMs, nowMs),
                    BudgetEntity("44444444-4444-4444-8444-444444444402", CAT_DINING, "Dining & Coffee", 30_000L, "USD", "MONTHLY", nowMs, nowMs),
                    BudgetEntity("44444444-4444-4444-8444-444444444403", CAT_TRANSPORT, "Transport & Fuel", 22_000L, "USD", "MONTHLY", nowMs, nowMs),
                )
            )

            db.plannedPaymentDao().upsertAll(
                listOf(
                    PlannedPaymentEntity("55555555-5555-4555-8555-555555555501", ACC_CHECKING, CAT_BILLS, "Apartment Rent", 165_000L, "USD", "EXPENSE", "MONTHLY", nowMs + 6 * day, true, nowMs, nowMs),
                    PlannedPaymentEntity("55555555-5555-4555-8555-555555555502", ACC_CHECKING, CAT_BILLS, "Electric Utility", 8_240L, "USD", "EXPENSE", "MONTHLY", nowMs + 3 * day, true, nowMs, nowMs),
                    PlannedPaymentEntity("55555555-5555-4555-8555-555555555503", ACC_CHECKING, CAT_SUBS, "Figma Pro", 1_500L, "USD", "EXPENSE", "MONTHLY", nowMs + 5 * day, true, nowMs, nowMs),
                )
            )

            db.holdingDao().upsertAll(
                listOf(
                    HoldingEntity("66666666-6666-4666-8666-666666666601", "VOO", "Vanguard S&P 500", "STOCKS", "NYSE", 24.5, 45_200L, 48_196L, "USD", 124, nowMs, nowMs),
                    HoldingEntity("66666666-6666-4666-8666-666666666602", "BTC", "Bitcoin", "CRYPTO", "CRYPTO", 0.042, 8_200_000L, 9_664_286L, "USD", 380, nowMs, nowMs),
                    HoldingEntity("66666666-6666-4666-8666-666666666603", "EUR", "EUR/USD Reserve", "FOREX", "FOREX", 2390.0, 100L, 100L, "EUR", 18, nowMs, nowMs),
                )
            )

            db.magicRuleDao().upsertAll(
                listOf(
                    MagicRuleEntity("77777777-7777-4777-8777-777777777701", "coffee|starbucks|blue bottle", CAT_DINING, "coffee", true, nowMs, nowMs),
                    MagicRuleEntity("77777777-7777-4777-8777-777777777702", "whole foods|trader joe|grocery", CAT_GROCERIES, "weekly", true, nowMs, nowMs),
                    MagicRuleEntity("77777777-7777-4777-8777-777777777703", "metro|uber|shell|chevron", CAT_TRANSPORT, "commute", true, nowMs, nowMs),
                    MagicRuleEntity("77777777-7777-4777-8777-777777777704", "payroll|salary", CAT_INCOME, "salary", true, nowMs, nowMs),
                )
            )
        }
    }
}
