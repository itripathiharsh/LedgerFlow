package com.ledgerflow.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ledgerflow.app.data.local.LedgerDatabase
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
    fun seedData_isIdempotentAndPopulatesOfflineLedger() = runTest {
        SeedData.ensureSeededIfEmpty(db, nowMs = 1_700_000_000_000L)
        assertEquals(4, db.accountDao().countAll())
        assertEquals(8, db.transactionDao().getAllActive().size)
        SeedData.ensureSeededIfEmpty(db, nowMs = 1_700_000_005_000L)
        assertEquals(4, db.accountDao().countAll())
    }

    @Test
    fun atomicRecordAndSoftDelete_updatesBalancesAndSupportsFts4Search() = runTest {
        SeedData.ensureSeededIfEmpty(db, nowMs = 1_700_000_000_000L)
        val checking = db.accountDao().getAllActive().first()
        val initialBalance = checking.balanceMinor

        val txId = UUID.randomUUID().toString()
        val tx = TransactionEntity(
            id = txId,
            accountId = checking.id,
            categoryId = SeedData.CAT_DINING,
            txType = "EXPENSE",
            amountMinor = -1250L,
            currency = checking.currency,
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

        val afterDebit = db.accountDao().getById(checking.id)!!
        assertEquals(initialBalance - 1250L, afterDebit.balanceMinor)

        val ftsQuery = MagicRuleEngine.sanitizeFtsQuery("Espresso #coffee")
        val hits = db.transactionDao().searchFts(ftsQuery)
        assertTrue(hits.any { it.id == txId })

        db.softDeleteTransactionAtomic(txId, nowMs = 1_700_000_002_000L)
        val afterRevert = db.accountDao().getById(checking.id)!!
        assertEquals(initialBalance, afterRevert.balanceMinor)
        val tombstone = db.transactionDao().getById(txId)
        assertNotNull(tombstone?.deletedAtEpochMs)
    }

    @Test
    fun recordTransactionAtomic_sameIdUpdatesWithoutDoubleCounting() = runTest {
        SeedData.ensureSeededIfEmpty(db, nowMs = 1_700_000_000_000L)
        val checking = db.accountDao().getAllActive().first()
        val initialBalance = checking.balanceMinor

        val txId = UUID.randomUUID().toString()
        val initialTx = TransactionEntity(
            id = txId,
            accountId = checking.id,
            txType = "EXPENSE",
            amountMinor = -1_000L,
            currency = checking.currency,
            merchant = "Bakery",
            occurredAtEpochMs = 1_700_000_001_000L,
            createdAtEpochMs = 1_700_000_001_000L,
            updatedAtEpochMs = 1_700_000_001_000L,
        )
        db.recordTransactionAtomic(initialTx)
        assertEquals(initialBalance - 1_000L, db.accountDao().getById(checking.id)!!.balanceMinor)

        // Updating existing transaction id must reverse prior -1000 and apply -2500 -> net -2500 (not -3500)
        db.recordTransactionAtomic(initialTx.copy(amountMinor = -2_500L, updatedAtEpochMs = 1_700_000_005_000L))
        assertEquals(initialBalance - 2_500L, db.accountDao().getById(checking.id)!!.balanceMinor)
    }

    @Test
    fun plannedPayment_calendarSchedulingAndSoftDeleteGuard() = runTest {
        SeedData.ensureSeededIfEmpty(db, nowMs = 1_700_000_000_000L)
        val checking = db.accountDao().getAllActive().first()
        val beforeBalance = checking.balanceMinor

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
                accountId = checking.id,
                title = "Cancelled Gym",
                amountMinor = 5_000L,
                currency = checking.currency,
                txType = "EXPENSE",
                cadence = "MONTHLY",
                nextDueAtEpochMs = 1_700_010_000_000L,
                createdAtEpochMs = 1_700_000_000_000L,
                updatedAtEpochMs = 1_700_000_000_000L,
                deletedAtEpochMs = 1_700_000_001_000L,
            )
        )
        db.executePlannedPaymentAtomic(deletedId, checking.id)
        assertEquals(beforeBalance, db.accountDao().getById(checking.id)!!.balanceMinor)
    }
}
