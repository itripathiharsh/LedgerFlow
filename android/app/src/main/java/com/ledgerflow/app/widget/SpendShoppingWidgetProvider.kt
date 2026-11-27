package com.ledgerflow.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.ledgerflow.app.security.VaultStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Widget 2 — Dedicated Shopping List Widget (4x2 to 4x4).
 * Renders selectable checklist, visual completion progress, estimated total,
 * interactive items with qty/price, + Add Item and Open List actions.
 */
class SpendShoppingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult: PendingResult? = goAsync()
        providerScope.launch {
            try {
                LedgerWidgetCoordinator.refreshSpecificProviderIds(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds = appWidgetIds,
                    kind = LedgerWidgetKind.SHOPPING_LIST,
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pendingResult: PendingResult? = goAsync()
        providerScope.launch {
            try {
                LedgerWidgetCoordinator.refreshSpecificProviderIds(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds = intArrayOf(appWidgetId),
                    kind = LedgerWidgetKind.SHOPPING_LIST,
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val appCtx = context.applicationContext
        val pending: PendingResult? = goAsync()
        providerScope.launch {
            try {
                val keys = appWidgetIds.map { it.toString() }.toSet()
                VaultStore(appCtx).update { state ->
                    state.copy(widgetListSelections = state.widgetListSelections - keys)
                }
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        val providerScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
        )
    }
}
