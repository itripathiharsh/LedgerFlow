package com.ledgerflow.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Ensures all pinned LedgerFlow widgets re-bind after device reboot or APK upgrade.
 */
class WidgetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        val pending: PendingResult? = goAsync()
        receiverScope.launch {
            try {
                LedgerWidgetCoordinator.refreshAllWidgetsNow(appContext)
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        val receiverScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
        )
    }
}
