package com.ledgerflow.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.data.local.ShoppingItemEntity
import com.ledgerflow.app.data.local.ShoppingListEntity
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.security.VaultStore
import com.ledgerflow.app.ui.theme.LedgerFlowTheme
import com.ledgerflow.app.ui.theme.resolveLedgerPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compact floating picker allowing each Shopping List Widget instance to choose which
 * checklist to display cleanly from the home screen.
 */
class WidgetListPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setContent {
            val appCtx = applicationContext
            val scope = rememberCoroutineScope()
            val vaultStore = remember { VaultStore(appCtx) }
            val vault = remember { vaultStore.read() }
            val palette = resolveLedgerPalette(
                themeMode = vault.themeMode,
                systemDark = LedgerWidgetCoordinator.isSystemDarkTheme(this),
            )
            var lists by remember { mutableStateOf<List<ShoppingListEntity>>(emptyList()) }
            var allItems by remember { mutableStateOf<List<ShoppingItemEntity>>(emptyList()) }
            val currentListId = remember {
                vault.widgetListSelections[appWidgetId.toString()]
            }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val db = LedgerDatabase.getInstance(appCtx)
                    val loadedLists = db.shoppingListDao().getAllActive()
                        .sortedByDescending { it.updatedAtEpochMs }
                    val loadedItems = db.shoppingItemDao().getAllActive()
                    withContext(Dispatchers.Main) {
                        lists = loadedLists
                        allItems = loadedItems
                    }
                }
            }

            LedgerFlowTheme(palette = palette) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                        .navigationBarsPadding()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { finishWithoutAnimation() },
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                        shape = RoundedCornerShape(24.dp),
                        color = palette.surface,
                        border = BorderStroke(1.dp, palette.border),
                        shadowElevation = 12.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Switch Widget Checklist",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.ink,
                                )
                                IconButton(onClick = { finishWithoutAnimation() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = palette.muted, modifier = Modifier.size(18.dp))
                                }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(lists, key = { it.id }) { list ->
                                    val summary = AnalyticsCalculator.summarizeShoppingList(list, allItems)
                                    val selected = list.id == currentListId || (currentListId == null && lists.firstOrNull()?.id == list.id)
                                    Surface(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                                        vaultStore.update { s ->
                                                            s.copy(
                                                                widgetListSelections = s.widgetListSelections + (appWidgetId.toString() to list.id)
                                                            )
                                                        }
                                                    }
                                                    LedgerWidgetCoordinator.refreshAllWidgetsNow(appCtx)
                                                }
                                                finishWithoutAnimation()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (selected) palette.emeraldSoft else palette.tint,
                                        border = BorderStroke(1.dp, if (selected) palette.emeraldPrimary else palette.border),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = list.title,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.ink,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                val est = if (vault.hideBalances) "••••" else MoneyFormatter.formatMinor(summary.estimatedTotalMinor, vault.baseCurrency)
                                                Text(
                                                    text = "${summary.checkedItems}/${summary.totalItems} completed • Est. $est",
                                                    fontSize = 11.sp,
                                                    color = palette.muted,
                                                )
                                            }
                                            if (selected) {
                                                Icon(
                                                    imageVector = Icons.Outlined.CheckCircle,
                                                    contentDescription = "Selected",
                                                    tint = palette.emeraldPrimary,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun finishWithoutAnimation() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        fun newIntent(context: Context, appWidgetId: Int): Intent =
            Intent(context, WidgetListPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
    }
}
