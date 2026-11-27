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
 * Widget 1 — Finance Widget (4x2 to 4x3).
 * Renders Today's Spend, Liquid Balance, optional monthly cash flow, and 1-tap Expense, Voice, Income & Smart Paste.
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
                LedgerWidgetCoordinator.refreshSpecificProviderIds(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds = appWidgetIds,
                    kind = LedgerWidgetKind.FINANCE_BAR,
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
                    kind = LedgerWidgetKind.FINANCE_BAR,
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
