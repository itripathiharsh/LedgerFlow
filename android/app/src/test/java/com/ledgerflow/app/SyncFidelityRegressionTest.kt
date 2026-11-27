package com.ledgerflow.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.data.remote.IsoTime
import com.ledgerflow.app.data.remote.SupabaseSyncClient
import com.ledgerflow.app.data.sync.LedgerSyncRepository
import com.ledgerflow.app.data.sync.SyncOutcome
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.security.VaultStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.URLDecoder
import java.security.Key
import java.security.Security
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory stand-in for the Supabase REST API that keeps the rules the sync bugs depended on:
 * `id` is unique across all users, a user cannot upsert over another user's row, updates get a
 * server timestamp, and a schema without the detail columns rejects payloads that name them.
 */
private class FakeCloud {
    @Volatile var activeUser = "user-a"
    @Volatile var hasDetailColumns = true
    val tables = ConcurrentHashMap<String, LinkedHashMap<String, JsonObject>>()

    private val detailColumns = mapOf(
        "accounts" to mapOf<String, JsonElement>(
            "credit_limit_minor" to JsonPrimitive(0), "account_subtype" to JsonPrimitive(""), "detail_version" to JsonPrimitive(0),
        ),
        "categories" to mapOf<String, JsonElement>(
            "parent_category_id" to JsonNull, "description" to JsonPrimitive(""), "detail_version" to JsonPrimitive(0),
        ),
        "transactions" to mapOf<String, JsonElement>(
            "category_ids_csv" to JsonPrimitive(""), "spending_purpose" to JsonPrimitive(""), "detail_version" to JsonPrimitive(0),
        ),
    )
    private val coreAccountTypes = setOf("CHECKING", "CARD", "CASH", "SAVINGS", "INVESTMENT", "SHARED")

    val server = LocalLoopbackHttpServer(handlerWithBody = { method, path, body -> handle(method, path, body) })

    fun rows(table: String, user: String = activeUser): List<JsonObject> = synchronized(this) {
        tables[table].orEmpty().values.filter { it.text("user_id") == user }
    }

    private fun handle(method: String, path: String, body: String): Pair<Int, String> = synchronized(this) {
        if (!path.startsWith("/rest/v1/")) return@synchronized 404 to "{}"
        val table = path.removePrefix("/rest/v1/").substringBefore('?')
        val store = tables.getOrPut(table) { LinkedHashMap() }
        if (method != "POST") {
            val since = Regex("updated_at=gt\\.([^&]+)").find(path)?.groupValues?.get(1)
                ?.let { IsoTime.parseOr(URLDecoder.decode(it, "UTF-8"), 0L) }
            val visible = store.values
                .filter { it.text("user_id") == activeUser }
                .filter { since == null || it.updatedAt() > since }
                .sortedBy { it.updatedAt() }
            return@synchronized 200 to JsonArray(visible).toString()
        }
        val incoming = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
        val detail = detailColumns[table].orEmpty()
        if (!hasDetailColumns && incoming.any { row -> row.keys.any { it in detail } }) {
            return@synchronized 400 to """{"code":"PGRST204","message":"Could not find the column"}"""
        }
        if (table == "accounts" && incoming.any { it.text("account_type") !in coreAccountTypes }) {
            return@synchronized 400 to """{"code":"23514"}"""
        }
        if (incoming.any { row -> store[row.text("id")]?.let { it.text("user_id") != row.text("user_id") } == true }) {
            return@synchronized 403 to """{"code":"42501","message":"row-level security"}"""
        }
        for (row in incoming) {
            val id = row.text("id")!!
            val existing = store[id]
            store[id] = if (existing == null) {
                JsonObject((if (hasDetailColumns) detail else emptyMap()) + row)
            } else {
                val stamped = maxOf(row.updatedAt(), System.currentTimeMillis())
                JsonObject(existing + row + ("updated_at" to JsonPrimitive(IsoTime.format(stamped))))
            }
        }
        201 to ""
    }

    /** What migration 0010 does to rows that already exist. */
    fun addDetailColumns() = synchronized(this) {
        hasDetailColumns = true
        detailColumns.forEach { (table, defaults) ->
            val store = tables[table] ?: return@forEach
            store.keys.toList().forEach { store[it] = JsonObject(defaults + store.getValue(it)) }
        }
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.updatedAt(): Long = IsoTime.parseOr(text("updated_at"), 0L)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncFidelityRegressionTest {
    private lateinit var context: Context
    private lateinit var vault: VaultStore
    private lateinit var cloud: FakeCloud
    private val databases = mutableListOf<LedgerDatabase>()

    companion object {
        private val testKeys = ConcurrentHashMap<String, Key>()

        @JvmStatic
        @BeforeClass
        fun installKeyStore() {
            testKeys.clear()
            if (Security.getProvider("AndroidKeyStore") == null) {
                Security.insertProviderAt(RobolectricAndroidKeyStoreProvider(testKeys), 1)
            }
        }

        @JvmStatic
        @AfterClass
        fun removeKeyStore() {
            Security.removeProvider("AndroidKeyStore")
            testKeys.clear()
        }

        private const val INSTALL_A = 1_700_000_000_000L
        private const val INSTALL_B = 1_710_000_000_000L
        private const val CARD_ID = "aaaaaaaa-0000-4000-8000-000000000001"
        private const val LOAN_ID = "aaaaaaaa-0000-4000-8000-000000000002"
        private const val SUB_ID = "bbbbbbbb-0000-4000-8000-000000000001"
        private const val TX_ID = "cccccccc-0000-4000-8000-000000000001"
        private const val WALLET_TX_ID = "cccccccc-0000-4000-8000-000000000002"
    }

    @Before
    fun setup() {
        VaultStore.clearCacheForTests()
        context = ApplicationProvider.getApplicationContext()
        vault = VaultStore(context)
        cloud = FakeCloud()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { net ->
            val caps = ShadowNetworkCapabilities.newInstance()
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            shadowOf(cm).setNetworkCapabilities(net, caps)
        }
    }

    @After
    fun tearDown() {
        cloud.server.stop()
        databases.forEach { it.close() }
        FxConverter.resetToBaselineForTests()
        VaultStore.clearCacheForTests()
    }

    /** A fresh install. Main-thread queries stay disallowed, as they are in the shipped app. */
    private suspend fun install(nowMs: Long): LedgerDatabase {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        databases.add(db)
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = nowMs)
        return db
    }

    private fun signIn(user: String) {
        cloud.activeUser = user
        vault.update { it.copy(userId = user, accessToken = "token-$user") }
    }

    /** Signing in on a new device starts with no sync history. */
    private fun newDevice(user: String) {
        vault.update { it.copy(lastCloudUserId = null, lastSyncWatermarkIso = null) }
        signIn(user)
    }

    private suspend fun sync(db: LedgerDatabase) {
        val url = "http://127.0.0.1:${cloud.server.port}"
        val outcome = LedgerSyncRepository(context, db, vault, SupabaseSyncClient(url, "test-key", "")).syncNow()
        assertTrue("sync failed: $outcome", outcome is SyncOutcome.Success)
    }

    private fun wallet(accounts: List<AccountEntity>) = accounts.single { it.name == "Cash Wallet" }

    private suspend fun addRichData(db: LedgerDatabase) {
        val t = INSTALL_A + 1_000L
        db.accountDao().upsert(AccountEntity(CARD_ID, "Visa", "HDFC", "CREDIT_CARD", "INR", -5_000L, "1111", 900_000L, createdAtEpochMs = t, updatedAtEpochMs = t))
        db.accountDao().upsert(AccountEntity(LOAN_ID, "Car Loan", "SBI", "LOAN", "INR", -70_000L, createdAtEpochMs = t, updatedAtEpochMs = t))
        db.categoryDao().upsert(CategoryEntity(SUB_ID, "Chai", "EXPENSE", parentCategoryId = SeedData.CAT_DINING, createdAtEpochMs = t, updatedAtEpochMs = t, description = "Tea stalls"))
        db.recordTransactionAtomic(
            TransactionEntity(
                id = TX_ID, accountId = CARD_ID, categoryId = SUB_ID, categoryIdsCsv = "$SUB_ID,${SeedData.CAT_DINING}",
                txType = "EXPENSE", amountMinor = -2_000L, currency = "INR", merchant = "Stall", note = "cutting",
                sourceTag = "BANK_SYNC", occurredAtEpochMs = t, createdAtEpochMs = t, updatedAtEpochMs = t,
                spendingPurpose = "Evening break", paymentAppName = "PhonePe", paymentAppPackage = "com.phonepe.app",
                referenceId = "123456789012", notificationKey = "key-1", notificationFingerprint = "fp-1",
                originalParsedAmountMinor = -2_000L,
            )
        )
        val walletId = wallet(db.accountDao().getAllActive()).id
        db.recordTransactionAtomic(
            TransactionEntity(
                id = WALLET_TX_ID, accountId = walletId, categoryId = SeedData.CAT_GENERAL, txType = "INCOME",
                amountMinor = 40_000L, currency = "INR", merchant = "Cash in",
                occurredAtEpochMs = t, createdAtEpochMs = t, updatedAtEpochMs = t,
            )
        )
    }

    private suspend fun assertRichDataIntact(db: LedgerDatabase) {
        val card = db.accountDao().getById(CARD_ID)!!
        assertEquals("CREDIT_CARD", card.accountType)
        assertEquals(900_000L, card.creditLimitMinor)
        assertEquals("LOAN", db.accountDao().getById(LOAN_ID)!!.accountType)
        val sub = db.categoryDao().getById(SUB_ID)!!
        assertEquals(SeedData.CAT_DINING, sub.parentCategoryId)
        assertEquals("Tea stalls", sub.description)
        assertEquals(SeedData.CAT_DINING, db.categoryDao().getById(SeedData.SUB_COFFEE)!!.parentCategoryId)
        val tx = db.transactionDao().getById(TX_ID)!!
        assertEquals("Evening break", tx.spendingPurpose)
        assertEquals("cutting", tx.note)
        assertEquals(SUB_ID, tx.categoryId)
        assertEquals("$SUB_ID,${SeedData.CAT_DINING}", tx.categoryIdsCsv)
    }

    @Test
    fun secondUserCanSyncAlthoughStarterRowsShareIds() = runTest {
        val first = install(INSTALL_A)
        addRichData(first)
        signIn("user-a")
        sync(first)

        val second = install(INSTALL_B)
        addRichData(second)
        // Give the second user's own rows their own random ids, as separate installs would.
        second.transactionDao().deleteAll()
        second.accountDao().deleteAll()
        second.categoryDao().deleteAll()
        SeedData.ensureMinimalSetup(second, defaultCurrency = "INR", nowMs = INSTALL_B)
        val walletId = wallet(second.accountDao().getAllActive()).id
        val ownTx = UUID.randomUUID().toString()
        second.recordTransactionAtomic(
            TransactionEntity(
                id = ownTx, accountId = walletId, categoryId = SeedData.CAT_DINING, txType = "EXPENSE",
                amountMinor = -100L, currency = "INR", merchant = "Own",
                occurredAtEpochMs = INSTALL_B, createdAtEpochMs = INSTALL_B, updatedAtEpochMs = INSTALL_B,
            )
        )
        newDevice("user-b")
        sync(second)

        val walletA = cloud.rows("accounts", "user-a").single { it["name"]!!.jsonPrimitive.content == "Cash Wallet" }
        val walletB = cloud.rows("accounts", "user-b").single()
        assertNotEquals(walletA["id"], walletB["id"])
        val pushedTx = cloud.rows("transactions", "user-b").single()
        assertEquals(walletB["id"], pushedTx["account_id"])
        assertTrue(cloud.rows("categories", "user-b").any { it["id"] == pushedTx["category_id"] })
        // Locally the starter ids are unchanged and nothing was duplicated by the pull.
        assertEquals(walletId, second.accountDao().getAllActive().single().id)
        assertEquals(SeedData.starterCategories(0L).size, second.categoryDao().getAllActive().size)
        assertEquals(walletId, second.transactionDao().getById(ownTx)!!.accountId)
    }

    @Test
    fun syncKeepsLocalDetailOnTheCurrentCloudSchema() = runTest {
        cloud.hasDetailColumns = false
        val db = install(INSTALL_A)
        addRichData(db)
        signIn("user-a")
        sync(db)
        sync(db)
        // An update gets a newer server timestamp, so the next pull really does overwrite the row.
        val card = db.accountDao().getById(CARD_ID)!!
        db.accountDao().upsert(card.copy(name = "Visa Gold", pendingSync = true))
        sync(db)

        assertRichDataIntact(db)
        val tx = db.transactionDao().getById(TX_ID)!!
        assertEquals("PhonePe", tx.paymentAppName)
        assertEquals("123456789012", tx.referenceId)
        assertEquals("key-1", tx.notificationKey)
        assertEquals("fp-1", tx.notificationFingerprint)
        // Without detail columns the purpose still reaches the cloud inside the note.
        assertTrue(cloud.rows("transactions").any { it["note"]!!.jsonPrimitive.content == "Evening break • cutting" })
        assertTrue(vault.read().cloudDetailColumnsMissing)
    }

    @Test
    fun newDeviceRestoresDetailAndIsNotOverwrittenByStarterRows() = runTest {
        val first = install(INSTALL_A)
        addRichData(first)
        val dining = first.categoryDao().getById(SeedData.CAT_DINING)!!
        first.categoryDao().upsert(dining.copy(name = "Eating Out", updatedAtEpochMs = INSTALL_A + 5_000L, pendingSync = true))
        signIn("user-a")
        sync(first)

        val second = install(System.currentTimeMillis())
        newDevice("user-a")
        sync(second)

        assertRichDataIntact(second)
        assertEquals("Eating Out", second.categoryDao().getById(SeedData.CAT_DINING)!!.name)
        assertEquals(40_000L, wallet(second.accountDao().getAllActive()).balanceMinor)
        assertEquals(40_000L, cloud.rows("accounts").single { it["name"]!!.jsonPrimitive.content == "Cash Wallet" }["balance_minor"]!!.jsonPrimitive.content.toLong())
        assertTrue(first.transactionDao().getById(TX_ID)!!.referenceId.isNotBlank())
    }

    @Test
    fun newDeviceKeepsStarterTreeAndCloudBalanceOnTheCurrentCloudSchema() = runTest {
        cloud.hasDetailColumns = false
        val first = install(INSTALL_A)
        addRichData(first)
        signIn("user-a")
        sync(first)

        val second = install(System.currentTimeMillis())
        newDevice("user-a")
        sync(second)

        assertEquals(40_000L, wallet(second.accountDao().getAllActive()).balanceMinor)
        assertEquals(SeedData.CAT_DINING, second.categoryDao().getById(SeedData.SUB_COFFEE)!!.parentCategoryId)
    }

    @Test
    fun detailIsUploadedOnceTheCloudSchemaGainsTheColumns() = runTest {
        cloud.hasDetailColumns = false
        val db = install(INSTALL_A)
        addRichData(db)
        signIn("user-a")
        sync(db)

        cloud.addDetailColumns()
        // Any later edit reveals that the columns now exist.
        val loan = db.accountDao().getById(LOAN_ID)!!
        db.accountDao().upsert(loan.copy(name = "Car Loan 2", updatedAtEpochMs = System.currentTimeMillis(), pendingSync = true))
        sync(db)

        assertTrue(cloud.rows("accounts").any { it["credit_limit_minor"]!!.jsonPrimitive.content == "900000" })
        assertTrue(cloud.rows("transactions").any { it["spending_purpose"]!!.jsonPrimitive.content == "Evening break" })
        assertTrue(cloud.rows("categories").any { it["description"]!!.jsonPrimitive.content == "Tea stalls" })
        assertEquals(false, vault.read().cloudDetailColumnsMissing)
        sync(db)
        assertRichDataIntact(db)
    }

    @Test
    fun switchingCloudAccountReplacesLocalDataWithoutFailing() = runTest {
        val db = install(INSTALL_A)
        addRichData(db)
        signIn("user-a")
        sync(db)

        signIn("user-b")
        sync(db)

        assertEquals(null, db.transactionDao().getById(TX_ID))
        assertEquals(0L, db.accountDao().getAllActive().single().balanceMinor)
        assertEquals(SeedData.starterCategories(0L).size, cloud.rows("categories", "user-b").size)
    }

    @Test
    fun jsonBackupRoundTripKeepsLimitsPurposePaymentDetailAndPendingStatus() = runTest {
        val db = install(INSTALL_A)
        addRichData(db)
        db.recordTransactionAtomic(
            TransactionEntity(
                id = "pending-1", accountId = TransactionEntity.UNASSIGNED_ACCOUNT_ID, txType = "EXPENSE",
                amountMinor = -300L, currency = "INR", merchant = "Unknown",
                occurredAtEpochMs = INSTALL_A, createdAtEpochMs = INSTALL_A, updatedAtEpochMs = INSTALL_A,
                reviewStatus = TransactionEntity.STATUS_PENDING_REVIEW,
            )
        )
        val before = db.transactionDao().getById(TX_ID)!!
        val repo = LedgerSyncRepository(context, db, vault)
        val json = repo.exportVaultJson()
        db.resetAllDataFresh(defaultCurrency = "INR")
        repo.importExternalDataOffline(json.toByteArray())

        assertRichDataIntact(db)
        assertEquals(before.copy(pendingSync = true), db.transactionDao().getById(TX_ID))
        assertEquals(TransactionEntity.STATUS_PENDING_REVIEW, db.transactionDao().getById("pending-1")!!.reviewStatus)
    }

    @Test
    fun backupFromAnEarlierReleaseStillRestores() = runTest {
        val db = install(INSTALL_A)
        val stamp = "2026-11-27T10:00:00Z"
        val old = """
            {"exportedAt":"$stamp","baseCurrency":"INR",
             "accounts":[{"id":"$CARD_ID","user_id":"x","name":"Visa","account_type":"CREDIT_CARD","currency":"INR","balance_minor":-5,"created_at":"$stamp","updated_at":"$stamp"}],
             "categories":[{"id":"$SUB_ID","user_id":"x","name":"Chai","kind":"EXPENSE","icon_key":"cafe","color_hex":"#000000","created_at":"$stamp","updated_at":"$stamp"}],
             "transactions":[{"id":"$TX_ID","user_id":"x","account_id":"","tx_type":"EXPENSE","amount_minor":-5,"currency":"INR","merchant":"M","occurred_at":"$stamp","created_at":"$stamp","updated_at":"$stamp"}],
             "budgets":[],"plannedPayments":[],"holdings":[],"magicRules":[],
             "categoryHierarchy":[{"id":"$SUB_ID","parentCategoryId":"${SeedData.CAT_DINING}","description":"Tea"}]}
        """.trimIndent()
        LedgerSyncRepository(context, db, vault).importExternalDataOffline(old.toByteArray())

        assertEquals("CREDIT_CARD", db.accountDao().getById(CARD_ID)!!.accountType)
        assertEquals(SeedData.CAT_DINING, db.categoryDao().getById(SUB_ID)!!.parentCategoryId)
        assertEquals("Tea", db.categoryDao().getById(SUB_ID)!!.description)
        assertEquals(TransactionEntity.STATUS_PENDING_REVIEW, db.transactionDao().getById(TX_ID)!!.reviewStatus)
    }

    private fun payment(id: String, atMs: Long, amount: Long = -2_000L, reference: String = "", merchant: String = "Chai Point") =
        TransactionEntity(
            id = id, accountId = CARD_ID, txType = "EXPENSE", amountMinor = amount, currency = "INR", merchant = merchant,
            occurredAtEpochMs = atMs, createdAtEpochMs = atMs, updatedAtEpochMs = atMs,
            reviewStatus = TransactionEntity.STATUS_PENDING_REVIEW, referenceId = reference,
            notificationKey = "reused-key", notificationFingerprint = "fp-$id", originalParsedAmountMinor = amount,
        )

    private suspend fun cardDb(): LedgerDatabase {
        val db = install(INSTALL_A)
        db.accountDao().upsert(AccountEntity(CARD_ID, "Visa", accountType = "CHECKING", currency = "INR", balanceMinor = 100_000L, createdAtEpochMs = 1L, updatedAtEpochMs = 1L))
        return db
    }

    @Test
    fun paymentsReusingANotificationKeyAreAllRecorded() = runTest {
        val db = cardDb()
        val start = INSTALL_A
        val minute = 60_000L
        db.upsertDetectedPaymentAtomic(payment("p1", start))
        // Same notification reposted moments later: still one payment.
        db.upsertDetectedPaymentAtomic(payment("p1-again", start + minute))
        // A different amount, a later identical one, and an identical one with another reference.
        db.upsertDetectedPaymentAtomic(payment("p2", start + 2 * minute, amount = -3_500L))
        db.upsertDetectedPaymentAtomic(payment("p3", start + 60 * minute))
        db.upsertDetectedPaymentAtomic(payment("p4", start + 90 * minute, reference = "REF0000001"))
        db.upsertDetectedPaymentAtomic(payment("p5", start + 91 * minute, reference = "REF0000002"))

        assertEquals(setOf("p1", "p2", "p3", "p4", "p5"), db.transactionDao().getPendingReview().map { it.id }.toSet())
        assertEquals(100_000L - 2_000L - 3_500L - 2_000L - 2_000L - 2_000L, db.accountDao().getById(CARD_ID)!!.balanceMinor)
    }

    @Test
    fun confirmedOrDismissedPaymentDoesNotSwallowTheNextOne() = runTest {
        val db = cardDb()
        db.upsertDetectedPaymentAtomic(payment("p1", INSTALL_A))
        db.softDeleteTransactionAtomic("p1")
        db.upsertDetectedPaymentAtomic(payment("p2", INSTALL_A + 3_600_000L))
        db.transactionDao().upsert(db.transactionDao().getById("p2")!!.copy(reviewStatus = TransactionEntity.STATUS_CONFIRMED))
        db.upsertDetectedPaymentAtomic(payment("p3", INSTALL_A + 7_200_000L))

        assertEquals(listOf("p3"), db.transactionDao().getPendingReview().map { it.id })
        assertEquals(96_000L, db.accountDao().getById(CARD_ID)!!.balanceMinor)
    }

    @Test
    fun failureNoticeOnlyCancelsThePaymentItRefersTo() = runTest {
        val db = cardDb()
        db.upsertDetectedPaymentAtomic(payment("paid", INSTALL_A, reference = "REF0000001"))

        // Another payment failing under the same key, by reference or long afterwards.
        assertEquals(false, db.handleDetectedFailureNotificationAtomic("reused-key", "REF0000002", INSTALL_A + 60_000L))
        assertEquals(false, db.handleDetectedFailureNotificationAtomic("reused-key", "", INSTALL_A + 3_600_000L))
        assertEquals(98_000L, db.accountDao().getById(CARD_ID)!!.balanceMinor)

        assertTrue(db.handleDetectedFailureNotificationAtomic("reused-key", "REF0000001", INSTALL_A + 3_600_000L))
        assertEquals(100_000L, db.accountDao().getById(CARD_ID)!!.balanceMinor)
    }

    @Test
    fun yenConvertsImportsAndExportsWithoutAHundredfoldError() = runTest {
        // Baseline: 154.2 yen to the dollar. Yen are whole units; dollars are stored in cents.
        assertEquals(1_000L, FxConverter.convertMinor(1_542L, "JPY", "USD"))
        assertEquals(1_542L, FxConverter.convertMinor(1_000L, "USD", "JPY"))
        assertEquals(-1_000L, FxConverter.convertMinor(-1_542L, "JPY", "USD"))

        val db = install(INSTALL_A)
        val repo = LedgerSyncRepository(context, db, vault)
        val csv = "Date,Merchant,Amount,Currency\n2026-11-20,Ramen,-1500,JPY\n2026-11-20,Tea,¥300,\n2026-11-20,Book,-12.50,USD"
        repo.importExternalDataOffline(csv.toByteArray(), defaultCurrency = "USD")
        repo.importExternalDataOffline("""[{"title":"Train","amount":220,"type":"expense"}]""".toByteArray(), defaultCurrency = "JPY")

        val amounts = db.transactionDao().getAllActive().associate { it.merchant to it.amountMinor }
        assertEquals(-1_500L, amounts["Ramen"])
        assertEquals(300L, amounts["Tea"])
        assertEquals(-1_250L, amounts["Book"])
        assertEquals(-220L, amounts["Train"])
        val exported = repo.exportLedgerCsv()
        assertTrue(exported.contains(",-1500,\"JPY\""))
        assertTrue(exported.contains(",-12.50,\"USD\""))
    }
}
