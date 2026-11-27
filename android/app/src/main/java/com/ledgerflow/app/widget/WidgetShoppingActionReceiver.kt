package com.ledgerflow.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Internal non-exported BroadcastReceiver handling direct shopping-item checkbox taps from
 * Widget B without launching any Activity.
 */
class WidgetShoppingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TOGGLE_SHOPPING_ITEM) return
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)?.trim().orEmpty()
        if (itemId.isBlank()) return
        if (!inFlightItemIds.add(itemId)) return

        val appContext = context.applicationContext
        val pendingResult: PendingResult? = goAsync()
        receiverScope.launch {
            try {
                val db = LedgerDatabase.getInstance(appContext)
                val updated = db.toggleShoppingItemCheckedAtomic(itemId)
                if (updated != null) {
                    runCatching { SyncWorker.enqueueWhenOnline(appContext) }
                }
                LedgerWidgetCoordinator.refreshAllWidgetsNow(appContext)
            } finally {
                inFlightItemIds.remove(itemId)
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_SHOPPING_ITEM = "com.ledgerflow.app.widget.ACTION_TOGGLE_SHOPPING_ITEM"
        const val EXTRA_ITEM_ID = "extra_shopping_item_id"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inFlightItemIds = ConcurrentHashMap.newKeySet<String>()

        fun toggleIntent(context: Context, itemId: String): Intent =
            Intent(context, WidgetShoppingActionReceiver::class.java).apply {
                action = ACTION_TOGGLE_SHOPPING_ITEM
                putExtra(EXTRA_ITEM_ID, itemId)
            }
    }
}
