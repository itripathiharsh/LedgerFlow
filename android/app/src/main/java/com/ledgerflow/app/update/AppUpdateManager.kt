package com.ledgerflow.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.ledgerflow.app.BuildConfig
import com.ledgerflow.app.domain.AppUpdatePolicy
import com.ledgerflow.app.domain.ArchiveTrustResult
import com.ledgerflow.app.domain.EligibilityResult
import com.ledgerflow.app.domain.UpdateManifest
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.util.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed class UpdateCheckOutcome {
    data object Offline : UpdateCheckOutcome()
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateCheckOutcome()
    data class UpToDate(val versionName: String, val versionCode: Long) : UpdateCheckOutcome()
    data class Snoozed(val manifest: UpdateManifest) : UpdateCheckOutcome()
    data class Error(val message: String) : UpdateCheckOutcome()
}

sealed class UpdateInstallProgress {
    data object CheckingPermission : UpdateInstallProgress()
    data object NeedsInstallPermission : UpdateInstallProgress()
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : UpdateInstallProgress()
    data object VerifyingSignature : UpdateInstallProgress()
    data object Installing : UpdateInstallProgress()
    data class AwaitingUserConfirmation(val intent: Intent) : UpdateInstallProgress()
    data class Failed(val message: String) : UpdateInstallProgress()
}

sealed class UpdateInstallEvent {
    data class UserConfirmationRequired(val intent: Intent) : UpdateInstallEvent()
    data object Success : UpdateInstallEvent()
    data class Failed(val message: String) : UpdateInstallEvent()
}

object UpdateEventBus {
    private val _events = MutableSharedFlow<UpdateInstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UpdateInstallEvent> = _events.asSharedFlow()

    fun emit(event: UpdateInstallEvent) {
        _events.tryEmit(event)
    }
}

class AppUpdateManager(
    private val context: Context,
    private val vault: VaultStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val checkMutex = Mutex()
    private val installMutex = Mutex()

    companion object {
        private const val MAX_APK_BYTES = 150L * 1024L * 1024L
        const val MIN_BACKGROUND_CHECK_INTERVAL_MS = 4L * 3_600_000L
        const val UPDATE_NOTIFICATION_ID = 9042

        fun installedVersionCode(context: Context): Long {
            val info = getPackageInfoCompat(context, context.packageName, 0)
            return info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: BuildConfig.VERSION_CODE.toLong()
        }

        fun installedVersionName(context: Context): String {
            val info = getPackageInfoCompat(context, context.packageName, 0)
            return info?.versionName ?: BuildConfig.VERSION_NAME
        }

        fun canRequestPackageInstalls(context: Context): Boolean =
            context.packageManager.canRequestPackageInstalls()

        fun unknownSourcesSettingsIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            )

        fun getInstalledSigningCertDigests(context: Context): Set<String> {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val info = getPackageInfoCompat(context, context.packageName, flags) ?: return emptySet()
            return extractCertDigests(info)
        }

        fun cleanupUpdateCache(context: Context) {
            val dir = File(context.cacheDir, "updates")
            if (dir.exists()) {
                dir.listFiles()?.forEach { runCatching { it.delete() } }
            }
        }

        private fun getPackageInfoCompat(context: Context, pkg: String, flags: Int): PackageInfo? =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        pkg,
                        PackageManager.PackageInfoFlags.of(flags.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(pkg, flags)
                }
            }.getOrNull()

        private fun getPackageArchiveInfoCompat(context: Context, apkPath: String, flags: Int): PackageInfo? =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageArchiveInfo(
                        apkPath,
                        PackageManager.PackageInfoFlags.of(flags.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageArchiveInfo(apkPath, flags)
                }
            }.getOrNull()

        private fun extractCertDigests(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo
                val active = signingInfo?.apkContentsSigners
                if (active != null && active.isNotEmpty()) {
                    active
                } else {
                    @Suppress("DEPRECATION")
                    info.signatures
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            return signatures?.map { AppUpdatePolicy.sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
        }
    }

    fun loadCachedEligibleManifest(ignoreDismissed: Boolean = false): UpdateManifest? {
        val state = vault.read()
        val raw = state.cachedUpdateJson ?: return null
        val manifest = runCatching { json.decodeFromString(UpdateManifest.serializer(), raw) }.getOrNull() ?: return null
        return when (
            val eligibility = AppUpdatePolicy.evaluateEligibility(
                manifest = manifest,
                installedVersionCode = installedVersionCode(context),
                installedCertDigests = getInstalledSigningCertDigests(context),
                dismissedVersionCode = state.dismissedUpdateVersionCode,
                ignoreDismissed = ignoreDismissed,
                pinnedExpectedCertHex = BuildConfig.EXPECTED_SIGNING_CERT_SHA256,
                allowCleartextLocal = BuildConfig.DEBUG,
            )
        ) {
            is EligibilityResult.Available -> eligibility.manifest
            else -> null
        }
    }

    suspend fun checkForUpdate(ignoreDismissed: Boolean = false): UpdateCheckOutcome = checkMutex.withLock {
        withContext(Dispatchers.IO) {
        if (!NetworkMonitor.isOnline(context)) {
            return@withContext UpdateCheckOutcome.Offline
        }
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL.trim()
        if (!AppUpdatePolicy.isAllowedUpdateUrl(manifestUrl, allowCleartextLocal = BuildConfig.DEBUG)) {
            return@withContext UpdateCheckOutcome.Error("Update manifest URL must use HTTPS")
        }

        val state = vault.read()
        val installedCode = installedVersionCode(context)
        val installedName = installedVersionName(context)
        val installedCerts = getInstalledSigningCertDigests(context)

        val request = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            .header("X-App-Package", context.packageName)
            .header("X-App-Version-Code", installedCode.toString())
            .get()
            .build()

        val manifest = try {
            http.newCall(request).execute().use { resp ->
                if (!AppUpdatePolicy.isAllowedUpdateUrl(resp.request.url.toString(), allowCleartextLocal = BuildConfig.DEBUG)) {
                    return@withContext UpdateCheckOutcome.Error("Blocked insecure update redirect")
                }
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheckOutcome.Error("Update check returned HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                json.decodeFromString(UpdateManifest.serializer(), body)
            }
        } catch (t: Throwable) {
            return@withContext UpdateCheckOutcome.Error(t.message ?: "Unable to reach update server")
        }

        val nowMs = System.currentTimeMillis()
        when (
            val eligibility = AppUpdatePolicy.evaluateEligibility(
                manifest = manifest,
                installedVersionCode = installedCode,
                installedCertDigests = installedCerts,
                dismissedVersionCode = state.dismissedUpdateVersionCode,
                ignoreDismissed = ignoreDismissed,
                pinnedExpectedCertHex = BuildConfig.EXPECTED_SIGNING_CERT_SHA256,
                allowCleartextLocal = BuildConfig.DEBUG,
            )
        ) {
            is EligibilityResult.Available -> {
                val encoded = json.encodeToString(UpdateManifest.serializer(), manifest)
                vault.update { it.copy(cachedUpdateJson = encoded, lastUpdateCheckEpochMs = nowMs) }
                UpdateCheckOutcome.UpdateAvailable(manifest)
            }
            is EligibilityResult.Dismissed -> {
                val encoded = json.encodeToString(UpdateManifest.serializer(), manifest)
                vault.update { it.copy(cachedUpdateJson = encoded, lastUpdateCheckEpochMs = nowMs) }
                UpdateCheckOutcome.Snoozed(manifest)
            }
            is EligibilityResult.UpToDate -> {
                vault.update { it.copy(cachedUpdateJson = null, lastUpdateCheckEpochMs = nowMs) }
                UpdateCheckOutcome.UpToDate(installedName, installedCode)
            }
            is EligibilityResult.Rejected -> {
                vault.update { it.copy(lastUpdateCheckEpochMs = nowMs) }
                UpdateCheckOutcome.Error(eligibility.reason)
            }
        }
        }
    }

    suspend fun downloadVerifyAndInstall(
        manifest: UpdateManifest,
        onProgress: (UpdateInstallProgress) -> Unit,
    ): Boolean = installMutex.withLock {
        withContext(Dispatchers.IO) {
        onProgress(UpdateInstallProgress.CheckingPermission)
        if (!canRequestPackageInstalls(context)) {
            onProgress(UpdateInstallProgress.NeedsInstallPermission)
            return@withContext false
        }
        if (!NetworkMonitor.isOnline(context)) {
            onProgress(UpdateInstallProgress.Failed("You are offline. Reconnect to download this update."))
            return@withContext false
        }
        if (!AppUpdatePolicy.isAllowedUpdateUrl(manifest.apkUrl, allowCleartextLocal = BuildConfig.DEBUG)) {
            onProgress(UpdateInstallProgress.Failed("Blocked non-HTTPS update URL."))
            return@withContext false
        }
        if (!AppUpdatePolicy.isValidSha256Hex(manifest.sha256)) {
            onProgress(UpdateInstallProgress.Failed("Update blocked: missing valid SHA-256 digest."))
            return@withContext false
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val minRequiredBytes = (if (manifest.apkSizeBytes > 0L) manifest.apkSizeBytes * 2 else 20L * 1024L * 1024L)
        if (updatesDir.usableSpace in 1L until minRequiredBytes) {
            onProgress(UpdateInstallProgress.Failed("Insufficient storage space to download and install update."))
            return@withContext false
        }
        val partFile = File(updatesDir, "ledgerflow-v${manifest.versionCode}.apk.part")
        val finalApk = File(updatesDir, "ledgerflow-v${manifest.versionCode}.apk")
        runCatching { partFile.delete() }
        runCatching { finalApk.delete() }

        // Always stream clean verified APK
        val request = Request.Builder().url(manifest.apkUrl).get().build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!AppUpdatePolicy.isAllowedUpdateUrl(resp.request.url.toString(), allowCleartextLocal = BuildConfig.DEBUG)) {
                    onProgress(UpdateInstallProgress.Failed("Blocked insecure APK redirect"))
                    return@withContext false
                }
                if (!resp.isSuccessful) {
                    onProgress(UpdateInstallProgress.Failed("Download failed (HTTP ${resp.code})"))
                    return@withContext false
                }
                val body = resp.body ?: run {
                    onProgress(UpdateInstallProgress.Failed("Empty APK response body"))
                    return@withContext false
                }
                val expectedTotal = if (manifest.apkSizeBytes > 0L) manifest.apkSizeBytes else body.contentLength()
                if (expectedTotal > MAX_APK_BYTES) {
                    onProgress(UpdateInstallProgress.Failed("Update APK exceeds 150 MB safety limit"))
                    return@withContext false
                }
                var downloaded = 0L
                var lastReported = 0L
                val buf = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    partFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            downloaded += read
                            if (downloaded > MAX_APK_BYTES) {
                                onProgress(UpdateInstallProgress.Failed("Downloaded APK exceeded safety size limit"))
                                return@withContext false
                            }
                            output.write(buf, 0, read)
                            if (downloaded - lastReported >= 64 * 1024 || downloaded == expectedTotal) {
                                lastReported = downloaded
                                onProgress(UpdateInstallProgress.Downloading(downloaded, expectedTotal))
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(UpdateInstallProgress.Downloading(downloaded, expectedTotal))
            }
        } catch (t: Throwable) {
            runCatching { partFile.delete() }
            onProgress(UpdateInstallProgress.Failed(t.message ?: "Download interrupted"))
            return@withContext false
        }

        onProgress(UpdateInstallProgress.VerifyingSignature)

        if (manifest.apkSizeBytes > 0L && partFile.length() != manifest.apkSizeBytes) {
            runCatching { partFile.delete() }
            onProgress(UpdateInstallProgress.Failed("Downloaded file size mismatch. Update aborted."))
            return@withContext false
        }
        if (!AppUpdatePolicy.verifyFileSha256(partFile, manifest.sha256)) {
            runCatching { partFile.delete() }
            onProgress(UpdateInstallProgress.Failed("SHA-256 verification failed. File may be corrupted."))
            return@withContext false
        }
        if (!partFile.renameTo(finalApk)) {
            partFile.copyTo(finalApk, overwrite = true)
            partFile.delete()
        }

        // Verify APK package name, versionCode & embedded signing certificate digest BEFORE opening PackageInstaller!
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archiveInfo = getPackageArchiveInfoCompat(context, finalApk.absolutePath, flags)
            ?: getPackageArchiveInfoCompat(
                context,
                finalApk.absolutePath,
                @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES,
            )
        if (archiveInfo == null) {
            runCatching { finalApk.delete() }
            onProgress(UpdateInstallProgress.Failed("Downloaded file is not a valid Android APK."))
            return@withContext false
        }

        val archiveCerts = extractCertDigests(archiveInfo)
        val installedCerts = getInstalledSigningCertDigests(context)
        val trust = AppUpdatePolicy.verifyArchiveTrust(
            archivePackageName = archiveInfo.packageName,
            archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo),
            archiveCertDigests = archiveCerts,
            expectedPackageName = context.packageName,
            installedVersionCode = installedVersionCode(context),
            installedCertDigests = installedCerts,
            manifest = manifest,
            pinnedExpectedCertHex = BuildConfig.EXPECTED_SIGNING_CERT_SHA256,
        )
        if (trust is ArchiveTrustResult.Rejected) {
            runCatching { finalApk.delete() }
            onProgress(UpdateInstallProgress.Failed(trust.reason))
            return@withContext false
        }

        onProgress(UpdateInstallProgress.Installing)
        vault.update {
            it.copy(
                pendingUpdateRelaunch = true,
                pendingUpdateVersionName = manifest.versionName,
            )
        }

        return@withContext try {
            installViaPackageInstallerSession(finalApk, manifest)
            true
        } catch (_: Throwable) {
            // Fallback to FileProvider APK install intent (ACTION_MY_PACKAGE_REPLACED still relaunches app after completion)
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", finalApk)
                val fallback = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onProgress(UpdateInstallProgress.AwaitingUserConfirmation(fallback))
                true
            }.getOrElse { err ->
                vault.update { it.copy(pendingUpdateRelaunch = false, pendingUpdateVersionName = null) }
                onProgress(UpdateInstallProgress.Failed(err.message ?: "Unable to start APK installer"))
                false
            }
        }
        }
    }

    private fun installViaPackageInstallerSession(apkFile: File, manifest: UpdateManifest) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("ledgerflow_v${manifest.versionCode}.apk", 0L, apkFile.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val callbackIntent = Intent(context, UpdateInstallStatusReceiver::class.java).apply {
                setPackage(context.packageName)
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val statusPendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, piFlags)
            session.commit(statusPendingIntent.intentSender)
        }
    }
}
