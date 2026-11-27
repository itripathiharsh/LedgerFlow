package com.ledgerflow.app.widget

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.ledgerflow.app.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent focus-aware activity launched from Smart Paste widget action.
 * Respects Android 10+ clipboard rules by waiting for [onWindowFocusChanged](true)
 * before reading clipboard text locally.
 */
class SmartPasteTrampolineActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var clipboardHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Safety timeout so trampoline never stalls visually if launcher suppresses focus briefly
        handler.postDelayed(
            {
                if (!clipboardHandled && !isFinishing) {
                    processClipboardNow()
                }
            },
            450L,
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !clipboardHandled) {
            processClipboardNow()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun processClipboardNow() {
        if (clipboardHandled) return
        clipboardHandled = true
        handler.removeCallbacksAndMessages(null)

        val clipText = readClipboardTextSafely()
        val appCtx = applicationContext

        activityScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                SmartPasteProcessor.evaluateAndCapture(
                    context = appCtx,
                    rawClipboardText = clipText,
                )
            }
            when (outcome) {
                is SmartPasteOutcome.EmptyClipboard -> {
                    Toast.makeText(
                        appCtx,
                        "Clipboard empty — copy a bank or UPI alert first",
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                is SmartPasteOutcome.IgnoredDeclinedAlert -> {
                    withContext(Dispatchers.IO) {
                        LedgerWidgetCoordinator.refreshAllWidgetsNow(appCtx)
                    }
                    Toast.makeText(
                        appCtx,
                        "Ignored declined or non-completed alert",
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                is SmartPasteOutcome.CapturedPending -> {
                    withContext(Dispatchers.IO) {
                        runCatching { SyncWorker.enqueueWhenOnline(appCtx) }
                        LedgerWidgetCoordinator.refreshAllWidgetsNow(appCtx)
                    }
                    val merchantSuffix = outcome.merchant?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
                    val msg = if (outcome.duplicateSkipped) {
                        "Already captured ${outcome.formattedAmount}$merchantSuffix"
                    } else {
                        "Captured ${outcome.formattedAmount}$merchantSuffix • Pending Review"
                    }
                    Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
                }

                is SmartPasteOutcome.NeedsConfirmation -> {
                    startActivity(
                        QuickTransactionOverlayActivity.newIntent(
                            context = this@SmartPasteTrampolineActivity,
                            txType = outcome.prefillTxType,
                            prefillAmountMinor = outcome.prefillAmountMinor,
                            prefillCurrency = outcome.prefillCurrency,
                            prefillMerchant = outcome.prefillMerchant,
                            prefillNote = outcome.rawNote,
                            prefillSourceTag = "IMPORT",
                        )
                    )
                }

                is SmartPasteOutcome.UnsupportedContent -> {
                    Toast.makeText(
                        appCtx,
                        "No valid payment alert found in clipboard",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            finishWithoutAnimation()
        }
    }

    private fun readClipboardTextSafely(): String {
        return runCatching {
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip ?: return ""
            if (clip.itemCount <= 0) return ""
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun finishWithoutAnimation() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, SmartPasteTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
    }
}
