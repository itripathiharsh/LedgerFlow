package com.ledgerflow.app

import com.ledgerflow.app.data.local.MagicRuleEntity
import com.ledgerflow.app.domain.CalculatorEngine
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.NotificationParser
import com.ledgerflow.app.security.VaultCryptoEngine
import com.ledgerflow.app.security.VaultKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class FinanceMathTest {
    @Test
    fun moneyFormatter_formatsNegativesPlusZeroDecimalsAndMasking() {
        assertEquals("-$6.80", MoneyFormatter.formatMinor(-680L, "USD"))
        assertEquals("+$2,710.00", MoneyFormatter.formatMinor(271_000L, "USD", showPlus = true))
        assertEquals("¥1,542", MoneyFormatter.formatMinor(1542L, "JPY"))
        assertEquals("€••••", MoneyFormatter.formatMinor(318_000L, "EUR", masked = true))
        assertEquals(1999L, MoneyFormatter.parseDecimalToMinor("19.99"))
        assertEquals(123_450L, MoneyFormatter.parseDecimalToMinor("1,234.50"))
    }

    @Test
    fun calculatorEngine_evaluatesPrecedenceAndRejectsInvalidMath() {
        assertEquals(4850L, CalculatorEngine.evaluateToMinor("48.50"))
        assertEquals(2000L, CalculatorEngine.evaluateToMinor("10+5*2"))
        assertEquals(2500L, CalculatorEngine.evaluateToMinor("100/4"))
        assertEquals(1500L, CalculatorEngine.evaluateToMinor("15+"))
        assertNull(CalculatorEngine.evaluateToMinor("10/0"))
        assertNull(CalculatorEngine.evaluateToMinor("5-20"))
    }

    @Test
    fun fxConverter_convertsAcrossCurrenciesDeterministically() {
        val usdToEur = FxConverter.convertMinor(10_000L, "USD", "EUR")
        assertEquals(9250L, usdToEur)
        assertEquals(10_000L, FxConverter.convertMinor(10_000L, "USD", "USD"))
    }

    @Test
    fun notificationParser_extractsGoogleWalletPaymentsAndIgnoresNoise() {
        val p1 = NotificationParser.parseGooglePayNotification("Paid \$6.80 at Blue Bottle Coffee", "Checking ••4821")
        assertNotNull(p1)
        assertEquals("Blue Bottle Coffee", p1!!.merchant)
        assertEquals(-680L, p1.amountMinor)
        assertEquals("USD", p1.currency)

        val p2 = NotificationParser.parseGooglePayNotification("Google Pay", "Spent €3.20 at Metro Transit")
        assertNotNull(p2)
        assertEquals(-320L, p2!!.amountMinor)
        assertEquals("EUR", p2.currency)

        assertNull(NotificationParser.parseGooglePayNotification("Google Wallet", "Your weekly summary is ready"))
    }

    @Test
    fun magicRuleEngine_matchesPatternsAndSanitizesFtsQueries() {
        val rules = listOf(
            MagicRuleEntity("r1", "coffee|espresso", "cat_dining", "coffee", enabled = true, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
            MagicRuleEntity("r2", "disabled", "cat_other", "no", enabled = false, createdAtEpochMs = 1L, updatedAtEpochMs = 1L),
        )
        val hit = MagicRuleEngine.apply("Blue Bottle Espresso #12", "", rules)
        assertTrue(hit.matched)
        assertEquals("cat_dining", hit.categoryId)
        assertEquals("coffee", hit.label)

        val miss = MagicRuleEngine.apply("Disabled Store", "", rules)
        assertFalse(miss.matched)

        assertEquals("Trader* Joe* s* groceries*", MagicRuleEngine.sanitizeFtsQuery("Trader Joe's #groceries"))
    }

    @Test
    fun vaultCryptoEngine_encryptsDecryptsAndVerifiesPin() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val provider = object : VaultKeyProvider {
            override fun getOrCreateSecretKey(): SecretKey = key
        }
        val crypto = VaultCryptoEngine(provider)
        val secret = "token-123-offline-vault".encodeToByteArray()
        val encrypted = crypto.encryptBytes(secret)
        assertFalse(encrypted.contentEquals(secret))
        assertEquals("token-123-offline-vault", crypto.decryptBytes(encrypted).decodeToString())

        val salt = VaultCryptoEngine.generateSalt()
        val hash = VaultCryptoEngine.hashPin("2580", salt)
        val saltB64 = java.util.Base64.getEncoder().encodeToString(salt)
        val hashB64 = java.util.Base64.getEncoder().encodeToString(hash)
        assertTrue(VaultCryptoEngine.verifyPin("2580", saltB64, hashB64))
        assertFalse(VaultCryptoEngine.verifyPin("9999", saltB64, hashB64))
    }
}
