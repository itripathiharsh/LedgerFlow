package com.ledgerflow.app.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.LedgerDatabase.DetectedCaptureAction
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.AccountMatcher
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.NotificationParser
import com.ledgerflow.app.domain.SupportedPaymentApps
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.widget.LedgerWidgetCoordinator
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

object PayAndTrackCapture {
    const val GOOGLE_PAY_INDIA_PACKAGE = "com.google.android.apps.nbu.paisa.user"
    const val GOOGLE_WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"
    const val PHONEPE_PACKAGE = "com.phonepe.app"
    const val PAYTM_PACKAGE = "net.one97.paytm"
    const val BHIM_PACKAGE = "in.org.npci.upiapp"

    data class CaptureOutcome(
        val captured: Boolean,
        val action: DetectedCaptureAction? = null,
        val transactionId: String? = null,
        val merchant: String? = null,
        val amountMinor: Long = 0L,
        val currency: String = "USD",
        val matchedAccountId: String = TransactionEntity.UNASSIGNED_ACCOUNT_ID,
        val appName: String = "",
        val isRefundOrReversal: Boolean = false,
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
        noteText: String = "",
        packageName: String = "",
        notificationKey: String = "",
        occurredAtEpochMs: Long = System.currentTimeMillis(),
        dbOverride: LedgerDatabase? = null,
    ): Boolean = capturePaymentDetailed(
        context = context,
        title = title,
        text = text,
        manualEntry = manualEntry,
        noteText = noteText,
        packageName = packageName,
        notificationKey = notificationKey,
        occurredAtEpochMs = occurredAtEpochMs,
        dbOverride = dbOverride,
    ).captured

    suspend fun capturePaymentDetailed(
        context: Context,
        title: String,
        text: String,
        packageName: String = "",
        appName: String = "",
        subText: String = "",
        notificationKey: String = "",
        occurredAtEpochMs: Long = System.currentTimeMillis(),
        manualEntry: Boolean = false,
        noteText: String = "",
        dbOverride: LedgerDatabase? = null,
    ): CaptureOutcome {
        val vault = VaultStore(context)
        val state = vault.read()
        if (!manualEntry && !state.payAndTrackEnabled) return CaptureOutcome(false)

        val cleanPkg = packageName.trim().lowercase(Locale.US)
        val supportedApp = SupportedPaymentApps.resolve(cleanPkg)
        if (!manualEntry && cleanPkg.isNotBlank() && supportedApp == null) {
            return CaptureOutcome(false)
        }

        val resolvedAppName = appName.trim().ifBlank {
            supportedApp?.displayName ?: if (manualEntry) "Smart Paste" else "Google Pay"
        }
        val sourceTag = supportedApp?.remoteSourceTag ?: "GPAY_AUTO"

        val inspection = NotificationParser.inspectNotification(
            title = title,
            text = text,
            defaultCurrency = state.baseCurrency,
            sourceApp = resolvedAppName,
            subText = subText,
            defaultBankHint = supportedApp?.bankHint,
        )

        val db = dbOverride ?: LedgerDatabase.getInstance(context)
        val now = System.currentTimeMillis()
        val safeOccurredAt = occurredAtEpochMs.takeIf { it > 0L } ?: now

        if (inspection.kind == NotificationParser.NotificationParseKind.FAILURE_OR_DECLINED) {
            val cancelled = db.handleDetectedFailureNotificationAtomic(
                notificationKey = notificationKey.trim(),
                referenceId = inspection.referenceId.orEmpty(),
                nowMs = now,
            )
            if (cancelled && dbOverride == null) {
                LedgerWidgetCoordinator.requestRefresh(context)
            }
            return CaptureOutcome(false, action = DetectedCaptureAction.CANCELLED_BY_FAILURE)
        }

        val parsed = inspection.payment ?: return CaptureOutcome(false)

        val accounts = db.accountDao().getAllActive()
        val matchedAccountId = AccountMatcher.matchAccountId(
            accounts = accounts,
            bankHint = parsed.bankOrAccountHint,
            lastFour = parsed.accountLastFour,
        )

        val convertedMinor = if (parsed.currency != state.baseCurrency) {
            FxConverter.convertMinor(parsed.amountMinor, parsed.currency, state.baseCurrency)
        } else null

        val accountHintText = listOfNotNull(
            parsed.bankOrAccountHint?.takeIf { it.isNotBlank() },
            parsed.accountLastFour?.let { "••$it" },
        ).joinToString(" ")

        val minuteBucket = safeOccurredAt / 60_000L
        val fingerprint = sha256Hex(
            "$cleanPkg|${title.trim()}|${text.trim()}|${parsed.amountMinor}|${parsed.currency}|${parsed.txType}|$minuteBucket"
        )

        // Strict user-controlled classification: NEVER guess category or spending purpose automatically.
        val tx = TransactionEntity(
            id = UUID.randomUUID().toString(),
            accountId = matchedAccountId,
            categoryId = null,
            categoryIdsCsv = "",
            txType = parsed.txType,
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
            convertedMinor = convertedMinor,
            convertedCurrency = if (convertedMinor != null) state.baseCurrency else null,
            merchant = parsed.merchant.orEmpty(),
            note = noteText.trim(),
            labelsCsv = if (parsed.isRefundOrReversal) "refund" else "",
            sourceTag = sourceTag,
            autoRuleApplied = false,
            occurredAtEpochMs = safeOccurredAt,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            pendingSync = true,
            reviewStatus = TransactionEntity.STATUS_PENDING_REVIEW,
            spendingPurpose = "",
            paymentAppName = resolvedAppName,
            paymentAppPackage = cleanPkg,
            referenceId = parsed.referenceId.orEmpty(),
            accountHint = accountHintText,
            notificationKey = notificationKey.trim(),
            notificationFingerprint = fingerprint,
            originalParsedAmountMinor = parsed.amountMinor,
            isRefundOrReversal = parsed.isRefundOrReversal,
        )

        val result = db.upsertDetectedPaymentAtomic(tx, nowMs = now)
        val capturedOk = result.action == DetectedCaptureAction.CREATED_PENDING ||
            result.action == DetectedCaptureAction.UPDATED_PENDING
        if (capturedOk && dbOverride == null) {
            LedgerWidgetCoordinator.requestRefresh(context)
        }

        return CaptureOutcome(
            captured = capturedOk,
            action = result.action,
            transactionId = result.transaction?.id,
            merchant = parsed.merchant,
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
            matchedAccountId = result.transaction?.accountId ?: matchedAccountId,
            appName = resolvedAppName,
            isRefundOrReversal = parsed.isRefundOrReversal,
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
