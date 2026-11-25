package com.ledgerflow.app.service

import android.content.Intent
import android.content.Context
import android.provider.Settings
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.NotificationParser
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.work.SyncWorker
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PayAndTrackCapture {
    const val GOOGLE_WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"
    private val recentDedupe = ConcurrentHashMap<String, Long>()

    data class CaptureOutcome(
        val captured: Boolean,
        val merchant: String? = null,
        val amountMinor: Long = 0L,
        val currency: String = "USD",
    )

    fun isOsListenerAvailable(context: Context): Boolean {
        val intent = Intent("android.service.notification.NotificationListenerService")
            .setPackage(context.packageName)
        return context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
    }

    fun isOsListenerEnabled(context: Context): Boolean {
        if (!isOsListenerAvailable(context)) return false
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.contains("${context.packageName}/")
    }

    suspend fun capturePayment(
        context: Context,
        title: String,
        text: String,
        manualEntry: Boolean = false,
        noteText: String = "Captured via Pay & Track",
    ): Boolean = capturePaymentDetailed(context, title, text, manualEntry, noteText).captured

    suspend fun capturePaymentDetailed(
        context: Context,
        title: String,
        text: String,
        manualEntry: Boolean = false,
        noteText: String = "Captured via Pay & Track",
    ): CaptureOutcome {
        val vault = VaultStore(context)
        val state = vault.read()
        if (!manualEntry && !state.payAndTrackEnabled) return CaptureOutcome(false)

        val parsed = NotificationParser.parseGooglePayNotification(
            title = title,
            text = text,
            defaultCurrency = state.baseCurrency,
        ) ?: return CaptureOutcome(false)

        val now = System.currentTimeMillis()
        val dedupeKey = "${parsed.merchant.lowercase()}_${parsed.amountMinor}_${parsed.currency}"
        if (!manualEntry) {
            if (recentDedupe.size > 64) {
                val cutoff = now - 300_000L
                recentDedupe.entries.removeIf { it.value < cutoff }
            }
            var duplicate = false
            recentDedupe.compute(dedupeKey) { _, prev ->
                if (prev != null && (now - prev) < 60_000L) {
                    duplicate = true
                    prev
                } else {
                    now
                }
            }
            if (duplicate) return CaptureOutcome(false)
        }

        val db = LedgerDatabase.getInstance(context)
        val accounts = db.accountDao().getAllActive()
        val targetAccount = accounts.firstOrNull { it.currency == parsed.currency } ?: accounts.firstOrNull()
            ?: return CaptureOutcome(false)
        val rules = db.magicRuleDao().getAllActive()
        val ruleMatch = MagicRuleEngine.apply(parsed.merchant, text, rules)

        val convertedMinor = if (parsed.currency != state.baseCurrency) {
            FxConverter.convertMinor(parsed.amountMinor, parsed.currency, state.baseCurrency)
        } else null

        val tx = TransactionEntity(
            id = UUID.randomUUID().toString(),
            accountId = targetAccount.id,
            categoryId = ruleMatch.categoryId,
            txType = "EXPENSE",
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
            convertedMinor = convertedMinor,
            convertedCurrency = if (convertedMinor != null) state.baseCurrency else null,
            merchant = parsed.merchant,
            note = noteText,
            labelsCsv = ruleMatch.label ?: "gpay",
            sourceTag = "GPAY_AUTO",
            autoRuleApplied = ruleMatch.matched,
            occurredAtEpochMs = now,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            pendingSync = true,
        )
        db.recordTransactionAtomic(tx)
        SyncWorker.enqueueWhenOnline(context)
        return CaptureOutcome(
            captured = true,
            merchant = parsed.merchant,
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
        )
    }
}
