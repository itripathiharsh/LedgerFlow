package com.ledgerflow.app.widget

import android.content.Context
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.NotificationParser
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.service.PayAndTrackCapture
import kotlin.math.abs

sealed interface SmartPasteOutcome {
    data object EmptyClipboard : SmartPasteOutcome
    data object IgnoredDeclinedAlert : SmartPasteOutcome
    data class CapturedPending(
        val formattedAmount: String,
        val merchant: String?,
        val txType: String,
        val duplicateSkipped: Boolean,
    ) : SmartPasteOutcome

    data class NeedsConfirmation(
        val prefillAmountMinor: Long,
        val prefillCurrency: String,
        val prefillTxType: String,
        val prefillMerchant: String,
        val rawNote: String,
    ) : SmartPasteOutcome

    data object UnsupportedContent : SmartPasteOutcome
}

/**
 * Evaluates clipboard text locally with [NotificationParser] and [PayAndTrackCapture].
 * Never auto-assigns a category and never silently commits ambiguous clipboard text.
 */
object SmartPasteProcessor {
    private val partialAmountRegex = Regex(
        """(?i)(?:₹|rs\.?|inr|usd|eur|gbp|\$|€|£)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)|\b([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:inr|rs\.?|usd|eur|gbp)\b"""
    )

    private val partialCreditKeywordRegex = Regex(
        """(?i)\b(credited|received|deposited|refund(?:ed)?|cashback|salary|paid\s+you)\b"""
    )

    private val partialDebitKeywordRegex = Regex(
        """(?i)\b(debited|spent|paid|sent|purchased|withdrawn|upi|txn|transaction|order|bill)\b"""
    )

    suspend fun evaluateAndCapture(
        context: Context,
        rawClipboardText: String,
        dbOverride: LedgerDatabase? = null,
        vaultOverride: VaultStore? = null,
    ): SmartPasteOutcome {
        val cleaned = rawClipboardText.trim()
        if (cleaned.isBlank()) return SmartPasteOutcome.EmptyClipboard

        val vault = (vaultOverride ?: VaultStore(context)).read()
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val title = lines.firstOrNull().orEmpty()
        val body = lines.drop(1).joinToString(" ")

        val inspection = NotificationParser.inspectNotification(
            title = title,
            text = body,
            defaultCurrency = vault.baseCurrency,
            sourceApp = "Smart Paste",
        )

        when (inspection.kind) {
            NotificationParser.NotificationParseKind.FAILURE_OR_DECLINED -> {
                val db = dbOverride ?: LedgerDatabase.getInstance(context)
                db.handleDetectedFailureNotificationAtomic(
                    notificationKey = "",
                    referenceId = inspection.referenceId.orEmpty(),
                )
                return SmartPasteOutcome.IgnoredDeclinedAlert
            }

            NotificationParser.NotificationParseKind.PAYMENT -> {
                val parsed = inspection.payment
                if (parsed != null && parsed.amountMinor != 0L) {
                    val outcome = PayAndTrackCapture.capturePaymentDetailed(
                        context = context,
                        title = title,
                        text = body,
                        packageName = "",
                        appName = "Smart Paste",
                        manualEntry = true,
                        noteText = "Widget Smart Paste",
                        dbOverride = dbOverride,
                    )
                    val formatted = MoneyFormatter.formatMinor(abs(parsed.amountMinor), parsed.currency)
                    val duplicate = outcome.action == LedgerDatabase.DetectedCaptureAction.IGNORED_DUPLICATE ||
                        outcome.action == LedgerDatabase.DetectedCaptureAction.IGNORED_CONFIRMED
                    return SmartPasteOutcome.CapturedPending(
                        formattedAmount = formatted,
                        merchant = outcome.merchant ?: parsed.merchant,
                        txType = parsed.txType,
                        duplicateSkipped = duplicate,
                    )
                }
            }

            NotificationParser.NotificationParseKind.IGNORED -> Unit
        }

        // Fallback: check if ambiguous/partial monetary text exists that user can confirm in overlay
        val amountMatch = partialAmountRegex.find(cleaned)
        val rawNum = amountMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }.orEmpty()
        val detectedCurrency = detectPartialCurrency(cleaned, vault.baseCurrency)
        val parsedMinor = if (rawNum.isNotBlank()) {
            MoneyFormatter.parseDecimalToMinor(rawNum.replace(",", ""), detectedCurrency)
        } else null

        if (parsedMinor != null && parsedMinor > 0L) {
            val hasContextHint = partialCreditKeywordRegex.containsMatchIn(cleaned) ||
                partialDebitKeywordRegex.containsMatchIn(cleaned)
            val txType = if (partialCreditKeywordRegex.containsMatchIn(cleaned)) "INCOME" else "EXPENSE"
            if (hasContextHint || cleaned.length <= 120) {
                return SmartPasteOutcome.NeedsConfirmation(
                    prefillAmountMinor = parsedMinor,
                    prefillCurrency = detectedCurrency,
                    prefillTxType = txType,
                    prefillMerchant = "",
                    rawNote = cleaned.take(160),
                )
            }
        }

        return SmartPasteOutcome.UnsupportedContent
    }

    private fun detectPartialCurrency(text: String, fallback: String): String {
        val upper = text.uppercase()
        return when {
            text.contains("₹") || upper.contains("INR") || Regex("""(?i)\brs\.?\b""").containsMatchIn(text) -> "INR"
            text.contains("$") || upper.contains("USD") -> "USD"
            text.contains("€") || upper.contains("EUR") -> "EUR"
            text.contains("£") || upper.contains("GBP") -> "GBP"
            else -> fallback
        }
    }
}
