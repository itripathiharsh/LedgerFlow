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
import com.ledgerflow.app.domain.MagicRuleEngine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
}
