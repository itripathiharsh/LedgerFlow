package com.ledgerflow.app.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.NotificationParser
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PayAndTrackNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != GOOGLE_WALLET_PACKAGE) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val text = bigText.ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() }

        serviceScope.launch {
            capturePayment(applicationContext, title, text)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val GOOGLE_WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"
        private val recentDedupe = ConcurrentHashMap<String, Long>()

        suspend fun capturePayment(
            context: Context,
            title: String,
            text: String,
            forceForTest: Boolean = false,
        ): Boolean {
            val vault = VaultStore(context)
            val state = vault.read()
            if (!forceForTest && !state.payAndTrackEnabled) return false

            val parsed = NotificationParser.parseGooglePayNotification(
                title = title,
                text = text,
                defaultCurrency = state.baseCurrency,
            ) ?: return false

            val now = System.currentTimeMillis()
            val dedupeKey = "${parsed.merchant.lowercase()}_${parsed.amountMinor}_${parsed.currency}"
            if (!forceForTest) {
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
                if (duplicate) return false
            }

            val db = LedgerDatabase.getInstance(context)
            val accounts = db.accountDao().getAllActive()
            val targetAccount = accounts.firstOrNull { it.currency == parsed.currency } ?: accounts.firstOrNull() ?: return false
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
                note = "Captured via Pay & Track",
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
            return true
        }
    }
}
