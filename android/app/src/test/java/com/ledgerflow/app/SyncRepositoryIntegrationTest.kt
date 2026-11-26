package com.ledgerflow.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.SeedData
import com.ledgerflow.app.data.remote.IsoTime
import com.ledgerflow.app.data.remote.SupabaseSyncClient
import com.ledgerflow.app.data.sync.LedgerSyncRepository
import com.ledgerflow.app.data.sync.SyncOutcome
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.service.PayAndTrackCapture
import com.ledgerflow.app.util.NetworkMonitor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.Key
import java.security.Security
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private class LocalLoopbackHttpServer(
    private val handler: (method: String, pathAndQuery: String) -> Pair<Int, String>,
) {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val port: Int get() = serverSocket.localPort

    private val acceptThread = thread(isDaemon = true, name = "test-http-loopback") {
        while (running.get()) {
            val client = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break
            }
            thread(isDaemon = true) {
                client.use { sock ->
                    val reader = BufferedInputStream(sock.getInputStream())
                    val headerLines = mutableListOf<String>()
                    val lineBuf = StringBuilder()
                    var prevCr = false
                    while (true) {
                        val b = reader.read()
                        if (b == -1) break
                        if (b == '\n'.code && prevCr) {
                            val line = lineBuf.toString().trimEnd('\r')
                            lineBuf.clear()
                            if (line.isEmpty()) break
                            headerLines.add(line)
                            prevCr = false
                        } else {
                            prevCr = (b == '\r'.code)
                            if (b != '\r'.code) lineBuf.append(b.toChar())
                        }
                    }
                    val reqLine = headerLines.firstOrNull().orEmpty().split(" ")
                    val method = reqLine.getOrElse(0) { "GET" }
                    val target = reqLine.getOrElse(1) { "/" }
                    val contentLength = headerLines
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.toIntOrNull() ?: 0
                    var remaining = contentLength
                    val sink = ByteArray(1024)
                    while (remaining > 0) {
                        val read = reader.read(sink, 0, minOf(remaining, sink.size))
                        if (read <= 0) break
                        remaining -= read
                    }

                    val (status, body) = handler(method, target)
                    val bodyBytes = body.toByteArray(Charsets.UTF_8)
                    val out = sock.getOutputStream()
                    val head = (
                        "HTTP/1.1 $status OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bodyBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                    out.write(head)
                    out.write(bodyBytes)
                    out.flush()
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket.close() }
        runCatching { acceptThread.join(500) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncRepositoryIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: LedgerDatabase
    private lateinit var vault: VaultStore
    private var server: LocalLoopbackHttpServer? = null

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
    }

    @Before
    fun setup() {
        VaultStore.clearCacheForTests()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vault = VaultStore(context)
        markOnline(true)
    }

    @After
    fun tearDown() {
        server?.stop()
        db.close()
        VaultStore.clearCacheForTests()
    }

    private fun markOnline(online: Boolean) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { activeNet ->
            val caps = ShadowNetworkCapabilities.newInstance()
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (online) {
                shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            shadowOf(cm).setNetworkCapabilities(activeNet, caps)
        }
    }

    @Test
    fun syncNow_preservesNewerLocalDirtyEditAndAppliesNewerRemoteWithoutAdvanceOnFailure() = runTest {
        val localDirtyId = UUID.randomUUID().toString()
        val remoteWinId = UUID.randomUUID().toString()

        db.accountDao().upsertAll(
            listOf(
                AccountEntity(
                    id = localDirtyId,
                    name = "Local Newer Dirty",
                    currency = "USD",
                    balanceMinor = 55_000L,
                    createdAtEpochMs = 1_700_000_000_000L,
                    updatedAtEpochMs = 1_700_000_050_000L,
                    pendingSync = true,
                ),
                AccountEntity(
                    id = remoteWinId,
                    name = "Local Older Clean",
                    currency = "USD",
                    balanceMinor = 10_000L,
                    createdAtEpochMs = 1_700_000_000_000L,
                    updatedAtEpochMs = 1_700_000_010_000L,
                    pendingSync = false,
                ),
            )
        )

        vault.update {
            it.copy(
                userId = "user-a",
                accessToken = "expired-token",
                refreshToken = "refresh-ok",
                lastSyncWatermarkIso = "2023-11-14T22:13:20Z",
            )
        }

        val pullCount = AtomicInteger(0)
        val srv = LocalLoopbackHttpServer { method, path ->
            when {
                path.startsWith("/auth/v1/token") -> {
                    200 to """{"access_token":"fresh-token","refresh_token":"refresh-2","user":{"id":"user-a","email":"a@test.com"}}"""
                }
                path.startsWith("/rest/v1/accounts") -> {
                    if (method == "POST") {
                        201 to ""
                    } else if (pullCount.getAndIncrement() == 0) {
                        401 to ""
                    } else {
                        200 to """
                            [
                              {"id":"$localDirtyId","user_id":"user-a","name":"Stale Remote Name","institution":"","account_type":"CHECKING","currency":"USD","balance_minor":100,"last_four":"","source_type":"MANUAL","sync_status":"OK","last_synced_at":null,"created_at":"2023-11-14T22:13:20Z","updated_at":"2023-11-14T22:13:30Z","deleted_at":null},
                              {"id":"$remoteWinId","user_id":"user-a","name":"Remote Updated Name","institution":"Bank","account_type":"CHECKING","currency":"USD","balance_minor":99900,"last_four":"1234","source_type":"MANUAL","sync_status":"OK","last_synced_at":null,"created_at":"2023-11-14T22:13:20Z","updated_at":"2023-11-14T22:15:00Z","deleted_at":null}
                            ]
                        """.trimIndent()
                    }
                }
                path.startsWith("/rest/v1/") -> {
                    if (method == "POST") 201 to "" else 200 to "[]"
                }
                else -> 404 to "{}"
            }
        }
        server = srv

        val remote = SupabaseSyncClient(
            baseUrl = "http://127.0.0.1:${srv.port}",
            publishableKey = "test-publishable-key",
            renderUrl = "http://127.0.0.1:${srv.port}",
        )
        val repo = LedgerSyncRepository(context, db, vault, remote)
        val outcome = repo.syncNow()
        assertTrue(outcome is SyncOutcome.Success)

        val localKept = db.accountDao().getById(localDirtyId)!!
        assertEquals("Local Newer Dirty", localKept.name)

        val remoteApplied = db.accountDao().getById(remoteWinId)!!
        assertEquals("Remote Updated Name", remoteApplied.name)
        assertEquals(99_900L, remoteApplied.balanceMinor)
        assertEquals("fresh-token", vault.read().accessToken)
        assertEquals(
            IsoTime.parseOr("2023-11-14T22:15:00Z", 0L),
            IsoTime.parseOr(vault.read().lastSyncWatermarkIso, 0L),
        )
    }

    @Test
    fun syncNow_pullFailure500_doesNotAdvanceWatermark() = runTest {
        val initialWatermark = "2023-11-14T22:13:20Z"
        vault.update {
            it.copy(
                userId = "user-b",
                accessToken = "valid-token",
                lastSyncWatermarkIso = initialWatermark,
            )
        }
        val srv = LocalLoopbackHttpServer { _, _ -> 500 to "" }
        server = srv

        val remote = SupabaseSyncClient(
            baseUrl = "http://127.0.0.1:${srv.port}",
            publishableKey = "test-publishable-key",
            renderUrl = "http://127.0.0.1:${srv.port}",
        )
        val repo = LedgerSyncRepository(context, db, vault, remote)
        val outcome = repo.syncNow()
        assertTrue(outcome is SyncOutcome.Error)
        assertEquals(initialWatermark, vault.read().lastSyncWatermarkIso)
    }

    @Test
    fun importCsvOffline_handlesNegativeParensAtomicallyAndDeduplicatesPayAndTrack() = runTest {
        SeedData.ensureMinimalSetup(db, nowMs = 1_700_000_000_000L)
        val checking = db.accountDao().getAllActive().first()
        val beforeBal = checking.balanceMinor

        val repo = LedgerSyncRepository(context, db, vault)
        val csv = """
            Merchant,Amount,Currency,Note
            City Parking,(12.50),USD,Garage fee
            Side Hustle,100.00,USD,Bonus
        """.trimIndent()
        val count = repo.importCsvOffline(csv, checking.id, "USD")
        assertEquals(2, count)
        assertEquals(beforeBal - 1250L + 10_000L, db.accountDao().getById(checking.id)!!.balanceMinor)

        markOnline(false)
        assertFalse(NetworkMonitor.isOnline(context))

        val appDb = LedgerDatabase.getInstance(context)
        SeedData.ensureMinimalSetup(appDb, nowMs = 1_700_000_000_000L)
        val firstCapture = PayAndTrackCapture.capturePayment(
            context = context,
            title = "Paid \$9.50 at Peet's Coffee",
            text = "Google Pay",
            manualEntry = false,
        )
        val duplicateCapture = PayAndTrackCapture.capturePayment(
            context = context,
            title = "Paid \$9.50 at Peet's Coffee",
            text = "Google Pay",
            manualEntry = false,
        )
        assertTrue(firstCapture)
        assertFalse(duplicateCapture)
    }

    @Test
    fun syncRepository_restoresPersistedFxRatesIntoConverter() {
        try {
            vault.update {
                it.copy(
                    cachedFxRatesJson = """{"USD":1.0,"EUR":0.90,"INR":90.0}""",
                    cachedFxDate = "2026-11-25",
                )
            }
            val repo = LedgerSyncRepository(context, db, vault)
            repo.restoreCachedFxRates()
            assertEquals(9000L, com.ledgerflow.app.domain.FxConverter.convertMinor(10_000L, "USD", "EUR"))
            assertEquals("ECB (2026-11-25)", com.ledgerflow.app.domain.FxConverter.lastRatesSource)
        } finally {
            com.ledgerflow.app.domain.FxConverter.resetToBaselineForTests()
        }
    }

    @Test
    fun importParser_handlesExternalCurrencyPrefixesSplitDebitCreditQuotesAndUtf16() {
        val rsAmount = com.ledgerflow.app.domain.ImportParser.parseAmountCell("Rs. 500.00")
        assertEquals(50_000L, rsAmount?.minor)
        assertEquals("INR", rsAmount?.currencyHint)

        val euroAmount = com.ledgerflow.app.domain.ImportParser.parseAmountCell("€ 1.234,56")
        assertEquals(123_456L, euroAmount?.minor)
        assertEquals("EUR", euroAmount?.currencyHint)

        val bankCsv = """
            HDFC Account Statement
            Account Number,XXXX1234

            Date,Narration,Withdrawal Amt.,Deposit Amt.,Currency,Note
            2026-11-20,"SWIGGY ORDER
            DINNER",Rs. 450.50,,INR,"Quoted
            multiline note"
            2026-11-21,SALARY CREDIT,,25000.00,INR,Monthly Pay
        """.trimIndent()
        val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + bankCsv.toByteArray(Charsets.UTF_16LE)
        val decoded = com.ledgerflow.app.domain.ImportParser.decodeTextBytes(utf16LeBom)
        val parsed = com.ledgerflow.app.domain.ImportParser.parseTransactionsCsvOrJson(decoded, defaultCurrency = "INR")
        assertTrue(parsed is com.ledgerflow.app.domain.ParsedImportResult.Transactions)
        val drafts = (parsed as com.ledgerflow.app.domain.ParsedImportResult.Transactions).drafts
        assertEquals(2, drafts.size)
        assertEquals("EXPENSE", drafts[0].txType)
        assertEquals(-45_050L, drafts[0].amountMinor)
        assertEquals("INR", drafts[0].currency)
        assertEquals("INCOME", drafts[1].txType)
        assertEquals(2_500_000L, drafts[1].amountMinor)
    }

    @Test
    fun importExternalDataOffline_importsThirdPartyAppCsvJsonRestoresVaultBackupAndRejectsBinary() = runTest {
        SeedData.ensureMinimalSetup(db, defaultCurrency = "INR", nowMs = 1_700_000_000_000L)
        val repo = LedgerSyncRepository(context, db, vault)

        // Third-party finance app CSV with positive amounts + Type column
        val thirdPartyCsv = """
            Date;Category;Title;Type;Amount;Note
            2026-11-18;Groceries;BigBasket;Expense;₹840.25;Weekly veggies
            2026-11-19;Income;Freelance Payout;Income;₹5000;Client invoice
        """.trimIndent()
        val csvOutcome = repo.importExternalDataOffline(thirdPartyCsv.toByteArray(Charsets.UTF_8), defaultCurrency = "INR")
        assertEquals(2, csvOutcome.importedTransactions)
        val walletAfterCsv = db.accountDao().getAllActive().first()
        assertEquals(-84_025L + 500_000L, walletAfterCsv.balanceMinor)

        // Generic JSON array export from another app
        val genericJson = """
            {
              "transactions": [
                {"title": "Metro Card", "amount": 120.0, "type": "expense", "currency": "INR", "date": "2026-11-22"}
              ]
            }
        """.trimIndent()
        val jsonOutcome = repo.importExternalDataOffline(genericJson.toByteArray(Charsets.UTF_8), defaultCurrency = "INR")
        assertEquals(1, jsonOutcome.importedTransactions)
        assertEquals(-84_025L + 500_000L - 12_000L, db.accountDao().getAllActive().first().balanceMinor)

        // Binary PDF rejection (no crash)
        val pdfBytes = "%PDF-1.7\n%...".toByteArray(Charsets.US_ASCII)
        val pdfOutcome = repo.importExternalDataOffline(pdfBytes, defaultCurrency = "INR")
        assertEquals(0, pdfOutcome.importedTransactions)
        assertTrue(pdfOutcome.message.contains("PDF", ignoreCase = true))

        // Full LedgerVaultBackup JSON export -> fresh DB -> restore round-trip
        val backupJson = repo.exportVaultJson()
        db.resetAllDataFresh(defaultCurrency = "INR")
        val restoreOutcome = repo.importExternalDataOffline(backupJson.toByteArray(Charsets.UTF_8), defaultCurrency = "INR")
        assertEquals(3, restoreOutcome.importedTransactions)
        assertEquals(-84_025L + 500_000L - 12_000L, db.accountDao().getAllActive().first().balanceMinor)
    }
}
