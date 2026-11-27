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
import com.ledgerflow.app.domain.CategoryTree
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.ParsedShoppingItem
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
            .addMigrations(
                LedgerDatabase.MIGRATION_1_2,
                LedgerDatabase.MIGRATION_2_3,
                LedgerDatabase.MIGRATION_3_4,
                LedgerDatabase.MIGRATION_4_5,
                LedgerDatabase.MIGRATION_5_6,
            )
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
            .addMigrations(
                LedgerDatabase.MIGRATION_1_2,
                LedgerDatabase.MIGRATION_2_3,
                LedgerDatabase.MIGRATION_3_4,
                LedgerDatabase.MIGRATION_4_5,
                LedgerDatabase.MIGRATION_5_6,
            )
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

        // 3. Shopping checklist -> Log checked items as Expense: wallet ₹7,000 -> ₹6,640, preserves checklist & prevents duplicate conversion
        db.shoppingListDao().upsert(ShoppingListEntity("list_1", "Weekend Groceries", SeedData.CAT_GROCERIES, 60L, 60L))
        db.shoppingItemDao().upsertAll(
            listOf(
                ShoppingItemEntity("s1", "list_1", "Milk", 6_000L, checked = true, createdAtEpochMs = 60L, updatedAtEpochMs = 60L),
                ShoppingItemEntity("s2", "list_1", "Almonds", 30_000L, checked = true, createdAtEpochMs = 61L, updatedAtEpochMs = 61L),
                ShoppingItemEntity("s3", "list_1", "Olive Oil", 45_000L, checked = false, createdAtEpochMs = 62L, updatedAtEpochMs = 62L),
            )
        )
        val convertRes = db.convertShoppingItemsToExpenseAtomic(
            listId = "list_1",
            itemIds = listOf("s1", "s2"),
            accountId = wallet.id,
            categoryId = SeedData.CAT_GROCERIES,
            totalMinor = 36_000L,
            currency = "INR",
            merchantTitle = "Weekend Groceries",
            nowMs = 70L,
        )
        assertTrue(convertRes.succeeded)
        assertEquals(2, convertRes.convertedItemCount)
        assertNotNull(convertRes.transactionId)
        assertEquals(664_000L, db.accountDao().getById(wallet.id)!!.balanceMinor)

        // Original shopping list & items are preserved cleanly!
        val preservedItems = db.shoppingItemDao().getActiveForList("list_1")
        assertEquals(3, preservedItems.size)
        val expenseTx = db.transactionDao().getAllActive().first { it.merchant == "Weekend Groceries" }
        assertTrue(expenseTx.note.contains("Milk"))
        assertTrue(expenseTx.note.contains("Almonds"))

        // Duplicate conversion of the exact same basket must be blocked cleanly without double debit
        val duplicateAttempt = db.convertShoppingItemsToExpenseAtomic(
            listId = "list_1",
            itemIds = listOf("s1", "s2"),
            accountId = wallet.id,
            categoryId = SeedData.CAT_GROCERIES,
            totalMinor = 36_000L,
            currency = "INR",
            merchantTitle = "Weekend Groceries",
            nowMs = 75L,
        )
        assertEquals(LedgerDatabase.ConvertShoppingOutcomeStatus.DUPLICATE_ALREADY_CONVERTED, duplicateAttempt.status)
        assertEquals(664_000L, db.accountDao().getById(wallet.id)!!.balanceMinor)

        // 4. Start Fresh resets Debts, Goals, and Shopping Lists alongside core ledger data
        db.resetAllDataFresh(defaultCurrency = "INR", nowMs = 100L)
        assertEquals(0, db.debtDao().getAllActive().size)
        assertEquals(0, db.savingsGoalDao().getAllActive().size)
        assertEquals(0, db.shoppingListDao().getAllActive().size)
        assertEquals(0, db.shoppingItemDao().getAllActive().size)
    }

    @Test
    fun migration3To4_preservesV3DataAndSupportsPendingReviewAuditColumns() = runTest {
        val dbName = "migration_v3_to_v4_test.db"
        context.deleteDatabase(dbName)

        val v3Helper = object : SQLiteOpenHelper(context, dbName, null, 3) {
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
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `debts` (`id` TEXT NOT NULL, `personName` TEXT NOT NULL, `direction` TEXT NOT NULL, `totalAmountMinor` INTEGER NOT NULL, `remainingAmountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `accountId` TEXT, `dueAtEpochMs` INTEGER, `note` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_debts_updatedAtEpochMs` ON `debts` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `savings_goals` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `targetAmountMinor` INTEGER NOT NULL, `savedAmountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `targetDateEpochMs` INTEGER, `iconKey` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `linkedAccountId` TEXT, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_goals_updatedAtEpochMs` ON `savings_goals` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `shopping_lists` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `defaultCategoryId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_lists_updatedAtEpochMs` ON `shopping_lists` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `shopping_items` (`id` TEXT NOT NULL, `listId` TEXT NOT NULL, `name` TEXT NOT NULL, `estimatedPriceMinor` INTEGER NOT NULL, `checked` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_listId` ON `shopping_items` (`listId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_updatedAtEpochMs` ON `shopping_items` (`updatedAtEpochMs`)")

                sqldb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                sqldb.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '52ee24071873aa389acff4053547f888')")
                sqldb.execSQL("INSERT INTO `accounts` VALUES ('a_hdfc', 'HDFC', 'HDFC Bank', 'CHECKING', 'INR', 500000, '4821', 0, 'MANUAL', 'OK', 1000, 1000, 1000, NULL, 0)")
                sqldb.execSQL("INSERT INTO `transactions` VALUES ('tx_v3', 'a_hdfc', NULL, '${SeedData.CAT_DINING}', '', 'EXPENSE', -15000, 'INR', NULL, NULL, 'Coffee House', 'note', '', 'MANUAL', 0, 1000, 1000, 1000, NULL, 0)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v3Helper.writableDatabase.close()
        v3Helper.close()

        val migratedDb = Room.databaseBuilder(context, LedgerDatabase::class.java, dbName)
            .addMigrations(
                LedgerDatabase.MIGRATION_1_2,
                LedgerDatabase.MIGRATION_2_3,
                LedgerDatabase.MIGRATION_3_4,
                LedgerDatabase.MIGRATION_4_5,
                LedgerDatabase.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val legacyTx = migratedDb.transactionDao().getById("tx_v3")!!
            assertEquals(TransactionEntity.STATUS_CONFIRMED, legacyTx.reviewStatus)
            assertEquals(0, migratedDb.transactionDao().getPendingReview().size)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun pendingReviewLifecycle_handlesDuplicatesUpdatesFailuresAndUnassignedBalancesAtomically() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = 1_700_000_000_000L)
        val wallet = db.accountDao().getAllActive().single()
        db.accountDao().upsert(wallet.copy(balanceMinor = 1_000_000L))
        val hdfc = AccountEntity("a_hdfc", "HDFC Bank", "HDFC", "CHECKING", "INR", 500_000L, "4821", 0L, "MANUAL", "OK", null, 1L, 1L)
        db.accountDao().upsert(hdfc)

        // 1. Initial notification for HDFC arrives as PENDING_REVIEW with no category and deducts HDFC once
        val pendingTx = TransactionEntity(
            id = "tx_pending_1",
            accountId = hdfc.id,
            categoryId = null,
            txType = "EXPENSE",
            amountMinor = -45_000L,
            currency = "INR",
            merchant = "Swiggy",
            sourceTag = "BANK_SYNC",
            occurredAtEpochMs = 10_000L,
            createdAtEpochMs = 10_000L,
            updatedAtEpochMs = 10_000L,
            reviewStatus = TransactionEntity.STATUS_PENDING_REVIEW,
            paymentAppName = "PhonePe",
            referenceId = "432198765432",
            notificationKey = "0|com.phonepe.app|1|null|100",
            notificationFingerprint = "fp_1",
            originalParsedAmountMinor = -45_000L,
        )
        val firstRes = db.upsertDetectedPaymentAtomic(pendingTx, nowMs = 10_000L)
        assertEquals(LedgerDatabase.DetectedCaptureAction.CREATED_PENDING, firstRes.action)
        assertEquals(455_000L, db.accountDao().getById(hdfc.id)!!.balanceMinor)

        // 2. Duplicate reposted notification with same fingerprint/reference ID is ignored without double deduction
        val dupRes = db.upsertDetectedPaymentAtomic(pendingTx.copy(id = "tx_dup"), nowMs = 10_500L)
        assertEquals(LedgerDatabase.DetectedCaptureAction.IGNORED_DUPLICATE, dupRes.action)
        assertEquals(455_000L, db.accountDao().getById(hdfc.id)!!.balanceMinor)

        // 3. Updated Android notification sharing same notificationKey corrects amount (-450 -> -480) cleanly
        val updatedNotif = pendingTx.copy(
            id = "tx_upd_candidate",
            amountMinor = -48_000L,
            notificationFingerprint = "fp_2",
            originalParsedAmountMinor = -48_000L,
        )
        val updRes = db.upsertDetectedPaymentAtomic(updatedNotif, nowMs = 11_000L)
        assertEquals(LedgerDatabase.DetectedCaptureAction.UPDATED_PENDING, updRes.action)
        assertEquals(452_000L, db.accountDao().getById(hdfc.id)!!.balanceMinor)

        // 4. User confirms, edits parsed amount (-480 -> -500), assigns category + spending purpose
        assertTrue(
            db.confirmOrUpdateTransactionAtomic(
                txId = "tx_pending_1",
                txType = "EXPENSE",
                magnitudeMinor = 50_000L,
                currency = "INR",
                accountId = hdfc.id,
                transferAccountId = null,
                categoryId = SeedData.CAT_DINING,
                categoryIdsCsv = SeedData.CAT_DINING,
                spendingPurpose = "Dinner with friends",
                merchant = "Swiggy Instamart",
                note = "Corrected tip",
                labelsCsv = "",
                markConfirmed = true,
                baseCurrency = "INR",
                nowMs = 12_000L,
            )
        )
        assertEquals(450_000L, db.accountDao().getById(hdfc.id)!!.balanceMinor)
        val confirmed = db.transactionDao().getById("tx_pending_1")!!
        assertEquals(TransactionEntity.STATUS_CONFIRMED, confirmed.reviewStatus)
        assertEquals(SeedData.CAT_DINING, confirmed.categoryId)
        assertEquals("Dinner with friends", confirmed.spendingPurpose)
        assertEquals(-48_000L, confirmed.originalParsedAmountMinor)

        // 5. Late updated notification for an already-CONFIRMED transaction must NOT overwrite user classification
        val lateNotifAfterConfirm = updatedNotif.copy(merchant = "Raw Gateway Name", amountMinor = -99_000L, notificationFingerprint = "fp_3")
        val lateIgnored = db.upsertDetectedPaymentAtomic(lateNotifAfterConfirm, nowMs = 13_000L)
        assertEquals(LedgerDatabase.DetectedCaptureAction.IGNORED_CONFIRMED, lateIgnored.action)
        assertEquals(450_000L, db.accountDao().getById(hdfc.id)!!.balanceMinor)
        assertEquals("Swiggy Instamart", db.transactionDao().getById("tx_pending_1")!!.merchant)

        // 6. User can edit a CONFIRMED transaction later (moving to wallet, ₹300) cleanly
        assertTrue(
            db.confirmOrUpdateTransactionAtomic(
                txId = "tx_pending_1",
                txType = "EXPENSE",
                magnitudeMinor = 30_000L,
                currency = "INR",
                accountId = wallet.id,
                transferAccountId = null,
                categoryId = SeedData.CAT_GROCERIES,
                categoryIdsCsv = SeedData.CAT_GROCERIES,
                spendingPurpose = "Household groceries",
                merchant = "Swiggy Instamart",
                note = "Edited later",
                labelsCsv = "",
                markConfirmed = true,
                baseCurrency = "INR",
                nowMs = 14_000L,
            )
        )
        assertEquals(500_000L, db.accountDao().getById(hdfc.id)!!.balanceMinor) // Reversed back on HDFC!
        assertEquals(970_000L, db.accountDao().getById(wallet.id)!!.balanceMinor) // Deducted once on Wallet!

        // 7. Unassigned Account PENDING_REVIEW keeps all balances untouched until assigned
        val unassignedTx = TransactionEntity(
            id = "tx_unassigned_1",
            accountId = TransactionEntity.UNASSIGNED_ACCOUNT_ID,
            categoryId = null,
            txType = "EXPENSE",
            amountMinor = -20_000L,
            currency = "INR",
            merchant = "Local Store",
            sourceTag = "BANK_SYNC",
            occurredAtEpochMs = 20_000L,
            createdAtEpochMs = 20_000L,
            updatedAtEpochMs = 20_000L,
            reviewStatus = TransactionEntity.STATUS_PENDING_REVIEW,
            paymentAppName = "Paytm",
            notificationKey = "0|net.one97.paytm|5|null|200",
            notificationFingerprint = "fp_unassigned",
        )
        assertEquals(LedgerDatabase.DetectedCaptureAction.CREATED_PENDING, db.upsertDetectedPaymentAtomic(unassignedTx, nowMs = 20_000L).action)
        assertEquals(500_000L, db.accountDao().getById(hdfc.id)!!.balanceMinor)
        assertEquals(970_000L, db.accountDao().getById(wallet.id)!!.balanceMinor)

        // Assigning HDFC & confirming applies -200 once
        assertTrue(
            db.confirmOrUpdateTransactionAtomic(
                txId = "tx_unassigned_1",
                txType = "EXPENSE",
                magnitudeMinor = 20_000L,
                currency = "INR",
                accountId = hdfc.id,
                transferAccountId = null,
                categoryId = SeedData.CAT_GROCERIES,
                categoryIdsCsv = SeedData.CAT_GROCERIES,
                spendingPurpose = "Snacks",
                merchant = "Local Store",
                note = "",
                labelsCsv = "",
                markConfirmed = true,
                baseCurrency = "INR",
                nowMs = 21_000L,
            )
        )
        assertEquals(480_000L, db.accountDao().getById(hdfc.id)!!.balanceMinor)

        // 8. Notification updating to FAILURE/DECLINED soft-deletes a pending item & reverses balance
        val tentativeTx = TransactionEntity(
            id = "tx_tentative",
            accountId = hdfc.id,
            categoryId = null,
            txType = "EXPENSE",
            amountMinor = -10_000L,
            currency = "INR",
            merchant = "Cab",
            sourceTag = "BANK_SYNC",
            occurredAtEpochMs = 30_000L,
            createdAtEpochMs = 30_000L,
            updatedAtEpochMs = 30_000L,
            reviewStatus = TransactionEntity.STATUS_PENDING_REVIEW,
            notificationKey = "0|com.phonepe.app|9|null|300",
            notificationFingerprint = "fp_tent",
        )
        db.upsertDetectedPaymentAtomic(tentativeTx, nowMs = 30_000L)
        assertEquals(470_000L, db.accountDao().getById(hdfc.id)!!.balanceMinor)
        assertTrue(db.handleDetectedFailureNotificationAtomic("0|com.phonepe.app|9|null|300", "", nowMs = 31_000L))
        assertEquals(480_000L, db.accountDao().getById(hdfc.id)!!.balanceMinor)
    }

    @Test
    fun migration4To5_addsQuantityLabelAndPreservesShoppingItems() = runTest {
        val dbName = "migration_v4_v5_test.db"
        context.deleteDatabase(dbName)
        val helper = object : SQLiteOpenHelper(context, dbName, null, 4) {
            override fun onCreate(sqldb: SQLiteDatabase) {
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `institution` TEXT NOT NULL, `accountType` TEXT NOT NULL, `currency` TEXT NOT NULL, `balanceMinor` INTEGER NOT NULL, `lastFour` TEXT NOT NULL, `creditLimitMinor` INTEGER NOT NULL, `sourceType` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `lastSyncedAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_updatedAtEpochMs` ON `accounts` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_deletedAtEpochMs` ON `accounts` (`deletedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `parentCategoryId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_updatedAtEpochMs` ON `categories` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `transferAccountId` TEXT, `categoryId` TEXT, `categoryIdsCsv` TEXT NOT NULL, `txType` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `convertedMinor` INTEGER, `convertedCurrency` TEXT, `merchant` TEXT NOT NULL, `note` TEXT NOT NULL, `labelsCsv` TEXT NOT NULL, `sourceTag` TEXT NOT NULL, `autoRuleApplied` INTEGER NOT NULL, `occurredAtEpochMs` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, `reviewStatus` TEXT NOT NULL, `spendingPurpose` TEXT NOT NULL, `paymentAppName` TEXT NOT NULL, `paymentAppPackage` TEXT NOT NULL, `referenceId` TEXT NOT NULL, `accountHint` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, `notificationFingerprint` TEXT NOT NULL, `originalParsedAmountMinor` INTEGER, `isRefundOrReversal` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_occurredAtEpochMs` ON `transactions` (`occurredAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_updatedAtEpochMs` ON `transactions` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_deletedAtEpochMs` ON `transactions` (`deletedAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_reviewStatus` ON `transactions` (`reviewStatus`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_referenceId` ON `transactions` (`referenceId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_notificationKey` ON `transactions` (`notificationKey`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_notificationFingerprint` ON `transactions` (`notificationFingerprint`)")
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
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `debts` (`id` TEXT NOT NULL, `personName` TEXT NOT NULL, `direction` TEXT NOT NULL, `totalAmountMinor` INTEGER NOT NULL, `remainingAmountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `accountId` TEXT, `dueAtEpochMs` INTEGER, `note` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_debts_updatedAtEpochMs` ON `debts` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `savings_goals` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `targetAmountMinor` INTEGER NOT NULL, `savedAmountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `targetDateEpochMs` INTEGER, `iconKey` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `linkedAccountId` TEXT, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_goals_updatedAtEpochMs` ON `savings_goals` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `shopping_lists` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `defaultCategoryId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_lists_updatedAtEpochMs` ON `shopping_lists` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `shopping_items` (`id` TEXT NOT NULL, `listId` TEXT NOT NULL, `name` TEXT NOT NULL, `estimatedPriceMinor` INTEGER NOT NULL, `checked` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_listId` ON `shopping_items` (`listId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_updatedAtEpochMs` ON `shopping_items` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                sqldb.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '741f4e45610f17a606b180397aa55e1a')")
                sqldb.execSQL("INSERT INTO `shopping_lists` VALUES ('list1', 'Grocery', 'cat_groceries', 1000, 1000, NULL, 1)")
                sqldb.execSQL("INSERT INTO `shopping_items` VALUES ('item1', 'list1', 'Bread', 4500, 0, 1000, 1000, NULL, 1)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close()
        helper.close()

        val migratedDb = Room.databaseBuilder(context, LedgerDatabase::class.java, dbName)
            .addMigrations(
                LedgerDatabase.MIGRATION_1_2,
                LedgerDatabase.MIGRATION_2_3,
                LedgerDatabase.MIGRATION_3_4,
                LedgerDatabase.MIGRATION_4_5,
                LedgerDatabase.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val migratedItem = migratedDb.shoppingItemDao().getById("item1")
            assertNotNull(migratedItem)
            assertEquals("Bread", migratedItem!!.name)
            assertEquals("", migratedItem.quantityLabel)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration5To6_upgradesHierarchyDescriptionAndStructuredShoppingColumns() = runTest {
        val dbName = "migration_v5_v6_test.db"
        context.deleteDatabase(dbName)
        val helper = object : SQLiteOpenHelper(context, dbName, null, 5) {
            override fun onCreate(sqldb: SQLiteDatabase) {
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `institution` TEXT NOT NULL, `accountType` TEXT NOT NULL, `currency` TEXT NOT NULL, `balanceMinor` INTEGER NOT NULL, `lastFour` TEXT NOT NULL, `creditLimitMinor` INTEGER NOT NULL, `sourceType` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `lastSyncedAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_updatedAtEpochMs` ON `accounts` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_deletedAtEpochMs` ON `accounts` (`deletedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `parentCategoryId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_updatedAtEpochMs` ON `categories` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `transferAccountId` TEXT, `categoryId` TEXT, `categoryIdsCsv` TEXT NOT NULL, `txType` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `convertedMinor` INTEGER, `convertedCurrency` TEXT, `merchant` TEXT NOT NULL, `note` TEXT NOT NULL, `labelsCsv` TEXT NOT NULL, `sourceTag` TEXT NOT NULL, `autoRuleApplied` INTEGER NOT NULL, `occurredAtEpochMs` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, `reviewStatus` TEXT NOT NULL, `spendingPurpose` TEXT NOT NULL, `paymentAppName` TEXT NOT NULL, `paymentAppPackage` TEXT NOT NULL, `referenceId` TEXT NOT NULL, `accountHint` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, `notificationFingerprint` TEXT NOT NULL, `originalParsedAmountMinor` INTEGER, `isRefundOrReversal` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_occurredAtEpochMs` ON `transactions` (`occurredAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_updatedAtEpochMs` ON `transactions` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_deletedAtEpochMs` ON `transactions` (`deletedAtEpochMs`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_reviewStatus` ON `transactions` (`reviewStatus`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_referenceId` ON `transactions` (`referenceId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_notificationKey` ON `transactions` (`notificationKey`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_notificationFingerprint` ON `transactions` (`notificationFingerprint`)")
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
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `debts` (`id` TEXT NOT NULL, `personName` TEXT NOT NULL, `direction` TEXT NOT NULL, `totalAmountMinor` INTEGER NOT NULL, `remainingAmountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `accountId` TEXT, `dueAtEpochMs` INTEGER, `note` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_debts_updatedAtEpochMs` ON `debts` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `savings_goals` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `targetAmountMinor` INTEGER NOT NULL, `savedAmountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `targetDateEpochMs` INTEGER, `iconKey` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `linkedAccountId` TEXT, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_goals_updatedAtEpochMs` ON `savings_goals` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `shopping_lists` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `defaultCategoryId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_lists_updatedAtEpochMs` ON `shopping_lists` (`updatedAtEpochMs`)")
                sqldb.execSQL("CREATE TABLE IF NOT EXISTS `shopping_items` (`id` TEXT NOT NULL, `listId` TEXT NOT NULL, `name` TEXT NOT NULL, `estimatedPriceMinor` INTEGER NOT NULL, `checked` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `deletedAtEpochMs` INTEGER, `pendingSync` INTEGER NOT NULL, `quantityLabel` TEXT NOT NULL, PRIMARY KEY(`id`))")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_listId` ON `shopping_items` (`listId`)")
                sqldb.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_updatedAtEpochMs` ON `shopping_items` (`updatedAtEpochMs`)")
                sqldb.execSQL("INSERT INTO `categories` VALUES ('c1', 'Groceries', 'EXPENSE', 'shopping_cart', '#10B981', NULL, 1000, 1000, NULL, 1)")
                sqldb.execSQL("INSERT INTO `shopping_lists` VALUES ('l1', 'Weekly Pantry', 'c1', 1000, 1000, NULL, 1)")
                sqldb.execSQL("INSERT INTO `shopping_items` VALUES ('i1', 'l1', 'Rice', 35000, 0, 1000, 1000, NULL, 1, '5 kg')")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close()
        helper.close()

        val migratedDb = Room.databaseBuilder(context, LedgerDatabase::class.java, dbName)
            .addMigrations(
                LedgerDatabase.MIGRATION_1_2,
                LedgerDatabase.MIGRATION_2_3,
                LedgerDatabase.MIGRATION_3_4,
                LedgerDatabase.MIGRATION_4_5,
                LedgerDatabase.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val cat = migratedDb.categoryDao().getById("c1")!!
            assertEquals("", cat.description)
            val list = migratedDb.shoppingListDao().getById("l1")!!
            assertNull(list.lastConvertedTxId)
            assertEquals("", list.lastConvertedSignature)
            val item = migratedDb.shoppingItemDao().getById("i1")!!
            assertEquals("5 kg", item.quantityLabel)
            assertNull(item.quantityValue)
            assertEquals("", item.quantityUnit)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun hierarchicalCategories_enforceCyclesDuplicatesAndSafeDeletion() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = 1_000L)
        val wallet = db.accountDao().getAllActive().single()

        // Create Level 1 -> Level 2 -> Level 3 -> Level 4 hierarchy
        val lvl1 = (db.upsertCategorySafeAtomic(null, "Campus Life", "EXPENSE", null, "school", "#6366F1", "Campus costs", 2_000L) as LedgerDatabase.CategoryMutationResult.Success).category
        val lvl2 = (db.upsertCategorySafeAtomic(null, "Hostel", "EXPENSE", lvl1.id, "hotel", "#6366F1", "", 2_100L) as LedgerDatabase.CategoryMutationResult.Success).category
        val lvl3 = (db.upsertCategorySafeAtomic(null, "Mess", "EXPENSE", lvl2.id, "restaurant", "#6366F1", "", 2_200L) as LedgerDatabase.CategoryMutationResult.Success).category
        val lvl4 = (db.upsertCategorySafeAtomic(null, "Late Snacks", "EXPENSE", lvl3.id, "cafe", "#6366F1", "", 2_300L) as LedgerDatabase.CategoryMutationResult.Success).category

        val activeMap = db.categoryDao().getAllActive().associateBy { it.id }
        assertEquals("Campus Life › Hostel › Mess › Late Snacks", CategoryTree.breadcrumbPath(lvl4.id, activeMap))
        assertEquals(3, CategoryTree.depthOf(lvl4.id, activeMap))

        // Reject circular move (moving lvl1 under its 4th-level descendant lvl4)
        val cycleAttempt = db.upsertCategorySafeAtomic(lvl1.id, lvl1.name, lvl1.kind, lvl4.id, lvl1.iconKey, lvl1.colorHex, "", 2_400L)
        assertTrue(cycleAttempt is LedgerDatabase.CategoryMutationResult.Error)

        // Reject same-level sibling duplicate case-insensitively, allow same name under different parent
        val dupSibling = db.upsertCategorySafeAtomic(null, "mess", "EXPENSE", lvl2.id, "restaurant", "#6366F1", "", 2_500L)
        assertTrue(dupSibling is LedgerDatabase.CategoryMutationResult.Error)
        val diffParentSameName = db.upsertCategorySafeAtomic(null, "Late Snacks", "EXPENSE", lvl1.id, "cafe", "#6366F1", "", 2_600L)
        assertTrue(diffParentSameName is LedgerDatabase.CategoryMutationResult.Success)

        // Record tx & budget on lvl3 ("Mess"), then inspect impact and safely move children + reassign usages
        db.recordTransactionAtomic(
            TransactionEntity("tx_mess", wallet.id, null, lvl3.id, lvl3.id, "EXPENSE", -20_000L, "INR", null, null, "Night Canteen", "", "", "MANUAL", false, 3_000L, 3_000L, 3_000L)
        )
        val impact = db.inspectCategoryDeletionImpact(lvl3.id)!!
        assertEquals(1, impact.directChildrenCount)
        assertEquals(1, impact.linkedTransactionsCount)

        val delRes = db.deleteCategoryWithSafeguardsAtomic(
            categoryId = lvl3.id,
            childStrategy = LedgerDatabase.ChildDeleteStrategy.MOVE_CHILDREN_TO_PARENT_OR_TARGET,
            moveChildrenTargetParentId = lvl2.id,
            usageStrategy = LedgerDatabase.UsageDeleteStrategy.REASSIGN_TO_TARGET,
            reassignCategoryId = SeedData.CAT_GENERAL,
            nowMs = 4_000L,
        )
        assertEquals(1, delRes.deletedCount)
        assertEquals(1, delRes.movedChildrenCount)
        assertEquals(1, delRes.reassignedUsagesCount)
        assertEquals(lvl2.id, db.categoryDao().getById(lvl4.id)!!.parentCategoryId)
        assertEquals(SeedData.CAT_GENERAL, db.transactionDao().getById("tx_mess")!!.categoryId)
    }

    @Test
    fun shoppingCrud_handlesStructuredQuantitiesUnitsEditAndClearActions() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = 1_000L)
        db.shoppingListDao().upsert(ShoppingListEntity("slist_1", "Monthly Staples", SeedData.CAT_GROCERIES, 1_000L, 1_000L))

        val added1 = db.addShoppingItemAtomic("slist_1", "Olive Oil", 2.0, "ltr", 95_000L, nowMs = 1_100L)!!
        assertEquals("2 litres", added1.quantityLabel)
        assertEquals("litre", added1.quantityUnit)
        assertEquals(95_000L, added1.estimatedPriceMinor) // total estimate stored verbatim, never multiplied by 2!

        val addedCustom = db.addShoppingItemAtomic("slist_1", "Pickles", 3.0, "jars", 45_000L, nowMs = 1_200L)!!
        assertEquals("3 jars", addedCustom.quantityLabel)

        val edited = db.updateShoppingItemAtomic(added1.id, "Extra Virgin Olive Oil", 1.5, "litre", 82_500L, nowMs = 1_300L)!!
        assertEquals("Extra Virgin Olive Oil", edited.name)
        assertEquals("1.5 litres", edited.quantityLabel)
        assertEquals(82_500L, edited.estimatedPriceMinor)

        db.toggleShoppingItemCheckedAtomic(addedCustom.id, nowMs = 1_400L)
        assertEquals(1, db.clearCompletedShoppingItemsAtomic("slist_1", nowMs = 1_500L))
        assertEquals(1, db.shoppingItemDao().getActiveForList("slist_1").size)
        assertEquals(1, db.clearAllShoppingItemsAtomic("slist_1", nowMs = 1_600L))
        assertEquals(0, db.shoppingItemDao().getActiveForList("slist_1").size)
    }

    @Test
    fun addParsedShoppingItemsAtomic_autoCreatesListDeduplicatesAndTogglesAtomically() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = 1_000L)
        // First voice capture when zero shopping lists exist -> auto-creates "Shopping List"
        val first = db.addParsedShoppingItemsAtomic(
            listId = null,
            items = listOf(
                ParsedShoppingItem("Atta", "2 kg"),
                ParsedShoppingItem("Milk", "1"),
            ),
            nowMs = 2_000L,
        )
        assertTrue(first.createdNewList)
        assertEquals(2, first.addedCount)
        assertEquals(0, first.skippedDuplicateCount)

        // Second voice capture -> skips unchecked duplicate "Milk", appends "Paneer"
        val second = db.addParsedShoppingItemsAtomic(
            listId = first.listId,
            items = listOf(
                ParsedShoppingItem("milk", "2 L"),
                ParsedShoppingItem("Paneer", "500 g"),
            ),
            nowMs = 3_000L,
        )
        assertFalse(second.createdNewList)
        assertEquals(1, second.addedCount)
        assertEquals(1, second.skippedDuplicateCount)

        val allItems = db.shoppingItemDao().getActiveOrderedForList(first.listId)
        assertEquals(3, allItems.size)
        val milk = allItems.first { it.name.equals("Milk", ignoreCase = true) }
        val toggled = db.toggleShoppingItemCheckedAtomic(milk.id, nowMs = 4_000L)
        assertNotNull(toggled)
        assertTrue(toggled!!.checked)
        assertTrue(db.shoppingItemDao().getById(milk.id)!!.checked)
    }
}
