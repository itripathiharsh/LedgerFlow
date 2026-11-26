package com.ledgerflow.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.BudgetEntity
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.MagicRuleEntity
import com.ledgerflow.app.data.local.PlannedPaymentEntity
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.MagicRuleEngine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals(8, db.categoryDao().getAllActive().size)
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
        assertEquals(8, db.categoryDao().getAllActive().size)
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
}
