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
 * Widget A — Compact Action Bar (4x2).
 * Renders Today's Spend, Liquid Balance, and zero-friction quick actions.
 */
class CompactActionBarWidgetProvider : AppWidgetProvider() {
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
                    isShoppingWidget = false,
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
                    isShoppingWidget = false,
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
