package com.ledgerflow.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Widget B — Spend + Active Shopping List (4x3).
 * Displays financial metrics, quick actions, and up to 3 interactive unpurchased items.
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
                LedgerWidgetCoordinator.refreshProviderIds(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds = appWidgetIds,
                    isShoppingWidget = true,
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
                LedgerWidgetCoordinator.refreshProviderIds(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds = intArrayOf(appWidgetId),
                    isShoppingWidget = true,
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private companion object {
        val providerScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
        )
    }
}
