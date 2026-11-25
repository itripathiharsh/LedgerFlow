package com.ledgerflow.app.domain

import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSupportedVersionCode: Long = 1L,
    val releasedAt: String = "",
    val apkUrl: String,
    val apkSizeBytes: Long = 0L,
    val sha256: String,
    val signingCertSha256: String = "",
    val releaseNotes: List<String> = emptyList(),
    val mandatory: Boolean = false,
)

sealed class EligibilityResult {
    data class Available(val manifest: UpdateManifest) : EligibilityResult()
    data class UpToDate(val reason: String) : EligibilityResult()
    data class Dismissed(val manifest: UpdateManifest) : EligibilityResult()
    data class Rejected(val reason: String) : EligibilityResult()
}

sealed class ArchiveTrustResult {
    data object Trusted : ArchiveTrustResult()
    data class Rejected(val reason: String) : ArchiveTrustResult()
}

object AppUpdatePolicy {
    private val sha256Regex = Regex("^[0-9a-f]{64}$")

    fun normalizeHex(value: String): String =
        value.trim().replace(":", "").replace(" ", "").lowercase(Locale.US)

    fun isValidSha256Hex(value: String): Boolean = sha256Regex.matches(normalizeHex(value))

    fun hexToBytes(hex: String): ByteArray {
        val clean = normalizeHex(hex)
        require(isValidSha256Hex(clean)) { "Invalid SHA-256 hex digest" }
        return ByteArray(32) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }

    fun sha256Hex(bytes: ByteArray): String =
        bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun verifyFileSha256(file: File, expectedHex: String): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return false
        val normalized = normalizeHex(expectedHex)
        if (!isValidSha256Hex(normalized)) return false
        val expectedBytes = hexToBytes(normalized)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return MessageDigest.isEqual(digest.digest(), expectedBytes)
    }

    fun isAllowedUpdateUrl(url: String, allowCleartextLocal: Boolean = false): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (scheme == "https" && host.isNotBlank()) return true
        if (allowCleartextLocal && scheme == "http") {
            return host == "10.0.2.2" || host == "127.0.0.1" || host == "localhost"
        }
        return false
    }

    fun evaluateEligibility(
        manifest: UpdateManifest,
        installedVersionCode: Long,
        installedCertDigests: Set<String>,
        dismissedVersionCode: Long,
        ignoreDismissed: Boolean,
        pinnedExpectedCertHex: String = "",
        allowCleartextLocal: Boolean = false,
    ): EligibilityResult {
        if (manifest.versionCode <= installedVersionCode) {
            return EligibilityResult.UpToDate("Already on latest version ($installedVersionCode)")
        }
        if (manifest.versionName.isBlank()) {
            return EligibilityResult.Rejected("Missing release version name")
        }
        if (!isAllowedUpdateUrl(manifest.apkUrl, allowCleartextLocal)) {
            return EligibilityResult.Rejected("Update download URL must use HTTPS")
        }
        if (!isValidSha256Hex(manifest.sha256)) {
            return EligibilityResult.Rejected("Release manifest missing valid SHA-256 checksum")
        }
        val normalizedManifestCert = normalizeHex(manifest.signingCertSha256)
        if (normalizedManifestCert.isNotEmpty()) {
            if (!isValidSha256Hex(normalizedManifestCert)) {
                return EligibilityResult.Rejected("Manifest signing certificate digest is malformed")
            }
            val trusted = trustedCertSet(installedCertDigests, pinnedExpectedCertHex)
            if (trusted.isNotEmpty() && normalizedManifestCert !in trusted) {
                return EligibilityResult.Rejected("Release certificate does not match trusted publisher key")
            }
        }
        if (!ignoreDismissed && !manifest.mandatory && manifest.versionCode <= dismissedVersionCode) {
            return EligibilityResult.Dismissed(manifest)
        }
        return EligibilityResult.Available(manifest)
    }

    fun verifyArchiveTrust(
        archivePackageName: String?,
        archiveVersionCode: Long,
        archiveCertDigests: Set<String>,
        expectedPackageName: String,
        installedVersionCode: Long,
        installedCertDigests: Set<String>,
        manifest: UpdateManifest,
        pinnedExpectedCertHex: String = "",
    ): ArchiveTrustResult {
        if (archivePackageName.isNullOrBlank() || archivePackageName != expectedPackageName) {
            return ArchiveTrustResult.Rejected(
                "APK package ID mismatch ($archivePackageName vs $expectedPackageName)"
            )
        }
        if (archiveVersionCode <= installedVersionCode) {
            return ArchiveTrustResult.Rejected(
                "APK version $archiveVersionCode is not newer than installed $installedVersionCode"
            )
        }
        if (archiveVersionCode != manifest.versionCode) {
            return ArchiveTrustResult.Rejected(
                "APK version $archiveVersionCode does not match manifest version ${manifest.versionCode}"
            )
        }
        val cleanArchiveCerts = archiveCertDigests.map(::normalizeHex).filter(::isValidSha256Hex).toSet()
        if (cleanArchiveCerts.isEmpty()) {
            return ArchiveTrustResult.Rejected("Downloaded APK carries no readable signing certificate")
        }
        val trustedInstalled = trustedCertSet(installedCertDigests, pinnedExpectedCertHex)
        if (trustedInstalled.isEmpty()) {
            return ArchiveTrustResult.Rejected("Unable to read installed app signing certificate")
        }
        if (!cleanArchiveCerts.all { it in trustedInstalled }) {
            return ArchiveTrustResult.Rejected("APK signature does not match installed app signing key")
        }
        val manifestCert = normalizeHex(manifest.signingCertSha256)
        if (manifestCert.isNotEmpty() && manifestCert !in cleanArchiveCerts) {
            return ArchiveTrustResult.Rejected("APK signature does not match manifest certificate digest")
        }
        return ArchiveTrustResult.Trusted
    }

    private fun trustedCertSet(installedCertDigests: Set<String>, pinnedExpectedCertHex: String): Set<String> {
        val cleanInstalled = installedCertDigests.map(::normalizeHex).filter(::isValidSha256Hex).toSet()
        val pinned = normalizeHex(pinnedExpectedCertHex)
        return if (isValidSha256Hex(pinned)) {
            if (cleanInstalled.isEmpty()) setOf(pinned) else cleanInstalled.intersect(setOf(pinned))
        } else {
            cleanInstalled
        }
    }
}
