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
 * Widget 4 — Quick Expense Capsule (3x1 / 4x1).
 * Sleek horizontal launcher strip with instant + Expense, Voice Expense, and Quick Income triggers.
 */
class QuickExpenseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        dispatchRefresh(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        dispatchRefresh(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun dispatchRefresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pendingResult: PendingResult? = goAsync()
        providerScope.launch {
            try {
                LedgerWidgetCoordinator.refreshSpecificProviderIds(
                    context = context,
                    appWidgetManager = manager,
                    appWidgetIds = ids,
                    kind = LedgerWidgetKind.QUICK_EXPENSE,
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
