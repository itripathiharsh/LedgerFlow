package com.ledgerflow.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

@Serializable
data class VaultSessionState(
    val userId: String? = null,
    val lastCloudUserId: String? = null,
    val userEmail: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val lastSyncEpochMs: Long = 0L,
    val lastSyncWatermarkIso: String? = null,
    val baseCurrency: String = "USD",
    val appLockEnabled: Boolean = false,
    val autoLockSeconds: Int = 10,
    val blurInSwitcher: Boolean = true,
    val payAndTrackEnabled: Boolean = true,
    val notificationDisclosureAccepted: Boolean = false,
    val telemetryOptIn: Boolean = false,
    val hideBalances: Boolean = false,
    val pinHashBase64: String? = null,
    val pinSaltBase64: String? = null,
    val dismissedUpdateVersionCode: Long = 0L,
    val lastUpdateCheckEpochMs: Long = 0L,
    val cachedUpdateJson: String? = null,
    val pendingUpdateRelaunch: Boolean = false,
    val pendingUpdateVersionName: String? = null,
    val justUpdatedVersionName: String? = null,
    val pendingInstallResumeVersionCode: Long = 0L,
    val cachedFxRatesJson: String? = null,
    val cachedFxDate: String? = null,
    val onboardingCompleted: Boolean = false,
    val userName: String = "",
    val themeMode: String = "SYSTEM", // SYSTEM, LIGHT, DARK
)

interface VaultKeyProvider {
    fun getOrCreateSecretKey(): SecretKey
}

class AndroidKeyStoreProvider(
    private val alias: String = "ledgerflow_vault_aes256_gcm",
) : VaultKeyProvider {
    override fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}

class VaultCryptoEngine(
    private val keyProvider: VaultKeyProvider,
) {
    fun encryptBytes(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    fun decryptBytes(payload: ByteArray): ByteArray {
        require(payload.size > 13) { "Corrupted encrypted vault blob" }
        val ivLen = payload[0].toInt() and 0xFF
        require(ivLen in 12..16 && payload.size > 1 + ivLen) { "Invalid GCM IV length" }
        val iv = payload.copyOfRange(1, 1 + ivLen)
        val ciphertext = payload.copyOfRange(1 + ivLen, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider.getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private val random = SecureRandom()

        fun generateSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

        fun hashPin(pin: String, salt: ByteArray): ByteArray {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(pin.toCharArray(), salt, 10_000, 256)
            return factory.generateSecret(spec).encoded
        }

        fun verifyPin(pin: String, saltBase64: String, expectedHashBase64: String): Boolean {
            val salt = Base64.getDecoder().decode(saltBase64)
            val expected = Base64.getDecoder().decode(expectedHashBase64)
            val actual = hashPin(pin, salt)
            return MessageDigest.isEqual(actual, expected)
        }
    }
}

class VaultStore(context: Context) {
    private val appContext = context.applicationContext
    private val vaultFile = File(appContext.filesDir, "ledgerflow_vault.enc")
    private val engine = VaultCryptoEngine(AndroidKeyStoreProvider())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(): VaultSessionState = synchronized(processLock) {
        val fileKey = vaultFile.absolutePath
        cachedStates[fileKey]?.let { return it }
        if (!vaultFile.exists()) return VaultSessionState().also { cachedStates[fileKey] = it }
        return try {
            val raw = vaultFile.readBytes()
            val plain = engine.decryptBytes(raw).decodeToString()
            json.decodeFromString(VaultSessionState.serializer(), plain).also { cachedStates[fileKey] = it }
        } catch (_: Exception) {
            // Preserve unreadable blob for recovery rather than silently zeroing state in place
            val corruptCopy = File(vaultFile.parentFile, "ledgerflow_vault.enc.corrupt")
            runCatching { vaultFile.copyTo(corruptCopy, overwrite = true) }
            VaultSessionState()
        }
    }

    fun update(transform: (VaultSessionState) -> VaultSessionState): VaultSessionState = synchronized(processLock) {
        val next = transform(read())
        val bytes = json.encodeToString(VaultSessionState.serializer(), next).encodeToByteArray()
        val encrypted = engine.encryptBytes(bytes)
        val tmp = File(vaultFile.parentFile, "ledgerflow_vault.enc.tmp")
        tmp.writeBytes(encrypted)
        if (!tmp.renameTo(vaultFile)) {
            vaultFile.writeBytes(encrypted)
            tmp.delete()
        }
        cachedStates[vaultFile.absolutePath] = next
        return next
    }

    fun setPin(pin: String): VaultSessionState {
        val salt = VaultCryptoEngine.generateSalt()
        val hash = VaultCryptoEngine.hashPin(pin, salt)
        return update {
            it.copy(
                appLockEnabled = true,
                pinSaltBase64 = Base64.getEncoder().encodeToString(salt),
                pinHashBase64 = Base64.getEncoder().encodeToString(hash),
            )
        }
    }

    fun checkPin(pin: String): Boolean {
        val s = read()
        val salt = s.pinSaltBase64 ?: return false
        val hash = s.pinHashBase64 ?: return false
        return VaultCryptoEngine.verifyPin(pin, salt, hash)
    }

    fun clearAuth(): VaultSessionState = update {
        it.copy(userId = null, userEmail = null, accessToken = null, refreshToken = null)
    }

    fun resetSyncAndCloudState(): VaultSessionState = update {
        it.copy(
            userId = null,
            lastCloudUserId = null,
            accessToken = null,
            refreshToken = null,
            lastSyncEpochMs = 0L,
            lastSyncWatermarkIso = null,
            hideBalances = false,
        )
    }

    companion object {
        private val processLock = Any()
        private val cachedStates = mutableMapOf<String, VaultSessionState>()

        internal fun clearCacheForTests() {
            synchronized(processLock) { cachedStates.clear() }
        }
    }
}
