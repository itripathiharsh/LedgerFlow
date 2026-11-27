package com.ledgerflow.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.BudgetEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.DebtEntity
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.MagicRuleEntity
import com.ledgerflow.app.data.local.PlannedPaymentEntity
import com.ledgerflow.app.data.local.SavingsGoalEntity
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.local.ShoppingItemEntity
import com.ledgerflow.app.data.local.ShoppingListEntity
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.MagicRuleEngine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerDatabaseRobolectricTest {
    private lateinit var context: Context
    private lateinit var db: LedgerDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun minimalSetup_isIdempotentAndStartsCleanAtZeroBalance() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "USD", nowMs = 1_700_000_000_000L)
        val accounts = db.accountDao().getAllActive()
        assertEquals(1, accounts.size)
        assertEquals("Cash Wallet", accounts.first().name)
        assertEquals(0L, accounts.first().balanceMinor)
        assertEquals(SeedData.starterCategories(0L).size, db.categoryDao().getAllActive().size)
        assertEquals(0, db.transactionDao().getAllActive().size)
        assertEquals(0, db.budgetDao().getAllActive().size)
        assertEquals(0, db.plannedPaymentDao().getAllActive().size)
        assertEquals(0, db.holdingDao().getAllActive().size)
        assertEquals(0, db.magicRuleDao().getAllActive().size)

        SeedData.ensureMinimalSetup(db, defaultCurrency = "EUR", nowMs = 1_700_000_005_000L)
        assertEquals(1, db.accountDao().countAll())
    }

    @Test
    fun atomicRecordAndSoftDelete_updatesBalancesAndSupportsFts4Search() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "USD", nowMs = 1_700_000_000_000L)
        val wallet = db.accountDao().getAllActive().first()
        db.accountDao().upsert(wallet.copy(balanceMinor = 10_000L))

        val txId = UUID.randomUUID().toString()
        val tx = TransactionEntity(
            id = txId,
            accountId = wallet.id,
            categoryId = SeedData.CAT_DINING,
            txType = "EXPENSE",
            amountMinor = -1250L,
            currency = wallet.currency,
            merchant = "Artisan Espresso Bar",
            note = "Morning flat white #coffee",
            labelsCsv = "coffee",
            sourceTag = "MANUAL",
            occurredAtEpochMs = 1_700_000_001_000L,
            createdAtEpochMs = 1_700_000_001_000L,
            updatedAtEpochMs = 1_700_000_001_000L,
            pendingSync = true,
        )
        db.recordTransactionAtomic(tx)

        val afterDebit = db.accountDao().getById(wallet.id)!!
        assertEquals(10_000L - 1250L, afterDebit.balanceMinor)

        val ftsQuery = MagicRuleEngine.sanitizeFtsQuery("Espresso #coffee")
        val hits = db.transactionDao().searchFts(ftsQuery)
        assertTrue(hits.any { it.id == txId })

        db.softDeleteTransactionAtomic(txId, nowMs = 1_700_000_002_000L)
        val afterRevert = db.accountDao().getById(wallet.id)!!
        assertEquals(10_000L, afterRevert.balanceMinor)
        val tombstone = db.transactionDao().getById(txId)
        assertNotNull(tombstone?.deletedAtEpochMs)
    }

    @Test
    fun recordTransactionAtomic_sameIdUpdatesWithoutDoubleCounting() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "USD", nowMs = 1_700_000_000_000L)
        val wallet = db.accountDao().getAllActive().first()
        val initialBalance = wallet.balanceMinor

        val txId = UUID.randomUUID().toString()
        val initialTx = TransactionEntity(
            id = txId,
            accountId = wallet.id,
            txType = "EXPENSE",
            amountMinor = -1_000L,
            currency = wallet.currency,
            merchant = "Bakery",
            occurredAtEpochMs = 1_700_000_001_000L,
            createdAtEpochMs = 1_700_000_001_000L,
            updatedAtEpochMs = 1_700_000_001_000L,
        )
        db.recordTransactionAtomic(initialTx)
        assertEquals(initialBalance - 1_000L, db.accountDao().getById(wallet.id)!!.balanceMinor)

        // Updating existing transaction id must reverse prior -1000 and apply -2500 -> net -2500 (not -3500)
        db.recordTransactionAtomic(initialTx.copy(amountMinor = -2_500L, updatedAtEpochMs = 1_700_000_005_000L))
        assertEquals(initialBalance - 2_500L, db.accountDao().getById(wallet.id)!!.balanceMinor)
    }

    @Test
    fun plannedPayment_calendarSchedulingAndSoftDeleteGuard() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "USD", nowMs = 1_700_000_000_000L)
        val wallet = db.accountDao().getAllActive().first()
        val beforeBalance = wallet.balanceMinor

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.JANUARY, 31, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nextMonthMs = LedgerDatabase.advanceDueDateEpochMs(cal.timeInMillis, "MONTHLY")
        val nextCal = Calendar.getInstance().apply { timeInMillis = nextMonthMs }
        assertEquals(Calendar.FEBRUARY, nextCal.get(Calendar.MONTH))

        val deletedId = UUID.randomUUID().toString()
        db.plannedPaymentDao().upsert(
            PlannedPaymentEntity(
                id = deletedId,
                accountId = wallet.id,
                title = "Cancelled Gym",
                amountMinor = 5_000L,
                currency = wallet.currency,
                txType = "EXPENSE",
                cadence = "MONTHLY",
                nextDueAtEpochMs = 1_700_010_000_000L,
                createdAtEpochMs = 1_700_000_000_000L,
                updatedAtEpochMs = 1_700_000_000_000L,
                deletedAtEpochMs = 1_700_000_001_000L,
            )
        )
        db.executePlannedPaymentAtomic(deletedId, wallet.id)
        assertEquals(beforeBalance, db.accountDao().getById(wallet.id)!!.balanceMinor)
    }

    @Test
    fun resetAllDataFresh_clearsUserEntitiesAndRebuildsCleanFts4() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "USD", nowMs = 1_700_000_000_000L)
        val customAccId = UUID.randomUUID().toString()
        db.accountDao().upsert(
            AccountEntity(
                id = customAccId,
                name = "Savings",
                accountType = "SAVINGS",
                currency = "USD",
                balanceMinor = 50_000L,
                createdAtEpochMs = 1_700_000_001_000L,
                updatedAtEpochMs = 1_700_000_001_000L,
            )
        )
        db.recordTransactionAtomic(
            TransactionEntity(
                id = UUID.randomUUID().toString(),
                accountId = customAccId,
                categoryId = SeedData.CAT_DINING,
                txType = "EXPENSE",
                amountMinor = -500L,
                currency = "USD",
                merchant = "Roastery Cafe",
                occurredAtEpochMs = 1_700_000_002_000L,
                createdAtEpochMs = 1_700_000_002_000L,
                updatedAtEpochMs = 1_700_000_002_000L,
            )
        )
        db.budgetDao().upsert(
            BudgetEntity(UUID.randomUUID().toString(), SeedData.CAT_DINING, "Dining", 10_000L, "USD", "MONTHLY", 1L, 1L)
        )
        db.holdingDao().upsert(
            HoldingEntity(UUID.randomUUID().toString(), "VOO", "S&P 500", "STOCKS", "NYSE", 1.0, 40_000L, 42_000L, "USD", 0, 1L, 1L)
        )
        db.magicRuleDao().upsert(
            MagicRuleEntity(UUID.randomUUID().toString(), "roastery", SeedData.CAT_DINING, "coffee", true, 1L, 1L)
        )

        db.resetAllDataFresh(defaultCurrency = "INR", nowMs = 1_700_000_010_000L)

        val accounts = db.accountDao().getAllActive()
        assertEquals(1, accounts.size)
        assertEquals(0L, accounts.first().balanceMinor)
        assertEquals("INR", accounts.first().currency)
        assertEquals("Cash Wallet", accounts.first().name)
        assertEquals(SeedData.starterCategories(0L).size, db.categoryDao().getAllActive().size)
        assertEquals(0, db.transactionDao().getAllActive().size)
        assertEquals(0, db.budgetDao().getAllActive().size)
        assertEquals(0, db.plannedPaymentDao().getAllActive().size)
        assertEquals(0, db.holdingDao().getAllActive().size)
        assertEquals(0, db.magicRuleDao().getAllActive().size)
        assertTrue(db.transactionDao().searchFts("Roastery*").isEmpty())

        // Fresh transaction still indexes cleanly in FTS4
        val txId = UUID.randomUUID().toString()
        db.recordTransactionAtomic(
            TransactionEntity(
                id = txId,
                accountId = accounts.first().id,
                categoryId = SeedData.CAT_DINING,
                txType = "EXPENSE",
                amountMinor = -2_000L,
                currency = "INR",
                merchant = "Local Chai Corner",
                occurredAtEpochMs = 1_700_000_030_000L,
                createdAtEpochMs = 1_700_000_030_000L,
                updatedAtEpochMs = 1_700_000_030_000L,
            )
        )
        assertEquals(-2_000L, db.accountDao().getById(accounts.first().id)!!.balanceMinor)
        assertTrue(db.transactionDao().searchFts("Chai*").any { it.id == txId })
    }

    @Test
    fun alignStarterWalletCurrencyIfEmpty_updatesUntouchedWalletCurrencyOnly() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "USD", nowMs = 1_700_000_000_000L)
        db.alignStarterWalletCurrencyIfEmpty("INR", nowMs = 1_700_000_001_000L)
        val after = db.accountDao().getAllActive().single()
        assertEquals("INR", after.currency)

        // Once a balance/transaction exists, switching default currency leaves existing account currency untouched
        db.accountDao().upsert(after.copy(balanceMinor = 50_000L))
        db.alignStarterWalletCurrencyIfEmpty("EUR", nowMs = 1_700_000_002_000L)
        assertEquals("INR", db.accountDao().getAllActive().single().currency)
    }

    @Test
    fun autoPayAndSkipPlannedPayments_handleCatchUpIdempotentlyAndFallBackWhenAccountDeleted() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = 1_700_000_000_000L)
        val wallet = db.accountDao().getAllActive().single()
        val debitCardId = UUID.randomUUID().toString()
        db.accountDao().upsert(
            AccountEntity(
                id = debitCardId,
                name = "HDFC Debit",
                accountType = "DEBIT_CARD",
                currency = "INR",
                balanceMinor = AccountTypeCatalog.normalizeOpeningBalanceMinor("DEBIT_CARD", 200_000L),
                lastFour = "4411",
                createdAtEpochMs = 1_700_000_000_000L,
                updatedAtEpochMs = 1_700_000_000_000L,
            )
        )

        val oct15 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.OCTOBER, 15, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val nov20 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 20, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val plannedId = UUID.randomUUID().toString()
        db.plannedPaymentDao().upsert(
            PlannedPaymentEntity(
                id = plannedId,
                accountId = debitCardId,
                categoryId = SeedData.CAT_BILLS,
                title = "Fiber Internet",
                amountMinor = 50_000L,
                currency = "INR",
                txType = "EXPENSE",
                cadence = "MONTHLY",
                nextDueAtEpochMs = oct15,
                autoPay = true,
                createdAtEpochMs = oct15,
                updatedAtEpochMs = oct15,
            )
        )

        // Two due cycles (Oct 15 and Nov 15) <= Nov 20 should post exactly once per period
        val postedFirst = db.processDueAutoPayPlannedPayments(nowMs = nov20)
        assertEquals(2, postedFirst)
        assertEquals(100_000L, db.accountDao().getById(debitCardId)!!.balanceMinor)

        val updatedPlanned = db.plannedPaymentDao().getById(plannedId)!!
        assertTrue(updatedPlanned.nextDueAtEpochMs > nov20)

        // Second run must be 100% idempotent
        assertEquals(0, db.processDueAutoPayPlannedPayments(nowMs = nov20))

        // Skipping advances one cadence without posting a transaction
        val beforeSkipDue = updatedPlanned.nextDueAtEpochMs
        assertTrue(db.skipPlannedPaymentAtomic(plannedId, nowMs = nov20))
        val afterSkip = db.plannedPaymentDao().getById(plannedId)!!
        assertEquals(LedgerDatabase.advanceDueDateEpochMs(beforeSkipDue, "MONTHLY"), afterSkip.nextDueAtEpochMs)
        assertEquals(2, db.transactionDao().getAllActive().size)

        // If target account is soft-deleted, manual execute falls back safely to active Cash Wallet
        db.accountDao().upsert(db.accountDao().getById(debitCardId)!!.copy(deletedAtEpochMs = nov20, updatedAtEpochMs = nov20))
        assertTrue(db.executePlannedPaymentAtomic(plannedId, wallet.id, nowMs = nov20 + 1_000L))
        assertEquals(-50_000L, db.accountDao().getById(wallet.id)!!.balanceMinor)

        // If all accounts are soft-deleted, execute returns false instead of lying
        db.accountDao().upsert(db.accountDao().getById(wallet.id)!!.copy(deletedAtEpochMs = nov20 + 2_000L, updatedAtEpochMs = nov20 + 2_000L))
        assertFalse(db.executePlannedPaymentAtomic(plannedId, null, nowMs = nov20 + 3_000L))
    }

    @Test
    fun migration1To2_preservesExistingDataAndSupportsSubcategoriesAndCreditLimits() = runTest {
        val dbName = "migration_v1_to_v2_test.db"
        context.deleteDatabase(dbName)

        // Create an authentic v1 database schema matching schemas/.../1.json and insert v1 rows
        val v1Helper = object : SQLiteOpenHelper(context, dbName, null, 1) {
            override fun onCreate(sqldb: SQLiteDatabase) {
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `institution` TEXT NOT NULL, `accountType` TEXT NOT NULL, `currency` TEXT NOT NULL, `balanceMinor` INTEGER NOT NULL, `lastFour` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `lastSyncedAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_updatedAtEpochMs` ON `accounts` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_deletedAtEpochMs` ON `accounts` (`deletedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_updatedAtEpochMs` ON `categories` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `transferAccountId` TEXT, `categoryId` TEXT, `txType` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `convertedMinor` INTEGER, `convertedCurrency` TEXT, `merchant` TEXT NOT NULL, `note` TEXT NOT NULL, `labelsCsv` TEXT NOT NULL, `sourceTag` TEXT NOT NULL, `autoRuleApplied` INTEGER NOT NULL, `occurredAtEpochMs` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_occurredAtEpochMs` ON `transactions` (`occurredAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_updatedAtEpochMs` ON `transactions` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_deletedAtEpochMs` ON `transactions` (`deletedAtEpochMs`)")

                sqldb.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `transactions_fts` USING FTS4(`merchant` TEXT NOT NULL, `note` TEXT NOT NULL, `labelsCsv` TEXT NOT NULL, content=`transactions`)")
                sqldb.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transactions_fts_BEFORE_UPDATE BEFORE UPDATE ON `transactions` BEGIN DELETE FROM `transactions_fts` WHERE `docid`=OLD.`rowid`; END")
                sqldb.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transactions_fts_BEFORE_DELETE BEFORE DELETE ON `transactions` BEGIN DELETE FROM `transactions_fts` WHERE `docid`=OLD.`rowid`; END")
                sqldb.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transactions_fts_AFTER_UPDATE AFTER UPDATE ON `transactions` BEGIN INSERT INTO `transactions_fts`(`docid`, `merchant`, `note`, `labelsCsv`) VALUES (NEW.`rowid`, NEW.`merchant`, NEW.`note`, NEW.`labelsCsv`); END")
                sqldb.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transactions_fts_AFTER_INSERT AFTER INSERT ON `transactions` BEGIN INSERT INTO `transactions_fts`(`docid`, `merchant`, `note`, `labelsCsv`) VALUES (NEW.`rowid`, NEW.`merchant`, NEW.`note`, NEW.`labelsCsv`); END")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` TEXT NOT NULL, `categoryId` TEXT, `name` TEXT NOT NULL, `limitMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `period` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_updatedAtEpochMs` ON `budgets` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `planned_payments` (`id` TEXT NOT NULL, `accountId` TEXT, `categoryId` TEXT, `title` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `txType` TEXT NOT NULL, `cadence` TEXT NOT NULL, `nextDueAtEpochMs` INTEGER NOT NULL, `autoPay` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_payments_nextDueAtEpochMs` ON `planned_payments` (`nextDueAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_payments_updatedAtEpochMs` ON `planned_payments` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `holdings` (`id` TEXT NOT NULL, `symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `assetClass` TEXT NOT NULL, `exchange` TEXT NOT NULL, `quantity` REAL NOT NULL, `avgCostMinor` INTEGER NOT NULL, `currentPriceMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `dayChangeBps` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_holdings_updatedAtEpochMs` ON `holdings` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `magic_rules` (`id` TEXT NOT NULL, `matchPattern` TEXT NOT NULL, `targetCategoryId` TEXT, `targetLabel` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_magic_rules_updatedAtEpochMs` ON `magic_rules` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                sqldb.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'dccae9600b5ddaa8d12af5361fc0c34e')")

                // Insert legacy v1 data
                sqldb.execSQL("INSERT INTO `accounts` VALUES ('a_legacy', 'Legacy Card', 'HDFC', 'CREDIT_CARD', 'INR', -250000, '8812', 'MANUAL', 'OK', 1000, 1000, 1000, NULL, 0)")
                sqldb.execSQL("INSERT INTO `categories` VALUES ('${SeedData.CAT_DINING}', 'Dining & Coffee', 'EXPENSE', 'cafe', '#D97706', 1000, 1000, NULL, 0)")
                sqldb.execSQL("INSERT INTO `transactions` VALUES ('tx_legacy', 'a_legacy', NULL, '${SeedData.CAT_DINING}', 'EXPENSE', -45000, 'INR', NULL, NULL, 'Cafe Coffee Day', 'latte', '', 'MANUAL', 0, 1700000000000, 1000, 1000, NULL, 0)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v1Helper.writableDatabase.close()
        v1Helper.close()

        val migratedDb = Room.databaseBuilder(context, LedgerDatabase::class.java, dbName)
            .addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            // Legacy records survive intact with safe defaults on v2 columns
            val acc = migratedDb.accountDao().getById("a_legacy")!!
            assertEquals(-250_000L, acc.balanceMinor)
            assertEquals(0L, acc.creditLimitMinor)

            val cat = migratedDb.categoryDao().getById(SeedData.CAT_DINING)!!
            assertNull(cat.parentCategoryId)

            val tx = migratedDb.transactionDao().getById("tx_legacy")!!
            assertEquals("", tx.categoryIdsCsv)

            // Idempotent ensureMinimalSetup adds the new expanded daily-life categories & subcategories
            SeedData.ensureMinimalSetup(migratedDb, defaultCurrency = "INR", nowMs = 2_000L)
            assertEquals(SeedData.starterCategories(0L).size, migratedDb.categoryDao().getAllActive().size)
            assertEquals(SeedData.CAT_FUEL, migratedDb.categoryDao().getById(SeedData.SUB_PETROL_DIESEL)?.parentCategoryId)

            // Credit limit update and custom child category creation work end-to-end
            migratedDb.accountDao().upsert(acc.copy(creditLimitMinor = 1_000_000L))
            assertEquals(1_000_000L, migratedDb.accountDao().getById("a_legacy")?.creditLimitMinor)

            val customSub = CategoryEntity("c_custom_ev", "Highway Fastag", "EXPENSE", "fuel", "#F59E0B", SeedData.CAT_FUEL, 3_000L, 3_000L)
            migratedDb.categoryDao().upsert(customSub)
            assertEquals(SeedData.CAT_FUEL, migratedDb.categoryDao().getById("c_custom_ev")?.parentCategoryId)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration2To3_preservesExistingV2DataAndCreatesDebtsGoalsShoppingTables() = runTest {
        val dbName = "migration_v2_to_v3_test.db"
        context.deleteDatabase(dbName)

        val v2Helper = object : SQLiteOpenHelper(context, dbName, null, 2) {
            override fun onCreate(sqldb: SQLiteDatabase) {
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `institution` TEXT NOT NULL, `accountType` TEXT NOT NULL, `currency` TEXT NOT NULL, `balanceMinor` INTEGER NOT NULL, `lastFour` TEXT NOT NULL, `creditLimitMinor` INTEGER NOT NULL, `sourceType` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `lastSyncedAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_updatedAtEpochMs` ON `accounts` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_deletedAtEpochMs` ON `accounts` (`deletedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `parentCategoryId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_updatedAtEpochMs` ON `categories` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `transferAccountId` TEXT, `categoryId` TEXT, `categoryIdsCsv` TEXT NOT NULL, `txType` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `convertedMinor` INTEGER, `convertedCurrency` TEXT, `merchant` TEXT NOT NULL, `note` TEXT NOT NULL, `labelsCsv` TEXT NOT NULL, `sourceTag` TEXT NOT NULL, `autoRuleApplied` INTEGER NOT NULL, `occurredAtEpochMs` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_occurredAtEpochMs` ON `transactions` (`occurredAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_updatedAtEpochMs` ON `transactions` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_deletedAtEpochMs` ON `transactions` (`deletedAtEpochMs`)")

                sqldb.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `transactions_fts` USING FTS4(`merchant` TEXT NOT NULL, `note` TEXT NOT NULL, `labelsCsv` TEXT NOT NULL, content=`transactions`)")
                sqldb.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transactions_fts_BEFORE_UPDATE BEFORE UPDATE ON `transactions` BEGIN DELETE FROM `transactions_fts` WHERE `docid`=OLD.`rowid`; END")
                sqldb.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transactions_fts_BEFORE_DELETE BEFORE DELETE ON `transactions` BEGIN DELETE FROM `transactions_fts` WHERE `docid`=OLD.`rowid`; END")
                sqldb.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transactions_fts_AFTER_UPDATE AFTER UPDATE ON `transactions` BEGIN INSERT INTO `transactions_fts`(`docid`, `merchant`, `note`, `labelsCsv`) VALUES (NEW.`rowid`, NEW.`merchant`, NEW.`note`, NEW.`labelsCsv`); END")
                sqldb.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_transactions_fts_AFTER_INSERT AFTER INSERT ON `transactions` BEGIN INSERT INTO `transactions_fts`(`docid`, `merchant`, `note`, `labelsCsv`) VALUES (NEW.`rowid`, NEW.`merchant`, NEW.`note`, NEW.`labelsCsv`); END")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` TEXT NOT NULL, `categoryId` TEXT, `name` TEXT NOT NULL, `limitMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `period` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_updatedAtEpochMs` ON `budgets` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `planned_payments` (`id` TEXT NOT NULL, `accountId` TEXT, `categoryId` TEXT, `title` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `txType` TEXT NOT NULL, `cadence` TEXT NOT NULL, `nextDueAtEpochMs` INTEGER NOT NULL, `autoPay` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_payments_nextDueAtEpochMs` ON `planned_payments` (`nextDueAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_payments_updatedAtEpochMs` ON `planned_payments` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `holdings` (`id` TEXT NOT NULL, `symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `assetClass` TEXT NOT NULL, `exchange` TEXT NOT NULL, `quantity` REAL NOT NULL, `avgCostMinor` INTEGER NOT NULL, `currentPriceMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `dayChangeBps` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_holdings_updatedAtEpochMs` ON `holdings` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `magic_rules` (`id` TEXT NOT NULL, `matchPattern` TEXT NOT NULL, `targetCategoryId` TEXT, `targetLabel` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_magic_rules_updatedAtEpochMs` ON `magic_rules` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                sqldb.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '05502bd308cf1fa884603d8e50d53afe')")

                sqldb.execSQL("INSERT INTO `accounts` VALUES ('a_sbi', 'SBI', 'SBI Bank', 'SAVINGS', 'INR', 1000000, '', 0, 'MANUAL', 'OK', 1000, 1000, 1000, NULL, 0)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v2Helper.writableDatabase.close()
        v2Helper.close()

        val migratedDb = Room.databaseBuilder(context, LedgerDatabase::class.java, dbName)
            .addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            val acc = migratedDb.accountDao().getById("a_sbi")!!
            assertEquals(1_000_000L, acc.balanceMinor)

            migratedDb.debtDao().upsert(DebtEntity("d1", "Rohan", "LENT", 50_000L, 50_000L, "INR", acc.id, null, "", "ACTIVE", 2000L, 2000L))
            migratedDb.savingsGoalDao().upsert(SavingsGoalEntity("g1", "Emergency Fund", 1_000_000L, 250_000L, "INR", null, "savings", "#059669", acc.id, "ACTIVE", 2000L, 2000L))
            migratedDb.shoppingListDao().upsert(ShoppingListEntity("l1", "Groceries", SeedData.CAT_GROCERIES, 2000L, 2000L))
            migratedDb.shoppingItemDao().upsert(ShoppingItemEntity("i1", "l1", "Milk", 6_000L, false, 2000L, 2000L))

            assertEquals(1, migratedDb.debtDao().getAllActive().size)
            assertEquals(1, migratedDb.savingsGoalDao().getAllActive().size)
            assertEquals(1, migratedDb.shoppingListDao().getAllActive().size)
            assertEquals(1, migratedDb.shoppingItemDao().getActiveForList("l1").size)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun debtsGoalsShopping_atomicLedgerFlowsAndResetWorkEndToEnd() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = 1_700_000_000_000L)
        val wallet = db.accountDao().getAllActive().single()
        db.accountDao().upsert(wallet.copy(balanceMinor = 1_000_000L)) // ₹10,000.00

        // 1. Lent ₹2,000 (200,000 minor) with initial transaction deducts wallet -> ₹8,000
        val debt = DebtEntity("debt_1", "Rohan", "LENT", 200_000L, 200_000L, "INR", wallet.id, null, "Trip advance", "ACTIVE", 10L, 10L)
        assertTrue(db.createDebtAtomic(debt, createInitialTransaction = true))
        assertEquals(800_000L, db.accountDao().getById(wallet.id)!!.balanceMinor)

        // Partial repayment ₹800 -> wallet ₹8,800, debt still ACTIVE with ₹1,200 remaining
        assertTrue(db.recordDebtRepaymentAtomic("debt_1", 80_000L, wallet.id, recordTransaction = true, nowMs = 20L))
        assertEquals(880_000L, db.accountDao().getById(wallet.id)!!.balanceMinor)
        val partialDebt = db.debtDao().getById("debt_1")!!
        assertEquals(120_000L, partialDebt.remainingAmountMinor)
        assertEquals("ACTIVE", partialDebt.status)

        // Final repayment ₹1,200 -> wallet restored to ₹10,000, debt auto-moves to CLOSED
        assertTrue(db.recordDebtRepaymentAtomic("debt_1", 120_000L, wallet.id, recordTransaction = true, nowMs = 30L))
        assertEquals(1_000_000L, db.accountDao().getById(wallet.id)!!.balanceMinor)
        val closedDebt = db.debtDao().getById("debt_1")!!
        assertEquals(0L, closedDebt.remainingAmountMinor)
        assertEquals("CLOSED", closedDebt.status)

        // 2. Savings Goal contribution: target ₹5,000, saved ₹2,000 + ₹3,000 contribution -> REACHED, wallet ₹7,000
        db.savingsGoalDao().upsert(SavingsGoalEntity("goal_1", "Emergency Fund", 500_000L, 200_000L, "INR", null, "savings", "#059669", wallet.id, "ACTIVE", 40L, 40L))
        assertTrue(db.addGoalContributionAtomic("goal_1", 300_000L, wallet.id, deductFromAccount = true, nowMs = 50L))
        assertEquals(700_000L, db.accountDao().getById(wallet.id)!!.balanceMinor)
        val reachedGoal = db.savingsGoalDao().getById("goal_1")!!
        assertEquals(500_000L, reachedGoal.savedAmountMinor)
        assertEquals("REACHED", reachedGoal.status)

        // 3. Shopping checklist -> Log checked items as Expense: wallet ₹7,000 -> ₹6,640, checked items removed
        db.shoppingListDao().upsert(ShoppingListEntity("list_1", "Weekend Groceries", SeedData.CAT_GROCERIES, 60L, 60L))
        db.shoppingItemDao().upsertAll(
            listOf(
                ShoppingItemEntity("s1", "list_1", "Milk", 6_000L, checked = true, createdAtEpochMs = 60L, updatedAtEpochMs = 60L),
                ShoppingItemEntity("s2", "list_1", "Almonds", 30_000L, checked = true, createdAtEpochMs = 61L, updatedAtEpochMs = 61L),
                ShoppingItemEntity("s3", "list_1", "Olive Oil", 45_000L, checked = false, createdAtEpochMs = 62L, updatedAtEpochMs = 62L),
            )
        )
        assertTrue(
            db.convertShoppingItemsToExpenseAtomic(
                listId = "list_1",
                itemIds = listOf("s1", "s2"),
                accountId = wallet.id,
                categoryId = SeedData.CAT_GROCERIES,
                totalMinor = 36_000L,
                currency = "INR",
                merchantTitle = "Weekend Groceries",
                nowMs = 70L,
            )
        )
        assertEquals(664_000L, db.accountDao().getById(wallet.id)!!.balanceMinor)
        val remainingShoppingItems = db.shoppingItemDao().getActiveForList("list_1")
        assertEquals(1, remainingShoppingItems.size)
        assertEquals("Olive Oil", remainingShoppingItems.single().name)
        val expenseTx = db.transactionDao().getAllActive().first { it.merchant == "Weekend Groceries" }
        assertTrue(expenseTx.note.contains("Milk"))
        assertTrue(expenseTx.note.contains("Almonds"))

        // 4. Start Fresh resets Debts, Goals, and Shopping Lists alongside core ledger data
        db.resetAllDataFresh(defaultCurrency = "INR", nowMs = 100L)
        assertEquals(0, db.debtDao().getAllActive().size)
        assertEquals(0, db.savingsGoalDao().getAllActive().size)
        assertEquals(0, db.shoppingListDao().getAllActive().size)
        assertEquals(0, db.shoppingItemDao().getAllActive().size)
    }
}
