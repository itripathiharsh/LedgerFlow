package com.ledgerflow.app.data.local

import androidx.room.withTransaction

object SeedData {
    private const val ACC_CASH_CLEAN = "11111111-1111-4111-9000-000000000001"

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
}
