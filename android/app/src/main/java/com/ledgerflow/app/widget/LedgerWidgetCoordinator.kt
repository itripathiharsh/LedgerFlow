package com.ledgerflow.app.widget

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.ledgerflow.app.MainActivity
import com.ledgerflow.app.R
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.ShoppingItemEntity
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.ParsedShoppingItem
import com.ledgerflow.app.domain.WidgetMetricsCalculator
import com.ledgerflow.app.security.VaultSessionState
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.ui.theme.ThemeModes
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class WidgetThemeStyle {
    FLOW_DARK,
    PREMIUM_BLACK,
    LIGHT,
}

data class WidgetShoppingRow(
    val id: String,
    val displayText: String,
)

data class WidgetRenderSnapshot(
    val themeStyle: WidgetThemeStyle,
    val baseCurrency: String,
    val todaySpendDisplay: String,
    val liquidBalanceDisplay: String,
    val isMasked: Boolean,
    val hasActiveList: Boolean,
    val activeListId: String?,
    val activeListTitle: String,
    val checkedCount: Int,
    val totalCount: Int,
    val uncheckedRows: List<WidgetShoppingRow>,
)

/**
 * Central asynchronous coordinator keeping Widget A (4x2) and Widget B (4x3) synchronized
 * with Room state, theme selection, Mask Balances privacy, and lock-screen restrictions.
 */
object LedgerWidgetCoordinator {
    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val refreshMutex = Mutex()

    @Volatile
    private var debouncedJob: Job? = null

    fun cancelPendingRefreshes() {
        debouncedJob?.cancel()
        debouncedJob = null
    }

    fun resolveWidgetTheme(themeMode: String, systemDark: Boolean): WidgetThemeStyle {
        return when (ThemeModes.normalize(themeMode)) {
            ThemeModes.LIGHT -> WidgetThemeStyle.LIGHT
            ThemeModes.FLOW_DARK -> WidgetThemeStyle.FLOW_DARK
            ThemeModes.PREMIUM_DARK -> WidgetThemeStyle.PREMIUM_BLACK
            else -> if (systemDark) WidgetThemeStyle.FLOW_DARK else WidgetThemeStyle.LIGHT
        }
    }

    fun isSystemDarkTheme(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun isDeviceLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return keyguard?.isKeyguardLocked == true || keyguard?.isDeviceLocked == true
    }

    fun shouldMaskBalances(vault: VaultSessionState, deviceLocked: Boolean): Boolean =
        vault.hideBalances || (deviceLocked && !vault.showWidgetsOnLockScreen)

    /**
     * Requests a coalesced background refresh for all active LedgerFlow widgets.
     * Safe to invoke from any thread or after any Room/Vault mutation.
     */
    fun requestRefresh(context: Context, debounceMs: Long = 120L) {
        val appContext = context.applicationContext
        debouncedJob?.cancel()
        debouncedJob = coordinatorScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            refreshAllWidgetsNow(appContext)
        }
    }

    suspend fun refreshAllWidgetsNow(
        context: Context,
        dbOverride: LedgerDatabase? = null,
        vaultOverride: VaultStore? = null,
        appWidgetManagerOverride: AppWidgetManager? = null,
    ) {
        val appContext = context.applicationContext
        refreshMutex.withLock {
            val manager = appWidgetManagerOverride ?: AppWidgetManager.getInstance(appContext)
            val compactIds = manager.getAppWidgetIds(
                ComponentName(appContext, CompactActionBarWidgetProvider::class.java)
            )
            val shoppingIds = manager.getAppWidgetIds(
                ComponentName(appContext, SpendShoppingWidgetProvider::class.java)
            )
            if (compactIds.isEmpty() && shoppingIds.isEmpty()) return@withLock

            val snapshot = loadSnapshot(
                context = appContext,
                dbOverride = dbOverride,
                vaultOverride = vaultOverride,
            )

            withContext(Dispatchers.Main.immediate) {
                for (id in compactIds) {
                    val options = manager.getAppWidgetOptions(id)
                    val views = buildCompactRemoteViews(appContext, snapshot, options)
                    manager.updateAppWidget(id, views)
                }
                for (id in shoppingIds) {
                    val options = manager.getAppWidgetOptions(id)
                    val views = buildShoppingRemoteViews(appContext, snapshot, options)
                    manager.updateAppWidget(id, views)
                }
            }
        }
    }

    suspend fun refreshProviderIds(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        isShoppingWidget: Boolean,
        dbOverride: LedgerDatabase? = null,
        vaultOverride: VaultStore? = null,
    ) {
        if (appWidgetIds.isEmpty()) return
        val appContext = context.applicationContext
        refreshMutex.withLock {
            val snapshot = loadSnapshot(
                context = appContext,
                dbOverride = dbOverride,
                vaultOverride = vaultOverride,
            )
            withContext(Dispatchers.Main.immediate) {
                for (id in appWidgetIds) {
                    val options = appWidgetManager.getAppWidgetOptions(id)
                    val views = if (isShoppingWidget) {
                        buildShoppingRemoteViews(appContext, snapshot, options)
                    } else {
                        buildCompactRemoteViews(appContext, snapshot, options)
                    }
                    appWidgetManager.updateAppWidget(id, views)
                }
            }
        }
    }

    suspend fun loadSnapshot(
        context: Context,
        dbOverride: LedgerDatabase? = null,
        vaultOverride: VaultStore? = null,
        nowMs: Long = System.currentTimeMillis(),
        deviceLockedOverride: Boolean? = null,
    ): WidgetRenderSnapshot {
        val vaultStore = vaultOverride ?: VaultStore(context)
        val vault = vaultStore.read()
        val db = dbOverride ?: LedgerDatabase.getInstance(context)

        val accounts = db.accountDao().getAllActive()
        val transactions = db.transactionDao().getAllActive()
        val lists = db.shoppingListDao().getAllActive()

        val activeList = lists
            .filter { it.deletedAtEpochMs == null }
            .maxByOrNull { it.updatedAtEpochMs }

        val listItems: List<ShoppingItemEntity> = if (activeList != null) {
            db.shoppingItemDao().getActiveOrderedForList(activeList.id)
        } else {
            emptyList()
        }

        val metrics = WidgetMetricsCalculator.computeWidgetMetrics(
            accounts = accounts,
            transactions = transactions,
            baseCurrency = vault.baseCurrency,
            nowMs = nowMs,
        )

        val deviceLocked = deviceLockedOverride ?: isDeviceLocked(context)
        val masked = shouldMaskBalances(vault = vault, deviceLocked = deviceLocked)
        val todayStr = MoneyFormatter.formatMinor(metrics.todaySpendMinor, vault.baseCurrency, masked = masked)
        val liquidStr = MoneyFormatter.formatMinor(metrics.liquidBalanceMinor, vault.baseCurrency, masked = masked)

        val uncheckedRows = listItems
            .filter { !it.checked }
            .map { item ->
                WidgetShoppingRow(
                    id = item.id,
                    displayText = ParsedShoppingItem.formatDisplay(item.name, item.quantityLabel),
                )
            }
        val checkedCount = listItems.count { it.checked }

        return WidgetRenderSnapshot(
            themeStyle = resolveWidgetTheme(vault.themeMode, isSystemDarkTheme(context)),
            baseCurrency = vault.baseCurrency.uppercase(),
            todaySpendDisplay = todayStr,
            liquidBalanceDisplay = liquidStr,
            isMasked = masked,
            hasActiveList = activeList != null,
            activeListId = activeList?.id,
            activeListTitle = activeList?.title?.takeIf { it.isNotBlank() } ?: "Shopping List",
            checkedCount = checkedCount,
            totalCount = listItems.size,
            uncheckedRows = uncheckedRows,
        )
    }

    fun buildCompactRemoteViews(
        context: Context,
        snapshot: WidgetRenderSnapshot,
        options: Bundle? = null,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact_action_bar)
        bindCommonHeaderMetricsAndActions(views, context, snapshot, options)
        return views
    }

    fun buildShoppingRemoteViews(
        context: Context,
        snapshot: WidgetRenderSnapshot,
        options: Bundle? = null,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_spend_shopping_list)
        val tokens = paletteTokens(snapshot.themeStyle)
        bindCommonHeaderMetricsAndActions(views, context, snapshot, options)

        views.setInt(R.id.widget_section_divider, "setBackgroundColor", tokens.dividerColor)
        views.setTextColor(R.id.shopping_list_title, tokens.mutedColor)

        val openListsPi = PendingIntent.getActivity(
            context,
            REQ_OPEN_SHOPPING_LISTS,
            MainActivity.widgetLaunchIntent(context, route = MainActivity.ROUTE_SHOPPING_LISTS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.shopping_header_row, openListsPi)

        if (!snapshot.hasActiveList) {
            views.setTextViewText(R.id.shopping_list_title, "ACTIVE SHOPPING LIST")
            views.setViewVisibility(R.id.shopping_progress_badge, View.GONE)
            views.setViewVisibility(R.id.shopping_empty_container, View.VISIBLE)
            views.setViewVisibility(R.id.shopping_all_done_container, View.GONE)
            views.setViewVisibility(R.id.shopping_items_container, View.GONE)
            views.setTextColor(R.id.shopping_empty_text, tokens.mutedColor)
            views.setTextColor(R.id.btn_create_shopping_list, tokens.accentColor)

            val createListPi = PendingIntent.getActivity(
                context,
                REQ_CREATE_SHOPPING_LIST,
                MainActivity.widgetLaunchIntent(
                    context = context,
                    route = MainActivity.ROUTE_SHOPPING_LISTS,
                    openCreateShoppingList = true,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.btn_create_shopping_list, createListPi)
            views.setOnClickPendingIntent(R.id.shopping_empty_container, createListPi)
            return views
        }

        views.setTextViewText(
            R.id.shopping_list_title,
            snapshot.activeListTitle.uppercase(),
        )
        views.setViewVisibility(R.id.shopping_progress_badge, View.VISIBLE)
        views.setTextViewText(R.id.shopping_progress_badge, "${snapshot.checkedCount}/${snapshot.totalCount}")
        views.setTextColor(R.id.shopping_progress_badge, tokens.accentColor)
        views.setViewVisibility(R.id.shopping_empty_container, View.GONE)

        if (snapshot.uncheckedRows.isEmpty()) {
            views.setViewVisibility(R.id.shopping_items_container, View.GONE)
            views.setViewVisibility(R.id.shopping_all_done_container, View.VISIBLE)
            views.setTextColor(R.id.shopping_all_done_text, tokens.mutedColor)
            val allDoneMsg = if (snapshot.totalCount == 0) {
                "${snapshot.activeListTitle} is empty • Tap Voice or Open List"
            } else {
                "All ${snapshot.totalCount} items bought • Tap to review or log expense"
            }
            views.setTextViewText(R.id.shopping_all_done_text, allDoneMsg)
            views.setOnClickPendingIntent(R.id.shopping_all_done_container, openListsPi)
            return views
        }

        views.setViewVisibility(R.id.shopping_all_done_container, View.GONE)
        views.setViewVisibility(R.id.shopping_items_container, View.VISIBLE)

        val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180) ?: 180
        val maxRows = if (minHeightDp in 1..164) 2 else 3

        val rowIds = listOf(R.id.shopping_row_0, R.id.shopping_row_1, R.id.shopping_row_2)
        val checkIds = listOf(R.id.shopping_check_0, R.id.shopping_check_1, R.id.shopping_check_2)
        val textIds = listOf(R.id.shopping_item_text_0, R.id.shopping_item_text_1, R.id.shopping_item_text_2)

        for (index in 0 until 3) {
            val row = snapshot.uncheckedRows.getOrNull(index)
            if (index < maxRows && row != null) {
                views.setViewVisibility(rowIds[index], View.VISIBLE)
                views.setTextViewText(textIds[index], row.displayText)
                views.setTextColor(textIds[index], tokens.primaryInkColor)
                views.setInt(checkIds[index], "setColorFilter", tokens.mutedColor)

                val togglePi = PendingIntent.getBroadcast(
                    context,
                    REQ_TOGGLE_ITEM_BASE + index,
                    WidgetShoppingActionReceiver.toggleIntent(context, row.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(rowIds[index], togglePi)
            } else {
                views.setViewVisibility(rowIds[index], View.GONE)
            }
        }

        val remainingCount = (snapshot.uncheckedRows.size - maxRows).coerceAtLeast(0)
        if (remainingCount > 0) {
            views.setViewVisibility(R.id.shopping_more_count, View.VISIBLE)
            views.setTextViewText(R.id.shopping_more_count, "+$remainingCount more items")
            views.setTextColor(R.id.shopping_more_count, tokens.mutedColor)
            views.setOnClickPendingIntent(R.id.shopping_more_count, openListsPi)
        } else {
            views.setViewVisibility(R.id.shopping_more_count, View.GONE)
        }
        return views
    }

    private fun bindCommonHeaderMetricsAndActions(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetRenderSnapshot,
        options: Bundle?,
    ) {
        val tokens = paletteTokens(snapshot.themeStyle)

        views.setInt(R.id.widget_root, "setBackgroundResource", tokens.backgroundRes)
        views.setTextColor(R.id.widget_brand_label, tokens.accentColor)
        views.setViewVisibility(R.id.widget_lock_icon, if (snapshot.isMasked) View.VISIBLE else View.GONE)
        views.setInt(R.id.widget_lock_icon, "setColorFilter", tokens.mutedColor)

        views.setInt(R.id.widget_currency_pill, "setBackgroundResource", tokens.chipRes)
        views.setTextColor(R.id.widget_currency_pill, tokens.secondaryLabelColor)
        views.setTextViewText(R.id.widget_currency_pill, snapshot.baseCurrency)

        views.setTextColor(R.id.widget_today_spend_label, tokens.mutedColor)
        views.setTextColor(R.id.widget_today_spend_value, tokens.primaryInkColor)
        views.setTextViewText(R.id.widget_today_spend_value, snapshot.todaySpendDisplay)

        views.setInt(R.id.widget_metrics_divider, "setBackgroundColor", tokens.dividerColor)

        views.setTextColor(R.id.widget_liquid_balance_label, tokens.mutedColor)
        views.setTextColor(R.id.widget_liquid_balance_value, tokens.accentColor)
        views.setTextViewText(R.id.widget_liquid_balance_value, snapshot.liquidBalanceDisplay)

        val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) ?: 250
        views.setTextViewText(R.id.btn_add_expense, if (minWidthDp in 1..209) "+ Exp" else "+ Expense")

        views.setInt(R.id.btn_voice_list, "setBackgroundResource", tokens.circleBtnRes)
        views.setInt(R.id.btn_smart_paste, "setBackgroundResource", tokens.circleBtnRes)
        views.setInt(R.id.btn_add_income, "setBackgroundResource", tokens.circleBtnRes)
        views.setInt(R.id.icon_voice_list, "setColorFilter", tokens.primaryInkColor)
        views.setInt(R.id.icon_smart_paste, "setColorFilter", tokens.primaryInkColor)
        views.setInt(R.id.icon_add_income, "setColorFilter", tokens.accentColor)

        val openAppPi = PendingIntent.getActivity(
            context,
            REQ_OPEN_HOME,
            MainActivity.widgetLaunchIntent(context, route = "home"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_header_container, openAppPi)
        views.setOnClickPendingIntent(R.id.widget_metrics_row, openAppPi)

        val addExpensePi = PendingIntent.getActivity(
            context,
            REQ_ADD_EXPENSE,
            QuickTransactionOverlayActivity.newIntent(context, txType = "EXPENSE"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_add_expense, addExpensePi)

        val voiceListPi = PendingIntent.getActivity(
            context,
            REQ_VOICE_LIST,
            VoiceShoppingOverlayActivity.newIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_voice_list, voiceListPi)

        val smartPastePi = PendingIntent.getActivity(
            context,
            REQ_SMART_PASTE,
            SmartPasteTrampolineActivity.newIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_smart_paste, smartPastePi)

        val addIncomePi = PendingIntent.getActivity(
            context,
            REQ_ADD_INCOME,
            QuickTransactionOverlayActivity.newIntent(context, txType = "INCOME"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_add_income, addIncomePi)
    }

    private data class WidgetPaletteTokens(
        val backgroundRes: Int,
        val circleBtnRes: Int,
        val chipRes: Int,
        val primaryInkColor: Int,
        val secondaryLabelColor: Int,
        val mutedColor: Int,
        val dividerColor: Int,
        val accentColor: Int,
    )

    private fun paletteTokens(style: WidgetThemeStyle): WidgetPaletteTokens = when (style) {
        WidgetThemeStyle.FLOW_DARK -> WidgetPaletteTokens(
            backgroundRes = R.drawable.widget_bg_flow_dark,
            circleBtnRes = R.drawable.widget_circle_btn_flow_dark,
            chipRes = R.drawable.widget_chip_flow_dark,
            primaryInkColor = 0xFFEBF6F0.toInt(),
            secondaryLabelColor = 0xFFA7C4B5.toInt(),
            mutedColor = 0xFF7DA894.toInt(),
            dividerColor = 0xFF1D352B.toInt(),
            accentColor = 0xFF10B981.toInt(),
        )

        WidgetThemeStyle.PREMIUM_BLACK -> WidgetPaletteTokens(
            backgroundRes = R.drawable.widget_bg_premium_black,
            circleBtnRes = R.drawable.widget_circle_btn_premium_black,
            chipRes = R.drawable.widget_chip_premium_black,
            primaryInkColor = 0xFFFAFAFC.toInt(),
            secondaryLabelColor = 0xFFD1D5DB.toInt(),
            mutedColor = 0xFF9CA3AF.toInt(),
            dividerColor = 0xFF23232C.toInt(),
            accentColor = 0xFF10B981.toInt(),
        )

        WidgetThemeStyle.LIGHT -> WidgetPaletteTokens(
            backgroundRes = R.drawable.widget_bg_light,
            circleBtnRes = R.drawable.widget_circle_btn_light,
            chipRes = R.drawable.widget_chip_light,
            primaryInkColor = 0xFF0F172A.toInt(),
            secondaryLabelColor = 0xFF334155.toInt(),
            mutedColor = 0xFF64748B.toInt(),
            dividerColor = 0xFFE2E8F0.toInt(),
            accentColor = 0xFF059669.toInt(),
        )
    }

    private const val REQ_OPEN_HOME = 101
    private const val REQ_ADD_EXPENSE = 102
    private const val REQ_VOICE_LIST = 103
    private const val REQ_SMART_PASTE = 104
    private const val REQ_ADD_INCOME = 105
    private const val REQ_OPEN_SHOPPING_LISTS = 106
    private const val REQ_CREATE_SHOPPING_LIST = 107
    private const val REQ_TOGGLE_ITEM_BASE = 200
}
