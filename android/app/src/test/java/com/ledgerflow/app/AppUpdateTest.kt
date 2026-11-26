package com.ledgerflow.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import androidx.test.core.app.ApplicationProvider
import com.ledgerflow.app.domain.AppUpdatePolicy
import com.ledgerflow.app.domain.ArchiveTrustResult
import com.ledgerflow.app.domain.EligibilityResult
import com.ledgerflow.app.domain.UpdateManifest
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.update.AppReplacedReceiver
import com.ledgerflow.app.update.AppUpdateManager
import com.ledgerflow.app.update.UpdateCheckOutcome
import com.ledgerflow.app.update.UpdateEventBus
import com.ledgerflow.app.update.UpdateInstallEvent
import com.ledgerflow.app.update.UpdateInstallStatusReceiver
import com.ledgerflow.app.util.NetworkMonitor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

class AppUpdatePolicyTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val trustedCert = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val forgedCert = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    private val validSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun sampleManifest(
        versionCode: Long = 2L,
        apkUrl: String = "https://releases.ledgerflow.app/ledgerflow-v2.apk",
        sha256: String = validSha256,
        signingCertSha256: String = trustedCert,
        mandatory: Boolean = false,
    ) = UpdateManifest(
        versionCode = versionCode,
        versionName = "1.1.0",
        apkUrl = apkUrl,
        apkSizeBytes = 4_231_266L,
        sha256 = sha256,
        signingCertSha256 = signingCertSha256,
        releaseNotes = listOf("Improved sync reliability"),
        mandatory = mandatory,
    )

    @Test
    fun evaluateEligibility_enforcesHttpsSha256VersionAndLaterSnooze() {
        val eligible = AppUpdatePolicy.evaluateEligibility(
            manifest = sampleManifest(),
            installedVersionCode = 1L,
            installedCertDigests = setOf(trustedCert),
            dismissedVersionCode = 0L,
            ignoreDismissed = false,
        )
        assertTrue(eligible is EligibilityResult.Available)

        val snoozed = AppUpdatePolicy.evaluateEligibility(
            manifest = sampleManifest(versionCode = 2L),
            installedVersionCode = 1L,
            installedCertDigests = setOf(trustedCert),
            dismissedVersionCode = 2L,
            ignoreDismissed = false,
        )
        assertTrue(snoozed is EligibilityResult.Dismissed)

        val manualOverride = AppUpdatePolicy.evaluateEligibility(
            manifest = sampleManifest(versionCode = 2L),
            installedVersionCode = 1L,
            installedCertDigests = setOf(trustedCert),
            dismissedVersionCode = 2L,
            ignoreDismissed = true,
        )
        assertTrue(manualOverride is EligibilityResult.Available)

        val downgrade = AppUpdatePolicy.evaluateEligibility(
            manifest = sampleManifest(versionCode = 1L),
            installedVersionCode = 2L,
            installedCertDigests = setOf(trustedCert),
            dismissedVersionCode = 0L,
            ignoreDismissed = true,
        )
        assertTrue(downgrade is EligibilityResult.UpToDate)

        val cleartextRejected = AppUpdatePolicy.evaluateEligibility(
            manifest = sampleManifest(apkUrl = "http://evil.example.com/update.apk"),
            installedVersionCode = 1L,
            installedCertDigests = setOf(trustedCert),
            dismissedVersionCode = 0L,
            ignoreDismissed = true,
            allowCleartextLocal = false,
        )
        assertTrue(cleartextRejected is EligibilityResult.Rejected)

        val certMismatchRejected = AppUpdatePolicy.evaluateEligibility(
            manifest = sampleManifest(signingCertSha256 = forgedCert),
            installedVersionCode = 1L,
            installedCertDigests = setOf(trustedCert),
            dismissedVersionCode = 0L,
            ignoreDismissed = true,
        )
        assertTrue(certMismatchRejected is EligibilityResult.Rejected)
    }

    @Test
    fun verifyArchiveTrust_blocksForgedSignatureDowngradeAndPackageSwap() {
        val m = sampleManifest(versionCode = 3L, signingCertSha256 = trustedCert)

        val trusted = AppUpdatePolicy.verifyArchiveTrust(
            archivePackageName = "com.ledgerflow.app",
            archiveVersionCode = 3L,
            archiveCertDigests = setOf(trustedCert.uppercase()),
            expectedPackageName = "com.ledgerflow.app",
            installedVersionCode = 1L,
            installedCertDigests = setOf(trustedCert),
            manifest = m,
        )
        assertTrue(trusted is ArchiveTrustResult.Trusted)

        // Attacker swaps BOTH manifest cert field AND APK signature -> still blocked by installed binary signing key!
        val compromisedManifest = sampleManifest(versionCode = 3L, signingCertSha256 = forgedCert)
        val blockedForged = AppUpdatePolicy.verifyArchiveTrust(
            archivePackageName = "com.ledgerflow.app",
            archiveVersionCode = 3L,
            archiveCertDigests = setOf(forgedCert),
            expectedPackageName = "com.ledgerflow.app",
            installedVersionCode = 1L,
            installedCertDigests = setOf(trustedCert),
            manifest = compromisedManifest,
        )
        assertTrue(blockedForged is ArchiveTrustResult.Rejected)

        val blockedPackageSwap = AppUpdatePolicy.verifyArchiveTrust(
            archivePackageName = "com.evil.clone",
            archiveVersionCode = 3L,
            archiveCertDigests = setOf(trustedCert),
            expectedPackageName = "com.ledgerflow.app",
            installedVersionCode = 1L,
            installedCertDigests = setOf(trustedCert),
            manifest = m,
        )
        assertTrue(blockedPackageSwap is ArchiveTrustResult.Rejected)

        val blockedDowngrade = AppUpdatePolicy.verifyArchiveTrust(
            archivePackageName = "com.ledgerflow.app",
            archiveVersionCode = 1L,
            archiveCertDigests = setOf(trustedCert),
            expectedPackageName = "com.ledgerflow.app",
            installedVersionCode = 2L,
            installedCertDigests = setOf(trustedCert),
            manifest = m,
        )
        assertTrue(blockedDowngrade is ArchiveTrustResult.Rejected)
    }

    @Test
    fun verifyFileSha256_detectsValidAndCorruptedBytes() {
        val file = tempFolder.newFile("sample-update.apk")
        val payload = "ledgerflow-verified-apk-binary".encodeToByteArray()
        file.writeBytes(payload)
        val expected = AppUpdatePolicy.sha256Hex(payload)

        assertTrue(AppUpdatePolicy.verifyFileSha256(file, expected))
        assertFalse(AppUpdatePolicy.verifyFileSha256(file, "f".repeat(64)))
        assertFalse(AppUpdatePolicy.verifyFileSha256(file, "not-hex"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateRobolectricTest {

    companion object {
        private val testKeys = ConcurrentHashMap<String, Key>()

        @JvmStatic
        @BeforeClass
        fun installTestKeyStoreProvider() {
            testKeys.clear()
            if (Security.getProvider("AndroidKeyStore") == null) {
                Security.insertProviderAt(RobolectricAndroidKeyStoreProvider(testKeys), 1)
            }
        }

        @JvmStatic
        @AfterClass
        fun removeTestKeyStoreProvider() {
            Security.removeProvider("AndroidKeyStore")
            testKeys.clear()
        }
    }

    @Test
    fun appReplacedReceiver_relaunchesOnlyWhenPendingAndCleansCache() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val app = context as Application
        val vault = VaultStore(context)

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val staleApk = File(updatesDir, "ledgerflow-v2.apk").apply { writeText("apk") }
        assertTrue(staleApk.exists())

        vault.update {
            it.copy(
                pendingUpdateRelaunch = true,
                pendingUpdateVersionName = "1.2.0",
                dismissedUpdateVersionCode = 2L,
            )
        }

        val receiver = AppReplacedReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val after = vault.read()
        assertFalse(after.pendingUpdateRelaunch)
        assertNull(after.pendingUpdateVersionName)
        assertEquals("1.2.0", after.justUpdatedVersionName)
        assertEquals(0L, after.dismissedUpdateVersionCode)
        assertFalse(staleApk.exists())

        val launched = shadowOf(app).nextStartedActivity
        assertNotNull(launched)
        assertTrue(launched!!.getBooleanExtra(AppReplacedReceiver.EXTRA_JUST_UPDATED, false))

        // Second MY_PACKAGE_REPLACED without pending flag must NOT auto-launch
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun updateInstallStatusReceiver_clearsPendingFlagOnCancelOrFailure() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val vault = VaultStore(context)
        vault.update { it.copy(pendingUpdateRelaunch = true, pendingUpdateVersionName = "1.2.0") }

        val intent = Intent(context, UpdateInstallStatusReceiver::class.java).apply {
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
        }
        UpdateInstallStatusReceiver().onReceive(context, intent)

        assertFalse(vault.read().pendingUpdateRelaunch)
        assertNull(vault.read().pendingUpdateVersionName)
    }

    @Test
    fun checkForUpdate_offline_returnsOfflineWithoutInterruptingUser() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { activeNet ->
            val unvalidated = ShadowNetworkCapabilities.newInstance()
            shadowOf(unvalidated).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(cm).setNetworkCapabilities(activeNet, unvalidated)
        }
        assertFalse(NetworkMonitor.isOnline(context))

        val updater = AppUpdateManager(context, VaultStore(context))
        val outcome = updater.checkForUpdate(ignoreDismissed = false)
        assertEquals(UpdateCheckOutcome.Offline, outcome)
    }

    @Test
    fun cachedManifest_respectsLaterDismissalAcrossRestarts() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val vault = VaultStore(context)
        val json = Json { encodeDefaults = true }
        val manifest = UpdateManifest(
            versionCode = 99L,
            versionName = "1.9.9",
            apkUrl = "https://releases.ledgerflow.app/v99.apk",
            apkSizeBytes = 4_200_000L,
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            releaseNotes = listOf("Automatic updater"),
        )
        vault.update {
            it.copy(
                cachedUpdateJson = json.encodeToString(UpdateManifest.serializer(), manifest),
                dismissedUpdateVersionCode = 0L,
            )
        }

        val updater = AppUpdateManager(context, vault)
        assertEquals(99L, updater.loadCachedEligibleManifest(ignoreDismissed = false)?.versionCode)

        vault.update { it.copy(dismissedUpdateVersionCode = 99L) }
        assertNull(updater.loadCachedEligibleManifest(ignoreDismissed = false))
        assertEquals(99L, updater.loadCachedEligibleManifest(ignoreDismissed = true)?.versionCode)
    }

    @Test
    fun shouldCheckSilently_checksOncePerSessionAndRespectsCooldown() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { activeNet ->
            val validated = ShadowNetworkCapabilities.newInstance()
            shadowOf(validated).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(validated).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            shadowOf(cm).setNetworkCapabilities(activeNet, validated)
        }

        val manifestJson = Json.encodeToString(
            UpdateManifest.serializer(),
            UpdateManifest(
                versionCode = 1L,
                versionName = "1.0.0",
                apkUrl = "https://github.com/itripathiharsh/LedgerFlow/releases/download/v1.0.0/LedgerFlow-v1.0.0.apk",
                apkSizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ),
        )
        val mockClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        okhttp3.ResponseBody.create(
                            null,
                            manifestJson.toByteArray(),
                        ),
                    )
                    .build()
            }
            .build()

        val updater = AppUpdateManager(context, VaultStore(context), mockClient)
        val now = 2_000_000_000L
        org.junit.Assert.assertTrue(updater.shouldCheckSilently(now - 60_000L, now))

        updater.checkForUpdate(ignoreDismissed = false)

        assertFalse(updater.shouldCheckSilently(now - 60_000L, now))
        org.junit.Assert.assertTrue(
            updater.shouldCheckSilently(now - AppUpdateManager.MIN_BACKGROUND_CHECK_INTERVAL_MS, now),
        )
    }

    @Test
    fun appReplacedReceiver_clearsPendingResumeAndRelaunchState() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val vault = VaultStore(context)
        vault.update {
            it.copy(
                pendingUpdateRelaunch = true,
                pendingInstallResumeVersionCode = 5L,
            )
        }

        com.ledgerflow.app.update.AppReplacedReceiver().onReceive(
            context,
            android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED),
        )

        val updated = vault.read()
        assertFalse(updated.pendingUpdateRelaunch)
        assertEquals(0L, updated.pendingInstallResumeVersionCode)
    }

    @Test
    fun unknownSourcesSettingsIntents_providesOrderedFallbacks() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val intents = AppUpdateManager.unknownSourcesSettingsIntents(context)
        assertTrue(intents.size >= 6)
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intents[0].action)
        assertEquals("package:${context.packageName}", intents[0].dataString)
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intents[1].action)
        assertNull(intents[1].data)
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents[2].action)
        assertEquals("package:${context.packageName}", intents[2].dataString)
        assertEquals(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, intents[3].action)
        assertEquals(android.provider.Settings.ACTION_SECURITY_SETTINGS, intents[4].action)
        assertEquals(android.provider.Settings.ACTION_SETTINGS, intents[5].action)
    }

    @Test
    fun mainActivity_launchUnknownSourcesSettings_usesActivityResultRegistryWithoutComposeDelay() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.launchUnknownSourcesSettings()

        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            started!!.intent.action,
        )
        assertEquals("package:${activity.packageName}", started.intent.dataString)
        controller.pause().stop().destroy()
    }

    @Test
    fun updateInstallStatusReceiver_pendingUserAction_handlesForegroundVsBackgroundCleanly() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val app = context as Application
        val receiver = UpdateInstallStatusReceiver()
        val confirmPrompt = Intent(Intent.ACTION_VIEW)

        try {
            UpdateEventBus.isForegroundActive = true
            val deferredEvent = async(start = CoroutineStart.UNDISPATCHED) {
                UpdateEventBus.events.first()
            }
            val fgBroadcast = Intent(context, UpdateInstallStatusReceiver::class.java).apply {
                putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                putExtra(Intent.EXTRA_INTENT, confirmPrompt)
            }
            receiver.onReceive(context, fgBroadcast)

            val event = deferredEvent.await()
            assertTrue(event is UpdateInstallEvent.UserConfirmationRequired)
            val emittedFlags = (event as UpdateInstallEvent.UserConfirmationRequired).intent.flags
            assertEquals(0, emittedFlags and Intent.FLAG_ACTIVITY_NEW_TASK)
            assertNull(shadowOf(app).nextStartedActivity)

            UpdateEventBus.isForegroundActive = false
            receiver.onReceive(context, fgBroadcast)
            val bgStarted = shadowOf(app).nextStartedActivity
            assertNotNull(bgStarted)
            assertTrue((bgStarted!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        } finally {
            UpdateEventBus.isForegroundActive = false
        }
    }

    @Test
    fun buildVerifiedFallbackInstallIntent_rejectsMissingAndCorruptedCachedApks() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val updater = AppUpdateManager(context, VaultStore(context))
        val payload = "corrupted-apk-bytes".encodeToByteArray()
        val manifest = UpdateManifest(
            versionCode = 88L,
            versionName = "1.8.8",
            apkUrl = "https://github.com/itripathiharsh/LedgerFlow/releases/download/v1.8.8/LedgerFlow-v1.8.8.apk",
            apkSizeBytes = 1024L,
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val cachedApk = File(updatesDir, "ledgerflow-v88.apk")
        cachedApk.delete()
        assertNull(updater.buildVerifiedFallbackInstallIntent(manifest))

        cachedApk.writeBytes(payload)
        assertNull(updater.buildVerifiedFallbackInstallIntent(manifest))

        val exactDigestManifest = manifest.copy(
            apkSizeBytes = payload.size.toLong(),
            sha256 = AppUpdatePolicy.sha256Hex(payload),
        )
        // Non-APK bytes must still fail archive package/signature inspection.
        assertNull(updater.buildVerifiedFallbackInstallIntent(exactDigestManifest))
        cachedApk.delete()
    }
}

@Suppress("DEPRECATION")
internal class RobolectricAndroidKeyStoreProvider(
    private val keyMap: ConcurrentHashMap<String, Key>,
) : Provider("AndroidKeyStore", 1.0, "Robolectric in-memory test AndroidKeyStore") {
    init {
        sharedKeys = keyMap
        put("KeyStore.AndroidKeyStore", InMemoryKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", InMemoryAesKeyGeneratorSpi::class.java.name)
    }

    class InMemoryKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = sharedKeys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) {
            sharedKeys[alias] = key
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray?, chain: Array<Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String, cert: Certificate?) = Unit
        override fun engineDeleteEntry(alias: String) { sharedKeys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(sharedKeys.keys)
        override fun engineContainsAlias(alias: String): Boolean = sharedKeys.containsKey(alias)
        override fun engineSize(): Int = sharedKeys.size
        override fun engineIsKeyEntry(alias: String): Boolean = sharedKeys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class InMemoryAesKeyGeneratorSpi : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null

        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            spec = params as? KeyGenParameterSpec
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineGenerateKey(): SecretKey {
            val baseProvider = Security.getProviders().first {
                it.name != "AndroidKeyStore" && it.getService("KeyGenerator", "AES") != null
            }
            val key = KeyGenerator.getInstance("AES", baseProvider).apply { init(256) }.generateKey()
            spec?.keystoreAlias?.let { alias -> sharedKeys[alias] = key }
            return key
        }
    }

    companion object {
        @Volatile
        private var sharedKeys = ConcurrentHashMap<String, Key>()
    }
}
