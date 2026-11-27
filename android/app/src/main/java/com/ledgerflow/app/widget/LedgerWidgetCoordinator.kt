package com.ledgerflow.app.widget

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.ledgerflow.app.MainActivity
import com.ledgerflow.app.R
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.ShoppingItemEntity
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.ShoppingUnitCatalog
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
import java.util.Calendar
import kotlin.math.abs

enum class WidgetThemeStyle {
    FLOW_DARK,
    PREMIUM_BLACK,
    LIGHT,
}

data class WidgetShoppingRow(
    val id: String,
    val name: String,
    val qtyLabel: String,
    val priceDisplay: String,
    val checked: Boolean,
) {
    val displayText: String
        get() = if (qtyLabel.isNotBlank()) "$qtyLabel $name" else name
}

data class WidgetRenderSnapshot(
    val themeStyle: WidgetThemeStyle,
    val baseCurrency: String,
    val todaySpendDisplay: String,
    val liquidBalanceDisplay: String,
    val monthlyInflowDisplay: String,
    val monthlyOutflowDisplay: String,
    val pendingReviewCount: Int,
    val isMasked: Boolean,
    val hasActiveList: Boolean,
    val activeListId: String?,
    val activeListTitle: String,
    val listCount: Int,
    val checkedCount: Int,
    val totalCount: Int,
    val estimatedTotalDisplay: String,
    val checklistRows: List<WidgetShoppingRow>,
) {
    val uncheckedRows: List<WidgetShoppingRow>
        get() = checklistRows.filter { !it.checked }
}

/**
 * Central asynchronous coordinator keeping all 4 LedgerFlow Home Screen Widgets
 * synchronized with Room state, active theme, Mask Balances privacy, and lock-screen rules.
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
            val financeBarIds = manager.getAppWidgetIds(ComponentName(appContext, CompactActionBarWidgetProvider::class.java))
            val shoppingIds = manager.getAppWidgetIds(ComponentName(appContext, SpendShoppingWidgetProvider::class.java))
            val miniFinanceIds = manager.getAppWidgetIds(ComponentName(appContext, CompactFinanceWidgetProvider::class.java))
            val quickExpenseIds = manager.getAppWidgetIds(ComponentName(appContext, QuickExpenseWidgetProvider::class.java))
            if (financeBarIds.isEmpty() && shoppingIds.isEmpty() && miniFinanceIds.isEmpty() && quickExpenseIds.isEmpty()) {
                return@withLock
            }

            val baseSnapshot = loadSnapshot(
                context = appContext,
                dbOverride = dbOverride,
                vaultOverride = vaultOverride,
            )

            val perWidgetShoppingSnapshots = if (shoppingIds.isNotEmpty()) {
                shoppingIds.associateWith { wid ->
                    loadSnapshot(
                        context = appContext,
                        dbOverride = dbOverride,
                        vaultOverride = vaultOverride,
                        appWidgetId = wid,
                    )
                }
            } else emptyMap()

            withContext(Dispatchers.Main.immediate) {
                for (id in financeBarIds) {
                    val options = manager.getAppWidgetOptions(id)
                    manager.updateAppWidget(id, buildCompactRemoteViews(appContext, baseSnapshot, options))
                }
                for (id in shoppingIds) {
                    val options = manager.getAppWidgetOptions(id)
                    val snap = perWidgetShoppingSnapshots[id] ?: baseSnapshot
                    manager.updateAppWidget(id, buildShoppingRemoteViews(appContext, snap, options, appWidgetId = id))
                }
                for (id in miniFinanceIds) {
                    val options = manager.getAppWidgetOptions(id)
                    manager.updateAppWidget(id, buildCompactFinanceRemoteViews(appContext, baseSnapshot, options))
                }
                for (id in quickExpenseIds) {
                    val options = manager.getAppWidgetOptions(id)
                    manager.updateAppWidget(id, buildQuickExpenseRemoteViews(appContext, baseSnapshot, options))
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
    ) = refreshSpecificProviderIds(
        context = context,
        appWidgetManager = appWidgetManager,
        appWidgetIds = appWidgetIds,
        kind = if (isShoppingWidget) LedgerWidgetKind.SHOPPING_LIST else LedgerWidgetKind.FINANCE_BAR,
        dbOverride = dbOverride,
        vaultOverride = vaultOverride,
    )

    suspend fun refreshSpecificProviderIds(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        kind: LedgerWidgetKind,
        dbOverride: LedgerDatabase? = null,
        vaultOverride: VaultStore? = null,
    ) {
        if (appWidgetIds.isEmpty()) return
        val appContext = context.applicationContext
        refreshMutex.withLock {
            val baseSnapshot = loadSnapshot(
                context = appContext,
                dbOverride = dbOverride,
                vaultOverride = vaultOverride,
            )
            val perWidgetSnapshots = if (kind == LedgerWidgetKind.SHOPPING_LIST) {
                appWidgetIds.associateWith { wid ->
                    loadSnapshot(
                        context = appContext,
                        dbOverride = dbOverride,
                        vaultOverride = vaultOverride,
                        appWidgetId = wid,
                    )
                }
            } else emptyMap()

            withContext(Dispatchers.Main.immediate) {
                for (id in appWidgetIds) {
                    val options = appWidgetManager.getAppWidgetOptions(id)
                    val snap = perWidgetSnapshots[id] ?: baseSnapshot
                    val views = when (kind) {
                        LedgerWidgetKind.FINANCE_BAR -> buildCompactRemoteViews(appContext, snap, options)
                        LedgerWidgetKind.SHOPPING_LIST -> buildShoppingRemoteViews(appContext, snap, options, appWidgetId = id)
                        LedgerWidgetKind.COMPACT_FINANCE -> buildCompactFinanceRemoteViews(appContext, snap, options)
                        LedgerWidgetKind.QUICK_EXPENSE -> buildQuickExpenseRemoteViews(appContext, snap, options)
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
        appWidgetId: Int? = null,
    ): WidgetRenderSnapshot {
        val vaultStore = vaultOverride ?: VaultStore(context)
        val vault = vaultStore.read()
        val db = dbOverride ?: LedgerDatabase.getInstance(context)

        val accounts = db.accountDao().getAllActive()
        val transactions = db.transactionDao().getAllActive()
        val lists = db.shoppingListDao().getAllActive()
            .filter { it.deletedAtEpochMs == null }
            .sortedByDescending { it.updatedAtEpochMs }

        val selectedListId = appWidgetId
            ?.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
            ?.let { wid -> vault.widgetListSelections[wid.toString()] }
        val activeList = selectedListId?.let { id -> lists.firstOrNull { it.id == id } }
            ?: lists.firstOrNull()

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

        val monthStartMs = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        var monthlyInflow = 0L
        var monthlyOutflow = 0L
        var pendingCount = 0
        for (tx in transactions) {
            if (tx.deletedAtEpochMs != null) continue
            if (tx.reviewStatus == TransactionEntity.STATUS_PENDING_REVIEW) pendingCount++
            if (tx.occurredAtEpochMs >= monthStartMs) {
                val baseMag = abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, vault.baseCurrency))
                when (tx.txType) {
                    "INCOME" -> monthlyInflow += baseMag
                    "EXPENSE" -> monthlyOutflow += baseMag
                }
            }
        }

        val deviceLocked = deviceLockedOverride ?: isDeviceLocked(context)
        val masked = shouldMaskBalances(vault = vault, deviceLocked = deviceLocked)
        val todayStr = MoneyFormatter.formatMinor(metrics.todaySpendMinor, vault.baseCurrency, masked = masked)
        val liquidStr = MoneyFormatter.formatMinor(metrics.liquidBalanceMinor, vault.baseCurrency, masked = masked)
        val monthlyInStr = if (masked) "••••" else "+${MoneyFormatter.formatMinor(monthlyInflow, vault.baseCurrency)}"
        val monthlyOutStr = if (masked) "••••" else "-${MoneyFormatter.formatMinor(monthlyOutflow, vault.baseCurrency)}"

        val orderedRows = listItems
            .sortedWith(compareBy<ShoppingItemEntity> { it.checked }.thenBy { it.createdAtEpochMs })
            .map { item ->
                val qtyStr = ShoppingUnitCatalog.formatQuantityLabel(
                    item.quantityValue,
                    item.quantityUnit,
                    item.quantityLabel,
                )
                val priceStr = when {
                    item.estimatedPriceMinor <= 0L -> ""
                    masked -> "••••"
                    else -> MoneyFormatter.formatMinor(item.estimatedPriceMinor, vault.baseCurrency)
                }
                WidgetShoppingRow(
                    id = item.id,
                    name = item.name,
                    qtyLabel = qtyStr,
                    priceDisplay = priceStr,
                    checked = item.checked,
                )
            }
        val checkedCount = listItems.count { it.checked }
        val estimatedTotalMinor = listItems.sumOf { it.estimatedPriceMinor.coerceAtLeast(0L) }
        val estimatedTotalDisplay = if (masked) "••••" else MoneyFormatter.formatMinor(estimatedTotalMinor, vault.baseCurrency)

        return WidgetRenderSnapshot(
            themeStyle = resolveWidgetTheme(vault.themeMode, isSystemDarkTheme(context)),
            baseCurrency = vault.baseCurrency.uppercase(),
            todaySpendDisplay = todayStr,
            liquidBalanceDisplay = liquidStr,
            monthlyInflowDisplay = monthlyInStr,
            monthlyOutflowDisplay = monthlyOutStr,
            pendingReviewCount = pendingCount,
            isMasked = masked,
            hasActiveList = activeList != null,
            activeListId = activeList?.id,
            activeListTitle = activeList?.title?.takeIf { it.isNotBlank() } ?: "Shopping List",
            listCount = lists.size,
            checkedCount = checkedCount,
            totalCount = listItems.size,
            estimatedTotalDisplay = estimatedTotalDisplay,
            checklistRows = orderedRows,
        )
    }

    /**
     * Widget 1 — Finance Widget (4x2 - 4x3)
     */
    fun buildCompactRemoteViews(
        context: Context,
        snapshot: WidgetRenderSnapshot,
        options: Bundle? = null,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact_action_bar)
        val tokens = paletteTokens(snapshot.themeStyle)

        views.setInt(R.id.widget_root, "setBackgroundResource", tokens.backgroundRes)
        views.setTextColor(R.id.widget_brand_label, tokens.accentColor)
        views.setViewVisibility(R.id.widget_lock_icon, if (snapshot.isMasked) View.VISIBLE else View.GONE)
        views.setInt(R.id.widget_lock_icon, "setColorFilter", tokens.mutedColor)

        views.setInt(R.id.widget_currency_pill, "setBackgroundResource", tokens.chipRes)
        views.setTextColor(R.id.widget_currency_pill, tokens.secondaryLabelColor)
        views.setTextViewText(R.id.widget_currency_pill, snapshot.baseCurrency)

        if (snapshot.pendingReviewCount > 0 && !snapshot.isMasked) {
            views.setViewVisibility(R.id.widget_pending_pill, View.VISIBLE)
            views.setInt(R.id.widget_pending_pill, "setBackgroundResource", tokens.chipRes)
            views.setTextViewText(R.id.widget_pending_pill, "${snapshot.pendingReviewCount} PENDING")
        } else {
            views.setViewVisibility(R.id.widget_pending_pill, View.GONE)
        }

        views.setInt(R.id.widget_spend_well, "setBackgroundResource", tokens.wellRes)
        views.setTextColor(R.id.widget_today_spend_label, tokens.mutedColor)
        views.setTextColor(R.id.widget_today_spend_value, tokens.primaryInkColor)
        views.setTextViewText(R.id.widget_today_spend_value, snapshot.todaySpendDisplay)

        views.setInt(R.id.widget_balance_well, "setBackgroundResource", tokens.wellRes)
        views.setTextColor(R.id.widget_liquid_balance_label, tokens.mutedColor)
        views.setTextColor(R.id.widget_liquid_balance_value, tokens.accentColor)
        views.setTextViewText(R.id.widget_liquid_balance_value, snapshot.liquidBalanceDisplay)

        val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120) ?: 120
        val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260) ?: 260

        if (minHeightDp >= 180) {
            views.setViewVisibility(R.id.widget_monthly_strip, View.VISIBLE)
            views.setInt(R.id.widget_monthly_strip, "setBackgroundResource", tokens.wellRes)
            views.setTextColor(R.id.widget_monthly_label, tokens.mutedColor)
            views.setTextViewText(R.id.widget_monthly_inflow, snapshot.monthlyInflowDisplay)
            views.setTextViewText(R.id.widget_monthly_outflow, snapshot.monthlyOutflowDisplay)
        } else {
            views.setViewVisibility(R.id.widget_monthly_strip, View.GONE)
        }

        views.setTextViewText(R.id.btn_add_expense, if (minWidthDp in 1..215) "+ Exp" else "+ Expense")
        views.setViewVisibility(R.id.btn_smart_paste, if (minWidthDp in 1..225) View.GONE else View.VISIBLE)

        views.setInt(R.id.btn_voice_list, "setBackgroundResource", tokens.circleBtnRes)
        views.setInt(R.id.btn_smart_paste, "setBackgroundResource", tokens.circleBtnRes)
        views.setInt(R.id.btn_add_income, "setBackgroundResource", tokens.circleBtnRes)
        views.setInt(R.id.icon_voice_list, "setColorFilter", tokens.primaryInkColor)
        views.setInt(R.id.icon_smart_paste, "setColorFilter", tokens.primaryInkColor)
        views.setInt(R.id.icon_add_income, "setColorFilter", tokens.accentColor)

        val openAppPi = PendingIntent.getActivity(
            context,
            REQ_OPEN_HOME,
            MainActivity.widgetLaunchIntent(context, route = MainActivity.ROUTE_HOME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_header_container, openAppPi)
        views.setOnClickPendingIntent(R.id.widget_metrics_row, openAppPi)
        views.setOnClickPendingIntent(R.id.widget_monthly_strip, openAppPi)

        val addExpensePi = PendingIntent.getActivity(
            context,
            REQ_ADD_EXPENSE,
            QuickTransactionOverlayActivity.newIntent(context, txType = "EXPENSE"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_add_expense, addExpensePi)

        val voiceExpensePi = PendingIntent.getActivity(
            context,
            REQ_VOICE_EXPENSE,
            QuickTransactionOverlayActivity.newIntent(context, txType = "EXPENSE", startVoice = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_voice_list, voiceExpensePi)

        val addIncomePi = PendingIntent.getActivity(
            context,
            REQ_ADD_INCOME,
            QuickTransactionOverlayActivity.newIntent(context, txType = "INCOME"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_add_income, addIncomePi)

        val smartPastePi = PendingIntent.getActivity(
            context,
            REQ_SMART_PASTE,
            SmartPasteTrampolineActivity.newIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_smart_paste, smartPastePi)

        return views
    }

    /**
     * Widget 2 — Dedicated Shopping List Widget (4x2 - 4x4)
     */
    fun buildShoppingRemoteViews(
        context: Context,
        snapshot: WidgetRenderSnapshot,
        options: Bundle? = null,
        appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_spend_shopping_list)
        val tokens = paletteTokens(snapshot.themeStyle)

        views.setInt(R.id.widget_root, "setBackgroundResource", tokens.backgroundRes)
        views.setTextColor(R.id.shopping_list_title, tokens.primaryInkColor)
        views.setInt(R.id.shopping_switch_icon, "setColorFilter", tokens.mutedColor)
        views.setInt(R.id.shopping_progress_badge, "setBackgroundResource", tokens.chipRes)
        views.setTextColor(R.id.shopping_progress_badge, tokens.accentColor)
        views.setTextColor(R.id.shopping_est_total, tokens.accentColor)
        views.setInt(R.id.btn_shopping_open_list, "setBackgroundResource", tokens.chipRes)
        views.setTextColor(R.id.btn_shopping_open_list, tokens.primaryInkColor)

        val openListPi = PendingIntent.getActivity(
            context,
            REQ_OPEN_SHOPPING_LISTS + (appWidgetId.coerceAtLeast(0) % 50),
            MainActivity.widgetLaunchIntent(
                context = context,
                route = MainActivity.ROUTE_SHOPPING_LISTS,
                shoppingListId = snapshot.activeListId,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_shopping_open_list, openListPi)

        val addItemPi = PendingIntent.getActivity(
            context,
            REQ_VOICE_LIST + (appWidgetId.coerceAtLeast(0) % 50),
            VoiceShoppingOverlayActivity.newIntent(
                context = context,
                listId = snapshot.activeListId,
                initialMode = VoiceShoppingOverlayActivity.MODE_MANUAL,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_shopping_add_item, addItemPi)

        if (!snapshot.hasActiveList) {
            views.setTextViewText(R.id.shopping_list_title, "SHOPPING LISTS")
            views.setViewVisibility(R.id.shopping_switch_icon, View.GONE)
            views.setViewVisibility(R.id.shopping_est_total, View.GONE)
            views.setViewVisibility(R.id.shopping_progress_badge, View.GONE)
            views.setProgressBar(R.id.shopping_progress_bar, 100, 0, false)
            views.setInt(R.id.shopping_empty_container, "setBackgroundResource", tokens.wellRes)
            views.setViewVisibility(R.id.shopping_empty_container, View.VISIBLE)
            views.setViewVisibility(R.id.shopping_all_done_container, View.GONE)
            views.setViewVisibility(R.id.shopping_items_container, View.GONE)
            views.setTextColor(R.id.shopping_empty_text, tokens.mutedColor)

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
            views.setOnClickPendingIntent(R.id.shopping_list_switcher, createListPi)
            return views
        }

        views.setTextViewText(R.id.shopping_list_title, snapshot.activeListTitle.uppercase())
        views.setViewVisibility(
            R.id.shopping_switch_icon,
            if (snapshot.listCount > 1) View.VISIBLE else View.GONE,
        )
        val headerPi = if (snapshot.listCount > 1 && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            PendingIntent.getActivity(
                context,
                REQ_SWITCH_LIST_BASE + (appWidgetId.coerceAtLeast(0) % 50),
                WidgetListPickerActivity.newIntent(context, appWidgetId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            openListPi
        }
        views.setOnClickPendingIntent(R.id.shopping_list_switcher, headerPi)

        views.setViewVisibility(R.id.shopping_progress_badge, View.VISIBLE)
        views.setTextViewText(R.id.shopping_progress_badge, "${snapshot.checkedCount}/${snapshot.totalCount}")
        val pct = if (snapshot.totalCount > 0) ((snapshot.checkedCount * 100) / snapshot.totalCount).coerceIn(0, 100) else 0
        views.setProgressBar(R.id.shopping_progress_bar, 100, pct, false)

        if (snapshot.totalCount > 0) {
            views.setViewVisibility(R.id.shopping_est_total, View.VISIBLE)
            views.setTextViewText(R.id.shopping_est_total, "Est. ${snapshot.estimatedTotalDisplay}")
        } else {
            views.setViewVisibility(R.id.shopping_est_total, View.GONE)
        }

        views.setViewVisibility(R.id.shopping_empty_container, View.GONE)

        if (snapshot.checklistRows.isEmpty()) {
            views.setViewVisibility(R.id.shopping_items_container, View.GONE)
            views.setViewVisibility(R.id.shopping_all_done_container, View.VISIBLE)
            views.setInt(R.id.shopping_all_done_container, "setBackgroundResource", tokens.wellRes)
            views.setTextColor(R.id.shopping_all_done_text, tokens.mutedColor)
            views.setTextViewText(R.id.shopping_all_done_text, "${snapshot.activeListTitle} is empty • Tap + Add Item")
            views.setOnClickPendingIntent(R.id.shopping_all_done_container, addItemPi)
            return views
        }

        views.setViewVisibility(R.id.shopping_all_done_container, View.GONE)
        views.setViewVisibility(R.id.shopping_items_container, View.VISIBLE)

        val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200) ?: 200
        val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260) ?: 260
        val maxRows = when {
            minHeightDp in 1..155 -> 2
            minHeightDp in 156..215 -> 3
            minHeightDp in 216..285 -> 4
            else -> 5
        }
        val showPriceInline = minWidthDp >= 210

        val rowIds = listOf(
            R.id.shopping_row_0,
            R.id.shopping_row_1,
            R.id.shopping_row_2,
            R.id.shopping_row_3,
            R.id.shopping_row_4,
        )
        val checkIds = listOf(
            R.id.shopping_check_0,
            R.id.shopping_check_1,
            R.id.shopping_check_2,
            R.id.shopping_check_3,
            R.id.shopping_check_4,
        )
        val textIds = listOf(
            R.id.shopping_item_text_0,
            R.id.shopping_item_text_1,
            R.id.shopping_item_text_2,
            R.id.shopping_item_text_3,
            R.id.shopping_item_text_4,
        )
        val qtyIds = listOf(
            R.id.shopping_item_qty_0,
            R.id.shopping_item_qty_1,
            R.id.shopping_item_qty_2,
            R.id.shopping_item_qty_3,
            R.id.shopping_item_qty_4,
        )
        val priceIds = listOf(
            R.id.shopping_item_price_0,
            R.id.shopping_item_price_1,
            R.id.shopping_item_price_2,
            R.id.shopping_item_price_3,
            R.id.shopping_item_price_4,
        )

        for (index in 0 until 5) {
            val row = snapshot.checklistRows.getOrNull(index)
            if (index < maxRows && row != null) {
                views.setViewVisibility(rowIds[index], View.VISIBLE)
                views.setInt(rowIds[index], "setBackgroundResource", tokens.wellRes)

                views.setImageViewResource(
                    checkIds[index],
                    if (row.checked) R.drawable.ic_widget_checkbox_checked else R.drawable.ic_widget_checkbox_unchecked,
                )
                views.setInt(
                    checkIds[index],
                    "setColorFilter",
                    if (row.checked) tokens.accentColor else tokens.mutedColor,
                )

                views.setTextViewText(textIds[index], row.name)
                views.setTextColor(
                    textIds[index],
                    if (row.checked) tokens.mutedColor else tokens.primaryInkColor,
                )
                views.setInt(
                    textIds[index],
                    "setPaintFlags",
                    if (row.checked) (Paint.ANTI_ALIAS_FLAG or Paint.STRIKE_THRU_TEXT_FLAG) else Paint.ANTI_ALIAS_FLAG,
                )

                if (row.qtyLabel.isNotBlank()) {
                    views.setViewVisibility(qtyIds[index], View.VISIBLE)
                    views.setTextViewText(qtyIds[index], row.qtyLabel)
                    views.setTextColor(qtyIds[index], tokens.mutedColor)
                } else {
                    views.setViewVisibility(qtyIds[index], View.GONE)
                }

                if (showPriceInline && row.priceDisplay.isNotBlank()) {
                    views.setViewVisibility(priceIds[index], View.VISIBLE)
                    views.setTextViewText(priceIds[index], row.priceDisplay)
                    views.setTextColor(
                        priceIds[index],
                        if (row.checked) tokens.mutedColor else tokens.accentColor,
                    )
                } else {
                    views.setViewVisibility(priceIds[index], View.GONE)
                }

                val togglePi = PendingIntent.getBroadcast(
                    context,
                    REQ_TOGGLE_ITEM_BASE + (appWidgetId.coerceAtLeast(0) % 20) * 10 + index,
                    WidgetShoppingActionReceiver.toggleIntent(context, row.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(rowIds[index], togglePi)
            } else {
                views.setViewVisibility(rowIds[index], View.GONE)
            }
        }

        val remainingCount = (snapshot.checklistRows.size - maxRows).coerceAtLeast(0)
        if (remainingCount > 0) {
            views.setViewVisibility(R.id.shopping_more_count, View.VISIBLE)
            views.setTextViewText(R.id.shopping_more_count, "+$remainingCount more items")
            views.setTextColor(R.id.shopping_more_count, tokens.mutedColor)
            views.setOnClickPendingIntent(R.id.shopping_more_count, openListPi)
        } else {
            views.setViewVisibility(R.id.shopping_more_count, View.GONE)
        }
        return views
    }

    /**
     * Widget 3 — Compact Finance Tile (2x2 - 3x2)
     */
    fun buildCompactFinanceRemoteViews(
        context: Context,
        snapshot: WidgetRenderSnapshot,
        options: Bundle? = null,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact_finance)
        val tokens = paletteTokens(snapshot.themeStyle)
        val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160) ?: 160

        views.setInt(R.id.widget_root, "setBackgroundResource", tokens.backgroundRes)
        views.setTextColor(R.id.mini_brand_label, tokens.accentColor)
        views.setViewVisibility(R.id.mini_lock_icon, if (snapshot.isMasked) View.VISIBLE else View.GONE)
        views.setInt(R.id.mini_lock_icon, "setColorFilter", tokens.mutedColor)
        views.setInt(R.id.mini_currency_pill, "setBackgroundResource", tokens.chipRes)
        views.setTextColor(R.id.mini_currency_pill, tokens.secondaryLabelColor)
        views.setTextViewText(R.id.mini_currency_pill, snapshot.baseCurrency)

        views.setInt(R.id.mini_spend_well, "setBackgroundResource", tokens.wellRes)
        views.setTextColor(R.id.mini_today_label, tokens.mutedColor)
        views.setTextColor(R.id.mini_today_spend_value, tokens.primaryInkColor)
        views.setTextViewText(R.id.mini_today_spend_value, snapshot.todaySpendDisplay)

        views.setInt(R.id.mini_balance_well, "setBackgroundResource", tokens.wellRes)
        views.setTextColor(R.id.mini_balance_label, tokens.mutedColor)
        views.setTextColor(R.id.mini_liquid_balance_value, tokens.accentColor)
        views.setTextViewText(R.id.mini_liquid_balance_value, snapshot.liquidBalanceDisplay)

        views.setTextViewText(R.id.btn_mini_add_expense, if (minWidthDp in 1..140) "+ Exp" else "+ Expense")
        views.setViewVisibility(R.id.btn_mini_voice, if (minWidthDp in 1..145) View.GONE else View.VISIBLE)
        views.setInt(R.id.btn_mini_voice, "setBackgroundResource", tokens.circleBtnRes)
        views.setInt(R.id.icon_mini_voice, "setColorFilter", tokens.primaryInkColor)

        val openAppPi = PendingIntent.getActivity(
            context,
            REQ_OPEN_HOME,
            MainActivity.widgetLaunchIntent(context, route = MainActivity.ROUTE_HOME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.mini_header_container, openAppPi)
        views.setOnClickPendingIntent(R.id.mini_metrics_col, openAppPi)

        val addExpensePi = PendingIntent.getActivity(
            context,
            REQ_ADD_EXPENSE,
            QuickTransactionOverlayActivity.newIntent(context, txType = "EXPENSE"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_mini_add_expense, addExpensePi)

        val voiceExpensePi = PendingIntent.getActivity(
            context,
            REQ_VOICE_EXPENSE,
            QuickTransactionOverlayActivity.newIntent(context, txType = "EXPENSE", startVoice = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_mini_voice, voiceExpensePi)

        return views
    }

    /**
     * Widget 4 — Quick Expense Capsule (3x1 / 4x1)
     */
    fun buildQuickExpenseRemoteViews(
        context: Context,
        snapshot: WidgetRenderSnapshot,
        options: Bundle? = null,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_expense)
        val tokens = paletteTokens(snapshot.themeStyle)
        val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) ?: 250

        views.setInt(R.id.widget_root, "setBackgroundResource", tokens.backgroundRes)
        views.setTextColor(R.id.quick_brand_label, tokens.accentColor)
        views.setTextColor(R.id.quick_today_spend_text, tokens.secondaryLabelColor)
        views.setTextViewText(R.id.quick_today_spend_text, snapshot.todaySpendDisplay)
        views.setViewVisibility(R.id.quick_brand_pill, if (minWidthDp in 1..205) View.GONE else View.VISIBLE)

        views.setInt(R.id.btn_quick_voice_expense, "setBackgroundResource", tokens.circleBtnRes)
        views.setInt(R.id.icon_quick_voice, "setColorFilter", tokens.primaryInkColor)
        views.setInt(R.id.btn_quick_add_income, "setBackgroundResource", tokens.circleBtnRes)
        views.setInt(R.id.icon_quick_income, "setColorFilter", tokens.accentColor)

        val openAppPi = PendingIntent.getActivity(
            context,
            REQ_OPEN_HOME,
            MainActivity.widgetLaunchIntent(context, route = MainActivity.ROUTE_HOME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.quick_brand_pill, openAppPi)

        val addExpensePi = PendingIntent.getActivity(
            context,
            REQ_ADD_EXPENSE,
            QuickTransactionOverlayActivity.newIntent(context, txType = "EXPENSE"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_quick_add_expense, addExpensePi)

        val voiceExpensePi = PendingIntent.getActivity(
            context,
            REQ_VOICE_EXPENSE,
            QuickTransactionOverlayActivity.newIntent(context, txType = "EXPENSE", startVoice = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_quick_voice_expense, voiceExpensePi)

        val addIncomePi = PendingIntent.getActivity(
            context,
            REQ_ADD_INCOME,
            QuickTransactionOverlayActivity.newIntent(context, txType = "INCOME"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.btn_quick_add_income, addIncomePi)

        return views
    }

    private data class WidgetPaletteTokens(
        val backgroundRes: Int,
        val wellRes: Int,
        val circleBtnRes: Int,
        val chipRes: Int,
        val primaryInkColor: Int,
        val secondaryLabelColor: Int,
        val mutedColor: Int,
        val accentColor: Int,
    )

    private fun paletteTokens(style: WidgetThemeStyle): WidgetPaletteTokens = when (style) {
        WidgetThemeStyle.FLOW_DARK -> WidgetPaletteTokens(
            backgroundRes = R.drawable.widget_bg_flow_dark,
            wellRes = R.drawable.widget_well_flow_dark,
            circleBtnRes = R.drawable.widget_circle_btn_flow_dark,
            chipRes = R.drawable.widget_chip_flow_dark,
            primaryInkColor = 0xFFEBF6F0.toInt(),
            secondaryLabelColor = 0xFFA7C4B5.toInt(),
            mutedColor = 0xFF7DA894.toInt(),
            accentColor = 0xFF10B981.toInt(),
        )

        WidgetThemeStyle.PREMIUM_BLACK -> WidgetPaletteTokens(
            backgroundRes = R.drawable.widget_bg_premium_black,
            wellRes = R.drawable.widget_well_premium_black,
            circleBtnRes = R.drawable.widget_circle_btn_premium_black,
            chipRes = R.drawable.widget_chip_premium_black,
            primaryInkColor = 0xFFFAFAFC.toInt(),
            secondaryLabelColor = 0xFFD1D5DB.toInt(),
            mutedColor = 0xFF9CA3AF.toInt(),
            accentColor = 0xFF10B981.toInt(),
        )

        WidgetThemeStyle.LIGHT -> WidgetPaletteTokens(
            backgroundRes = R.drawable.widget_bg_light,
            wellRes = R.drawable.widget_well_light,
            circleBtnRes = R.drawable.widget_circle_btn_light,
            chipRes = R.drawable.widget_chip_light,
            primaryInkColor = 0xFF0F172A.toInt(),
            secondaryLabelColor = 0xFF334155.toInt(),
            mutedColor = 0xFF64748B.toInt(),
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
    private const val REQ_VOICE_EXPENSE = 108
    private const val REQ_SWITCH_LIST_BASE = 150
    private const val REQ_TOGGLE_ITEM_BASE = 200
}
