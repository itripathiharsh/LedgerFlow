package com.ledgerflow.app.data.local

import androidx.room.withTransaction

object SeedData {
    private const val ACC_CASH_CLEAN = "11111111-1111-4111-9000-000000000001"

    // Original parent categories (IDs preserved for seamless upgrades)
    const val CAT_DINING = "22222222-2222-4222-8222-222222222201"
    const val CAT_GROCERIES = "22222222-2222-4222-8222-222222222202"
    const val CAT_TRANSPORT = "22222222-2222-4222-8222-222222222203"
    const val CAT_SUBS = "22222222-2222-4222-8222-222222222204"
    const val CAT_BILLS = "22222222-2222-4222-8222-222222222205"
    const val CAT_INCOME = "22222222-2222-4222-8222-222222222206"
    const val CAT_HEALTH = "22222222-2222-4222-8222-222222222207"
    const val CAT_GENERAL = "22222222-2222-4222-8222-222222222208"

    // Expanded daily-life categories
    const val CAT_FUEL = "22222222-2222-4222-8222-222222222209"
    const val CAT_SHOPPING = "22222222-2222-4222-8222-222222222210"
    const val CAT_ENTERTAINMENT = "22222222-2222-4222-8222-222222222211"
    const val CAT_TRAVEL = "22222222-2222-4222-8222-222222222212"
    const val CAT_EDUCATION = "22222222-2222-4222-8222-222222222213"
    const val CAT_PERSONAL_CARE = "22222222-2222-4222-8222-222222222214"
    const val CAT_EMI_DEBT = "22222222-2222-4222-8222-222222222215"
    const val CAT_GIFTS = "22222222-2222-4222-8222-222222222216"
    const val CAT_SALARY = "22222222-2222-4222-8222-222222222217"
    const val CAT_FREELANCE = "22222222-2222-4222-8222-222222222218"
    const val CAT_REFUNDS = "22222222-2222-4222-8222-222222222219"

    // Starter subcategories
    const val SUB_RESTAURANTS = "33333333-3333-4333-8333-333333330101"
    const val SUB_COFFEE = "33333333-3333-4333-8333-333333330102"
    const val SUB_FOOD_DELIVERY = "33333333-3333-4333-8333-333333330103"

    const val SUB_PRODUCE = "33333333-3333-4333-8333-333333330201"
    const val SUB_DAIRY = "33333333-3333-4333-8333-333333330202"
    const val SUB_HOUSEHOLD = "33333333-3333-4333-8333-333333330203"

    const val SUB_TRANSIT = "33333333-3333-4333-8333-333333330301"
    const val SUB_CAB_AUTO = "33333333-3333-4333-8333-333333330302"

    const val SUB_PETROL_DIESEL = "33333333-3333-4333-8333-333333330901"
    const val SUB_EV_CHARGING = "33333333-3333-4333-8333-333333330902"
    const val SUB_VEHICLE_SERVICE = "33333333-3333-4333-8333-333333330903"
    const val SUB_PARKING_TOLLS = "33333333-3333-4333-8333-333333330904"

    const val SUB_STREAMING = "33333333-3333-4333-8333-333333330401"
    const val SUB_APPS_CLOUD = "33333333-3333-4333-8333-333333330402"

    const val SUB_RENT = "33333333-3333-4333-8333-333333330501"
    const val SUB_ELECTRICITY = "33333333-3333-4333-8333-333333330502"
    const val SUB_INTERNET_MOBILE = "33333333-3333-4333-8333-333333330503"
    const val SUB_WATER_GAS = "33333333-3333-4333-8333-333333330504"

    const val SUB_PHARMACY = "33333333-3333-4333-8333-333333330701"
    const val SUB_DOCTOR = "33333333-3333-4333-8333-333333330702"
    const val SUB_FITNESS = "33333333-3333-4333-8333-333333330703"

    const val SUB_CLOTHING = "33333333-3333-4333-8333-333333331001"
    const val SUB_ELECTRONICS = "33333333-3333-4333-8333-333333331002"
    const val SUB_MOVIES = "33333333-3333-4333-8333-333333331101"
    const val SUB_GAMES = "33333333-3333-4333-8333-333333331102"
    const val SUB_SALON = "33333333-3333-4333-8333-333333331401"
    const val SUB_BOOKS_COURSES = "33333333-3333-4333-8333-333333331301"

    fun starterCategories(nowMs: Long): List<CategoryEntity> = listOf(
        // Parents
        CategoryEntity(CAT_DINING, "Dining & Coffee", "EXPENSE", "cafe", "#D97706", null, nowMs, nowMs),
        CategoryEntity(CAT_GROCERIES, "Groceries", "EXPENSE", "shopping_cart", "#059669", null, nowMs, nowMs),
        CategoryEntity(CAT_FUEL, "Fuel & Vehicle", "EXPENSE", "fuel", "#F59E0B", null, nowMs, nowMs),
        CategoryEntity(CAT_TRANSPORT, "Transport & Commute", "EXPENSE", "directions_transit", "#0284C7", null, nowMs, nowMs),
        CategoryEntity(CAT_BILLS, "Housing & Bills", "EXPENSE", "home", "#475569", null, nowMs, nowMs),
        CategoryEntity(CAT_SHOPPING, "Shopping", "EXPENSE", "shopping_bag", "#EC4899", null, nowMs, nowMs),
        CategoryEntity(CAT_SUBS, "Subscriptions", "EXPENSE", "auto_awesome", "#6366F1", null, nowMs, nowMs),
        CategoryEntity(CAT_HEALTH, "Health & Pharmacy", "EXPENSE", "medical_services", "#E11D48", null, nowMs, nowMs),
        CategoryEntity(CAT_ENTERTAINMENT, "Entertainment", "EXPENSE", "movie", "#8B5CF6", null, nowMs, nowMs),
        CategoryEntity(CAT_TRAVEL, "Travel & Stay", "EXPENSE", "flight", "#0EA5E9", null, nowMs, nowMs),
        CategoryEntity(CAT_PERSONAL_CARE, "Personal Care", "EXPENSE", "spa", "#14B8A6", null, nowMs, nowMs),
        CategoryEntity(CAT_EDUCATION, "Education", "EXPENSE", "school", "#4F46E5", null, nowMs, nowMs),
        CategoryEntity(CAT_EMI_DEBT, "EMI & Debt", "EXPENSE", "loan", "#BE123C", null, nowMs, nowMs),
        CategoryEntity(CAT_GIFTS, "Gifts & Charity", "EXPENSE", "gift", "#F43F5E", null, nowMs, nowMs),
        CategoryEntity(CAT_GENERAL, "General", "EXPENSE", "category", "#64748B", null, nowMs, nowMs),
        // Income parents
        CategoryEntity(CAT_INCOME, "Income", "INCOME", "payments", "#059669", null, nowMs, nowMs),
        CategoryEntity(CAT_SALARY, "Salary", "INCOME", "payments", "#10B981", null, nowMs, nowMs),
        CategoryEntity(CAT_FREELANCE, "Freelance & Business", "INCOME", "work", "#0D9488", null, nowMs, nowMs),
        CategoryEntity(CAT_REFUNDS, "Refunds & Cashback", "INCOME", "payments", "#0284C7", null, nowMs, nowMs),
        // Subcategories
        CategoryEntity(SUB_RESTAURANTS, "Restaurants", "EXPENSE", "cafe", "#D97706", CAT_DINING, nowMs, nowMs),
        CategoryEntity(SUB_COFFEE, "Coffee & Snacks", "EXPENSE", "cafe", "#B45309", CAT_DINING, nowMs, nowMs),
        CategoryEntity(SUB_FOOD_DELIVERY, "Food Delivery", "EXPENSE", "cafe", "#F59E0B", CAT_DINING, nowMs, nowMs),
        CategoryEntity(SUB_PRODUCE, "Vegetables & Fruits", "EXPENSE", "shopping_cart", "#059669", CAT_GROCERIES, nowMs, nowMs),
        CategoryEntity(SUB_DAIRY, "Dairy & Bakery", "EXPENSE", "shopping_cart", "#10B981", CAT_GROCERIES, nowMs, nowMs),
        CategoryEntity(SUB_HOUSEHOLD, "Household Supplies", "EXPENSE", "shopping_cart", "#047857", CAT_GROCERIES, nowMs, nowMs),
        CategoryEntity(SUB_PETROL_DIESEL, "Petrol / Diesel", "EXPENSE", "fuel", "#F59E0B", CAT_FUEL, nowMs, nowMs),
        CategoryEntity(SUB_EV_CHARGING, "EV Charging", "EXPENSE", "fuel", "#10B981", CAT_FUEL, nowMs, nowMs),
        CategoryEntity(SUB_VEHICLE_SERVICE, "Service & Repair", "EXPENSE", "fuel", "#D97706", CAT_FUEL, nowMs, nowMs),
        CategoryEntity(SUB_PARKING_TOLLS, "Parking & Tolls", "EXPENSE", "fuel", "#64748B", CAT_FUEL, nowMs, nowMs),
        CategoryEntity(SUB_TRANSIT, "Metro & Bus", "EXPENSE", "directions_transit", "#0284C7", CAT_TRANSPORT, nowMs, nowMs),
        CategoryEntity(SUB_CAB_AUTO, "Cab & Auto", "EXPENSE", "directions_transit", "#0EA5E9", CAT_TRANSPORT, nowMs, nowMs),
        CategoryEntity(SUB_RENT, "Rent & Maintenance", "EXPENSE", "home", "#475569", CAT_BILLS, nowMs, nowMs),
        CategoryEntity(SUB_ELECTRICITY, "Electricity", "EXPENSE", "home", "#F59E0B", CAT_BILLS, nowMs, nowMs),
        CategoryEntity(SUB_INTERNET_MOBILE, "Internet & Mobile", "EXPENSE", "home", "#0284C7", CAT_BILLS, nowMs, nowMs),
        CategoryEntity(SUB_WATER_GAS, "Water & Cooking Gas", "EXPENSE", "home", "#0EA5E9", CAT_BILLS, nowMs, nowMs),
        CategoryEntity(SUB_STREAMING, "Streaming & OTT", "EXPENSE", "auto_awesome", "#6366F1", CAT_SUBS, nowMs, nowMs),
        CategoryEntity(SUB_APPS_CLOUD, "Apps & Cloud", "EXPENSE", "auto_awesome", "#8B5CF6", CAT_SUBS, nowMs, nowMs),
        CategoryEntity(SUB_PHARMACY, "Medicines & Pharmacy", "EXPENSE", "medical_services", "#E11D48", CAT_HEALTH, nowMs, nowMs),
        CategoryEntity(SUB_DOCTOR, "Doctor & Lab", "EXPENSE", "medical_services", "#FB7185", CAT_HEALTH, nowMs, nowMs),
        CategoryEntity(SUB_FITNESS, "Gym & Fitness", "EXPENSE", "medical_services", "#10B981", CAT_HEALTH, nowMs, nowMs),
        CategoryEntity(SUB_CLOTHING, "Clothing & Footwear", "EXPENSE", "shopping_bag", "#EC4899", CAT_SHOPPING, nowMs, nowMs),
        CategoryEntity(SUB_ELECTRONICS, "Electronics", "EXPENSE", "shopping_bag", "#6366F1", CAT_SHOPPING, nowMs, nowMs),
        CategoryEntity(SUB_MOVIES, "Movies & Outings", "EXPENSE", "movie", "#8B5CF6", CAT_ENTERTAINMENT, nowMs, nowMs),
        CategoryEntity(SUB_GAMES, "Games & Hobbies", "EXPENSE", "movie", "#A855F7", CAT_ENTERTAINMENT, nowMs, nowMs),
        CategoryEntity(SUB_SALON, "Salon & Grooming", "EXPENSE", "spa", "#14B8A6", CAT_PERSONAL_CARE, nowMs, nowMs),
        CategoryEntity(SUB_BOOKS_COURSES, "Courses & Books", "EXPENSE", "school", "#4F46E5", CAT_EDUCATION, nowMs, nowMs),
    )

    suspend fun ensureMinimalSetup(
        db: LedgerDatabase,
        defaultCurrency: String = "USD",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val existingCatIds = db.categoryDao().getAllIds().toHashSet()
            val missingCats = starterCategories(nowMs).filter { it.id !in existingCatIds }
            if (missingCats.isNotEmpty()) {
                db.categoryDao().upsertAll(missingCats)
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
