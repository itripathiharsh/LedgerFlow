package com.ledgerflow.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.AnalyticsEngine
import com.ledgerflow.app.domain.AnalyticsRangePreset
import com.ledgerflow.app.domain.CardUtilizationRow
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.ReportExporter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.BalanceTrendLineChart
import com.ledgerflow.app.ui.components.CategoryDonutChart
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.PieChartSlice
import com.ledgerflow.app.ui.components.SectionHeader
import com.ledgerflow.app.ui.components.SpendHeatmapCard
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.components.parseSliceColor
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.AmberSoft
import com.ledgerflow.app.ui.theme.AmberWarn
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.LedgerThemeColors
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.RoseSoft
import com.ledgerflow.app.ui.theme.SkySoft
import com.ledgerflow.app.ui.theme.SkySync
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.SurfaceCard
import com.ledgerflow.app.ui.theme.TabularStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    vm: LedgerViewModel,
    initialSection: Int = 0,
    onOpenDrawer: () -> Unit = {},
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val transactions by vm.allTransactions.collectAsState()
    val planned by vm.plannedPayments.collectAsState()
    val holdings by vm.holdings.collectAsState()
    val vault by vm.vaultState.collectAsState()

    val baseCurrency = vault.baseCurrency
    val masked = vault.hideBalances

    var sectionIndex by remember(initialSection) { mutableIntStateOf(initialSection.coerceIn(0, 4)) }
    var preset by remember { mutableStateOf(AnalyticsRangePreset.MONTH_1) }
    var customStartMs by remember { mutableStateOf<Long?>(null) }
    var customEndMs by remember { mutableStateOf<Long?>(null) }
    var customPickerStep by remember { mutableIntStateOf(0) } // 0 = closed, 1 = pick start, 2 = pick end

    var spendingBreakdownMode by remember { mutableIntStateOf(0) } // 0 = Categories, 1 = Subcategories, 2 = #Labels
    var editingLimitCard by remember { mutableStateOf<CardUtilizationRow?>(null) }
    var pendingCsvToSave by remember { mutableStateOf<String?>(null) }

    val saveReportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val content = pendingCsvToSave
        pendingCsvToSave = null
        if (uri != null && content != null) {
            vm.saveReportToUri(uri, content)
        }
    }

    val nowMs = System.currentTimeMillis()
    val activeRange = remember(preset, customStartMs, customEndMs, nowMs) {
        AnalyticsEngine.buildDateRange(
            preset = preset,
            nowMs = nowMs,
            customStartMs = customStartMs,
            customEndMs = customEndMs,
        )
    }

    val bundle = remember(accounts, categories, transactions, planned, holdings, baseCurrency, activeRange) {
        AnalyticsEngine.computeFullAnalytics(
            accounts = accounts,
            categories = categories,
            transactions = transactions,
            planned = planned,
            holdings = holdings,
            baseCurrency = baseCurrency,
            range = activeRange,
            nowMs = nowMs,
        )
    }

    val monthlyHeatmap = remember(transactions, baseCurrency) {
        AnalyticsCalculator.computeMonthlySpendHeatmap(transactions, baseCurrency)
    }
    val rollingHeatmap = remember(transactions, baseCurrency) {
        AnalyticsCalculator.computeRollingSpendHeatmap(transactions, baseCurrency, weeks = 12)
    }

    val sectionTitles = listOf("Overview", "Cash Flow", "Spending", "Credit & Debt", "Reports")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Open menu", tint = SlateInk)
                    }
                    Column {
                        Text("Analytics & Reports", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Text("${bundle.range.label} • $baseCurrency", fontSize = 12.sp, color = MutedSlate)
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = SlateInk)
                }
            }
        }

        // Timeframe Switcher (Weeks / Months / Custom)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AnalyticsRangePreset.entries.forEach { p ->
                        val active = preset == p
                        FilterChip(
                            selected = active,
                            onClick = {
                                if (p == AnalyticsRangePreset.CUSTOM) {
                                    customPickerStep = 1
                                } else {
                                    preset = p
                                }
                            },
                            label = { Text(p.label, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldSoft,
                                selectedLabelColor = EmeraldDark,
                            ),
                        )
                    }
                }

                // Clean Section Tabs
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SlateTint,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        sectionTitles.forEachIndexed { idx, title ->
                            val active = sectionIndex == idx
                            Surface(
                                onClick = { sectionIndex = idx },
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) SurfaceCard else Color.Transparent,
                                border = if (active) BorderStroke(1.dp, BorderSubtle) else null,
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) EmeraldDark else MutedSlate,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        when (sectionIndex) {
            // SECTION 0: OVERVIEW & BALANCES
            0 -> {
                // 1. Balance Trend
                item {
                    val trend = bundle.balanceTrend
                    val positive = trend.changeMinor >= 0L
                    CleanCard(padding = 16.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("Balance Trend", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                                Text(
                                    text = MoneyFormatter.formatMinor(trend.endBalanceMinor, baseCurrency, masked = masked),
                                    style = TabularStyle,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (trend.endBalanceMinor < 0L) RoseOutflow else SlateInk,
                                )
                            }
                            StatusPill(
                                text = "${if (positive) "+" else ""}${String.format(Locale.US, "%.1f", trend.changePercent)}%",
                                containerColor = if (positive) EmeraldSoft else RoseSoft,
                                contentColor = if (positive) EmeraldDark else RoseOutflow,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        BalanceTrendLineChart(
                            points = trend.points,
                            lineColor = if (positive) EmeraldPrimary else RoseOutflow,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            StatMiniCol("Start", MoneyFormatter.formatMinor(trend.startBalanceMinor, baseCurrency, masked = masked))
                            StatMiniCol("Min", MoneyFormatter.formatMinor(trend.minMinor, baseCurrency, masked = masked))
                            StatMiniCol("Peak", MoneyFormatter.formatMinor(trend.maxMinor, baseCurrency, masked = masked))
                            StatMiniCol(
                                label = "Change",
                                value = MoneyFormatter.formatMinor(trend.changeMinor, baseCurrency, showPlus = true, masked = masked),
                                valueColor = if (positive) EmeraldDark else RoseOutflow,
                            )
                        }
                    }
                }

                // 2. Balance by Accounts
                item {
                    CleanCard(padding = 16.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Balance by Accounts", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            Text("${bundle.accountBalances.size} active", fontSize = 12.sp, color = MutedSlate)
                        }
                        Spacer(Modifier.height(10.dp))
                        if (bundle.accountBalances.isEmpty()) {
                            Text("No active accounts yet.", fontSize = 12.sp, color = MutedSlate)
                        } else {
                            bundle.accountBalances.forEachIndexed { idx, row ->
                                if (idx > 0) HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                                val isNeg = row.baseBalanceMinor < 0L
                                val barColor = if (isNeg) RoseOutflow else EmeraldPrimary
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(row.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                                        Text(
                                            text = if (row.currency != baseCurrency) "${row.typeLabel} • ${row.currency}" else row.typeLabel,
                                            fontSize = 11.sp,
                                            color = MutedSlate,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = if (row.isLiability && isNeg) {
                                                "Owed ${MoneyFormatter.formatMinor(abs(row.baseBalanceMinor), baseCurrency, masked = masked)}"
                                            } else {
                                                MoneyFormatter.formatMinor(row.baseBalanceMinor, baseCurrency, masked = masked)
                                            },
                                            style = TabularStyle,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isNeg) RoseOutflow else SlateInk,
                                        )
                                        Text("${(row.shareRatio * 100f).toInt()}%", fontSize = 11.sp, color = MutedSlate)
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                                LinearProgressIndicator(
                                    progress = { row.shareRatio.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(999.dp)),
                                    color = barColor,
                                    trackColor = SlateTint,
                                )
                            }
                        }
                    }
                }

                // 3. Balance by Categories
                item {
                    CleanCard(padding = 16.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Balance by Categories", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            StatusPill("Net Period Impact", SlateTint, MutedSlate)
                        }
                        Spacer(Modifier.height(10.dp))
                        if (bundle.categoryNetBalances.isEmpty()) {
                            Text("No categorized activity in ${bundle.range.label}.", fontSize = 12.sp, color = MutedSlate)
                        } else {
                            bundle.categoryNetBalances.take(10).forEachIndexed { idx, row ->
                                if (idx > 0) HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                                val catColor = parseSliceColor(row.colorHex, EmeraldPrimary)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(catColor))
                                        Column {
                                            Text(row.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                                            Text(
                                                text = "${row.txCount} txn${if (row.txCount == 1) "" else "s"} • In ${MoneyFormatter.formatMinor(row.inflowMinor, baseCurrency, masked = masked)} / Out ${MoneyFormatter.formatMinor(row.outflowMinor, baseCurrency, masked = masked)}",
                                                fontSize = 11.sp,
                                                color = MutedSlate,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Text(
                                        text = MoneyFormatter.formatMinor(row.netMinor, baseCurrency, showPlus = true, masked = masked),
                                        style = TabularStyle,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (row.netMinor >= 0L) EmeraldDark else RoseOutflow,
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Balance Forecast + Planned Payment Timeline
                item {
                    val fc = bundle.balanceForecast
                    val netPositive = fc.netProjectedDeltaMinor >= 0L
                    CleanCard(padding = 16.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("Balance Forecast", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                                Text("Next ${fc.horizonDays} days from scheduled payments", fontSize = 12.sp, color = MutedSlate)
                            }
                            StatusPill(
                                text = MoneyFormatter.formatMinor(fc.projectedEndBalanceMinor, baseCurrency, masked = masked),
                                containerColor = if (netPositive) EmeraldSoft else RoseSoft,
                                contentColor = if (netPositive) EmeraldDark else RoseOutflow,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatBox("Scheduled In", MoneyFormatter.formatMinor(fc.scheduledInflowMinor, baseCurrency, showPlus = true, masked = masked), EmeraldDark, EmeraldSoft, Modifier.weight(1f))
                            StatBox("Scheduled Out", MoneyFormatter.formatMinor(-fc.scheduledOutflowMinor, baseCurrency, masked = masked), RoseOutflow, RoseSoft, Modifier.weight(1f))
                        }
                        if (fc.curvePoints.size >= 2) {
                            Spacer(Modifier.height(12.dp))
                            BalanceTrendLineChart(
                                points = fc.curvePoints,
                                lineColor = SkySync,
                            )
                        }
                        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))
                        Text("Planned Payment Timeline", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Spacer(Modifier.height(8.dp))
                        if (fc.timeline.isEmpty()) {
                            Text("No planned bills or recurring income scheduled in the next ${fc.horizonDays} days.", fontSize = 12.sp, color = MutedSlate)
                        } else {
                            fc.timeline.take(12).forEachIndexed { idx, itemRow ->
                                if (idx > 0) HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 7.dp))
                                val isInc = itemRow.txType == "INCOME"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(itemRow.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                                            if (itemRow.isOverdue) {
                                                StatusPill("Overdue", RoseSoft, RoseOutflow)
                                            } else if (itemRow.autoPay) {
                                                StatusPill("Auto", EmeraldSoft, EmeraldDark)
                                            }
                                        }
                                        Text(
                                            text = listOfNotNull(itemRow.dueDateLabel, itemRow.accountName, itemRow.cadence.lowercase().replaceFirstChar { it.uppercase() }).joinToString(" • "),
                                            fontSize = 11.sp,
                                            color = MutedSlate,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = MoneyFormatter.formatMinor(if (isInc) itemRow.amountBaseMinor else -itemRow.amountBaseMinor, baseCurrency, showPlus = isInc, masked = masked),
                                            style = TabularStyle,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isInc) EmeraldDark else RoseOutflow,
                                        )
                                        Text(
                                            text = "Bal ${MoneyFormatter.formatMinor(itemRow.runningBalanceMinor, baseCurrency, masked = masked)}",
                                            style = TabularStyle,
                                            fontSize = 10.sp,
                                            color = MutedSlate,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 1: CASH FLOW
            1 -> {
                // Cash Flow Summary & Trend
                item {
                    CleanCard(padding = 16.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Cash Flow", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            StatusPill(
                                text = "${if (bundle.periodSavingsRatePct > 0) "+" else ""}${bundle.periodSavingsRatePct}% Saved",
                                containerColor = if (bundle.periodSavingsRatePct >= 0) EmeraldSoft else RoseSoft,
                                contentColor = if (bundle.periodSavingsRatePct >= 0) EmeraldDark else RoseOutflow,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatBox("Inflow", MoneyFormatter.formatMinor(bundle.periodInflowMinor, baseCurrency, showPlus = true, masked = masked), EmeraldDark, EmeraldSoft, Modifier.weight(1f))
                            StatBox("Outflow", MoneyFormatter.formatMinor(-bundle.periodOutflowMinor, baseCurrency, masked = masked), RoseOutflow, RoseSoft, Modifier.weight(1f))
                            StatBox("Net Flow", MoneyFormatter.formatMinor(bundle.periodNetMinor, baseCurrency, showPlus = true, masked = masked), SkySync, SkySoft, Modifier.weight(1f))
                        }
                        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))
                        Text("Cash Flow Trend", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Spacer(Modifier.height(8.dp))
                        if (bundle.cashFlowBuckets.isEmpty()) {
                            Text("No cash flow activity in this period.", fontSize = 12.sp, color = MutedSlate)
                        } else {
                            CashFlowBucketsChart(buckets = bundle.cashFlowBuckets)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LegendItem("Inflow", EmeraldPrimary)
                                LegendItem("Outflow", LedgerThemeColors.outflowBar)
                            }
                        }
                    }
                }

                // Period-to-Period Comparison
                item {
                    val comp = bundle.periodComparison
                    CleanCard(padding = 16.dp) {
                        Text("Period-to-Period Comparison", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Text("${comp.currentLabel} vs ${comp.previousLabel}", fontSize = 12.sp, color = MutedSlate)
                        Spacer(Modifier.height(12.dp))
                        ComparisonRow(
                            label = "Income",
                            currentText = MoneyFormatter.formatMinor(comp.currentInflowMinor, baseCurrency, masked = masked),
                            prevText = MoneyFormatter.formatMinor(comp.previousInflowMinor, baseCurrency, masked = masked),
                            deltaPct = comp.inflowDeltaPct,
                            higherIsGood = true,
                        )
                        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                        ComparisonRow(
                            label = "Spending",
                            currentText = MoneyFormatter.formatMinor(comp.currentOutflowMinor, baseCurrency, masked = masked),
                            prevText = MoneyFormatter.formatMinor(comp.previousOutflowMinor, baseCurrency, masked = masked),
                            deltaPct = comp.outflowDeltaPct,
                            higherIsGood = false,
                        )
                        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                        ComparisonRow(
                            label = "Net Cash Flow",
                            currentText = MoneyFormatter.formatMinor(comp.currentNetMinor, baseCurrency, showPlus = true, masked = masked),
                            prevText = MoneyFormatter.formatMinor(comp.previousNetMinor, baseCurrency, showPlus = true, masked = masked),
                            deltaPct = null,
                            higherIsGood = true,
                        )

                        if (comp.topExpenseMovers.isNotEmpty()) {
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))
                            Text("Biggest Category Changes", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            Spacer(Modifier.height(8.dp))
                            comp.topExpenseMovers.forEachIndexed { idx, m ->
                                if (idx > 0) Spacer(Modifier.height(6.dp))
                                val rose = m.deltaMinor > 0L
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(m.name, fontSize = 13.sp, color = SlateInk, modifier = Modifier.weight(1f))
                                    Text(
                                        text = MoneyFormatter.formatMinor(m.deltaMinor, baseCurrency, showPlus = true, masked = masked),
                                        style = TabularStyle,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (rose) RoseOutflow else EmeraldDark,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 2: SPENDING
            2 -> {
                // Spending by Categories / Subcategories / #Labels
                item {
                    val activeItems = when (spendingBreakdownMode) {
                        1 -> bundle.spendingBySubcategory
                        2 -> bundle.spendingByLabels
                        else -> bundle.spendingByParentCategory
                    }
                    val total = activeItems.sumOf { it.amountMinor }
                    val defaultColor = EmeraldPrimary
                    val pieSlices = remember(activeItems, defaultColor) {
                        activeItems.map { item ->
                            PieChartSlice(
                                id = item.id,
                                label = item.name,
                                valueMinor = item.amountMinor,
                                color = parseSliceColor(item.colorHex, defaultColor),
                                ratio = item.ratio,
                            )
                        }
                    }

                    CleanCard(padding = 16.dp) {
                        Text("Spending Breakdown", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Categories", "Subcategories", "#Labels").forEachIndexed { idx, label ->
                                FilterChip(
                                    selected = spendingBreakdownMode == idx,
                                    onClick = { spendingBreakdownMode = idx },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = EmeraldSoft,
                                        selectedLabelColor = EmeraldDark,
                                    ),
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        CategoryDonutChart(
                            slices = pieSlices,
                            totalMinor = total,
                            currency = baseCurrency,
                            masked = masked,
                            centerSubtitle = bundle.range.label,
                            emptyMessage = "No spending recorded in this period.",
                        )
                    }
                }

                // Nature of Spending + Top Merchants + Weekday/Weekend
                item {
                    val nat = bundle.spendingNature
                    CleanCard(padding = 16.dp) {
                        Text("Nature of Spending", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Text("Needs vs Wants vs Savings & Debt Paydown", fontSize = 12.sp, color = MutedSlate)
                        Spacer(Modifier.height(10.dp))
                        if (nat.totalClassifiedMinor <= 0L) {
                            Text("Record expenses or repayments in ${bundle.range.label} to analyze spending mix.", fontSize = 12.sp, color = MutedSlate)
                        } else {
                            // Stacked bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(SlateTint),
                            ) {
                                if (nat.needsRatio > 0f) {
                                    Box(modifier = Modifier.weight(nat.needsRatio.coerceAtLeast(0.01f)).fillMaxSize().background(EmeraldPrimary))
                                }
                                if (nat.wantsRatio > 0f) {
                                    Box(modifier = Modifier.weight(nat.wantsRatio.coerceAtLeast(0.01f)).fillMaxSize().background(AmberWarn))
                                }
                                if (nat.savingsRatio > 0f) {
                                    Box(modifier = Modifier.weight(nat.savingsRatio.coerceAtLeast(0.01f)).fillMaxSize().background(SkySync))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            NatureRow("Essential Needs", "${(nat.needsRatio * 100f).toInt()}%", MoneyFormatter.formatMinor(nat.needsMinor, baseCurrency, masked = masked), EmeraldPrimary)
                            NatureRow("Discretionary Wants", "${(nat.wantsRatio * 100f).toInt()}%", MoneyFormatter.formatMinor(nat.wantsMinor, baseCurrency, masked = masked), AmberWarn)
                            NatureRow("Debt Repayments", "${(nat.savingsRatio * 100f).toInt()}%", MoneyFormatter.formatMinor(nat.savingsAndDebtMinor, baseCurrency, masked = masked), SkySync)

                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatBox("Weekday Spend", MoneyFormatter.formatMinor(nat.weekdaySpendMinor, baseCurrency, masked = masked), SlateInk, SlateTint, Modifier.weight(1f))
                                StatBox("Weekend Spend", MoneyFormatter.formatMinor(nat.weekendSpendMinor, baseCurrency, masked = masked), AmberWarn, AmberSoft, Modifier.weight(1f))
                            }

                            if (nat.topMerchants.isNotEmpty()) {
                                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                                Text("Top Merchants", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                                Spacer(Modifier.height(6.dp))
                                nat.topMerchants.forEachIndexed { idx, m ->
                                    if (idx > 0) Spacer(Modifier.height(5.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("${m.name} (${m.txCount}x)", fontSize = 12.sp, color = SlateInk, modifier = Modifier.weight(1f))
                                        Text(
                                            text = MoneyFormatter.formatMinor(m.amountMinor, baseCurrency, masked = masked),
                                            style = TabularStyle,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = SlateInk,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Daily Spend Map + Subscriptions & Pace
                item {
                    val hp = bundle.financialHealth
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SpendHeatmapCard(
                            monthlyData = monthlyHeatmap,
                            rollingData = rollingHeatmap,
                            currency = baseCurrency,
                            masked = masked,
                            allowModeSwitch = true,
                        )
                        CleanCard(padding = 16.dp) {
                            Text("Spend Pace & Subscriptions", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatBox("Daily Average", MoneyFormatter.formatMinor(hp.avgDailySpendMinor, baseCurrency, masked = masked), SlateInk, SlateTint, Modifier.weight(1f))
                                StatBox("Annualized Bills", MoneyFormatter.formatMinor(hp.annualizedSubscriptionsMinor, baseCurrency, masked = masked), AmberWarn, AmberSoft, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // SECTION 3: CREDIT & DEBT + FINANCIAL RESILIENCE
            3 -> {
                val cd = bundle.creditAndDebt
                val hp = bundle.financialHealth

                // 1. Overall Credit Limit Utilization
                item {
                    CleanCard(padding = 16.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Credit Limit Utilization", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            if (cd.overallUtilizationPct != null) {
                                val pct = cd.overallUtilizationPct
                                StatusPill(
                                    text = "${String.format(Locale.US, "%.1f", pct)}% Used",
                                    containerColor = if (pct >= 70f) RoseSoft else if (pct >= 30f) AmberSoft else EmeraldSoft,
                                    contentColor = if (pct >= 70f) RoseOutflow else if (pct >= 30f) AmberWarn else EmeraldDark,
                                )
                            } else {
                                StatusPill("No Limit Set", SlateTint, MutedSlate)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (cd.cards.isEmpty()) {
                            Text("Add a Credit Card in Accounts to track credit limit utilization.", fontSize = 12.sp, color = MutedSlate)
                        } else if (cd.totalCreditLimitMinor <= 0L) {
                            Text(
                                text = "Total Credit Used: ${MoneyFormatter.formatMinor(cd.totalCreditUsedMinor, baseCurrency, masked = masked)}. Set credit limits on your cards below to compute utilization %.",
                                fontSize = 12.sp,
                                color = MutedSlate,
                            )
                        } else {
                            val overallRatio = (cd.overallUtilizationPct ?: 0f) / 100f
                            val barColor = if (overallRatio >= 0.7f) RoseOutflow else if (overallRatio >= 0.3f) AmberWarn else EmeraldPrimary
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Used ${MoneyFormatter.formatMinor(cd.totalCreditUsedMinor, baseCurrency, masked = masked)}", fontSize = 12.sp, color = SlateInk)
                                Text("Total Limit ${MoneyFormatter.formatMinor(cd.totalCreditLimitMinor, baseCurrency, masked = masked)}", fontSize = 12.sp, color = MutedSlate)
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { overallRatio.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = barColor,
                                trackColor = SlateTint,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Available Credit: ${MoneyFormatter.formatMinor((cd.totalCreditLimitMinor - cd.totalCreditUsedMinor).coerceAtLeast(0L), baseCurrency, masked = masked)}",
                                fontSize = 11.sp,
                                color = EmeraldDark,
                            )
                        }
                    }
                }

                // 2. Utilization by Credit Cards
                item {
                    CleanCard(padding = 16.dp) {
                        Text("Utilization by Credit Cards", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Spacer(Modifier.height(10.dp))
                        if (cd.cards.isEmpty()) {
                            Text("No active credit cards found.", fontSize = 12.sp, color = MutedSlate)
                        } else {
                            cd.cards.forEachIndexed { idx, card ->
                                if (idx > 0) HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                                val pct = card.utilizationPercent
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        val last4 = if (card.lastFour.isNotBlank()) " ••${card.lastFour}" else ""
                                        Text("${card.name}$last4", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                                        Text(
                                            text = if (card.limitBaseMinor > 0L) {
                                                "Used ${MoneyFormatter.formatMinor(card.usedBaseMinor, baseCurrency, masked = masked)} / ${MoneyFormatter.formatMinor(card.limitBaseMinor, baseCurrency, masked = masked)}"
                                            } else {
                                                "Owed ${MoneyFormatter.formatMinor(card.usedBaseMinor, baseCurrency, masked = masked)} • No limit set"
                                            },
                                            fontSize = 11.sp,
                                            color = MutedSlate,
                                        )
                                    }
                                    TextButton(
                                        onClick = { editingLimitCard = card },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(if (card.limitBaseMinor > 0L) "Edit Limit" else "Set Limit", fontSize = 11.sp, color = SkySync)
                                    }
                                }
                                if (pct != null) {
                                    val barColor = if (pct >= 70f) RoseOutflow else if (pct >= 30f) AmberWarn else EmeraldPrimary
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { (pct / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(999.dp)),
                                        color = barColor,
                                        trackColor = SlateTint,
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Debt-to-Income Ratio & Recurring Debts
                item {
                    CleanCard(padding = 16.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Debt-to-Income Ratio (DTI)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            val dti = cd.debtToIncomePct
                            if (dti != null) {
                                val healthy = dti <= 20f
                                val moderate = dti <= 36f
                                StatusPill(
                                    text = "${String.format(Locale.US, "%.1f", dti)}% • ${if (healthy) "Healthy" else if (moderate) "Moderate" else "High"}",
                                    containerColor = if (healthy) EmeraldSoft else if (moderate) AmberSoft else RoseSoft,
                                    contentColor = if (healthy) EmeraldDark else if (moderate) AmberWarn else RoseOutflow,
                                )
                            } else {
                                StatusPill("Need Income", SlateTint, MutedSlate)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatBox("Monthly Debt Service", MoneyFormatter.formatMinor(cd.monthlyDebtServiceMinor, baseCurrency, masked = masked), RoseOutflow, RoseSoft, Modifier.weight(1f))
                            StatBox("Avg Monthly Income", MoneyFormatter.formatMinor(cd.avgMonthlyIncomeMinor, baseCurrency, masked = masked), EmeraldDark, EmeraldSoft, Modifier.weight(1f))
                        }
                        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))
                        Text("Recurring Debts & Liabilities", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Spacer(Modifier.height(8.dp))
                        if (cd.recurringDebtItems.isEmpty()) {
                            Text("No active credit balances, loans, or EMI schedules.", fontSize = 12.sp, color = MutedSlate)
                        } else {
                            cd.recurringDebtItems.forEachIndexed { idx, debt ->
                                if (idx > 0) HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 7.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(debt.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                                        Text(debt.subtitle, fontSize = 11.sp, color = MutedSlate)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = MoneyFormatter.formatMinor(debt.balanceOrAmountMinor, baseCurrency, masked = masked),
                                            style = TabularStyle,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = RoseOutflow,
                                        )
                                        if (debt.monthlyEquivalentMinor > 0L) {
                                            Text(
                                                text = "${MoneyFormatter.formatMinor(debt.monthlyEquivalentMinor, baseCurrency, masked = masked)}/mo",
                                                style = TabularStyle,
                                                fontSize = 10.sp,
                                                color = MutedSlate,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Financial Resilience & Emergency Runway
                item {
                    CleanCard(padding = 16.dp) {
                        Text("Financial Resilience", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Text("Runway & solvency buffer from your real balances", fontSize = 12.sp, color = MutedSlate)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val runwayTxt = hp.emergencyRunwayMonths?.let { "${String.format(Locale.US, "%.1f", it)} mos" } ?: "N/A"
                            StatBox("Emergency Runway", runwayTxt, EmeraldDark, EmeraldSoft, Modifier.weight(1f))
                            StatBox("Net Worth", MoneyFormatter.formatMinor(hp.netWorthMinor, baseCurrency, masked = masked), if (hp.netWorthMinor >= 0L) SlateInk else RoseOutflow, SlateTint, Modifier.weight(1f))
                        }
                    }
                }
            }

            // SECTION 4: REPORTS (CASH FLOW, INCOME & EXPENSE, FINANCIAL ASSETS WITH DOWNLOAD & SHARE)
            else -> {
                item {
                    val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMs))
                    val cashFlowCsv = remember(bundle, baseCurrency) {
                        ReportExporter.buildCashFlowReportCsv(bundle, baseCurrency)
                    }
                    val incomeExpenseCsv = remember(bundle, transactions, categories, accounts, baseCurrency) {
                        ReportExporter.buildIncomeExpenseReportCsv(bundle, transactions, categories, accounts, baseCurrency)
                    }
                    val assetsCsv = remember(bundle, holdings, baseCurrency) {
                        ReportExporter.buildFinancialAssetsReportCsv(bundle, holdings, baseCurrency)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(title = "Downloadable Statements")

                        ReportDownloadCard(
                            title = "Cash Flow Report",
                            subtitle = "Period: ${bundle.range.label} • ${bundle.cashFlowBuckets.size} buckets",
                            highlights = listOf(
                                "Inflow" to MoneyFormatter.formatMinor(bundle.periodInflowMinor, baseCurrency, showPlus = true, masked = masked),
                                "Outflow" to MoneyFormatter.formatMinor(-bundle.periodOutflowMinor, baseCurrency, masked = masked),
                                "Net Surplus" to MoneyFormatter.formatMinor(bundle.periodNetMinor, baseCurrency, showPlus = true, masked = masked),
                            ),
                            onDownload = {
                                pendingCsvToSave = cashFlowCsv
                                saveReportLauncher.launch("ledgerflow_cash_flow_$stamp.csv")
                            },
                            onShare = {
                                vm.shareTextReport(
                                    title = "Share Cash Flow Report",
                                    fileName = "ledgerflow_cash_flow_$stamp.csv",
                                    contents = cashFlowCsv,
                                ) { intent ->
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { vm.showMessage("No app available to share report") }
                                }
                            },
                        )

                        val txCountInPeriod = remember(transactions, bundle.range) {
                            transactions.count { it.deletedAtEpochMs == null && it.occurredAtEpochMs in bundle.range.startEpochMs..bundle.range.endEpochMs }
                        }
                        ReportDownloadCard(
                            title = "Income & Expense Statement",
                            subtitle = "Period: ${bundle.range.label} • $txCountInPeriod ledger entries",
                            highlights = listOf(
                                "Income" to MoneyFormatter.formatMinor(bundle.periodInflowMinor, baseCurrency, masked = masked),
                                "Expenses" to MoneyFormatter.formatMinor(bundle.periodOutflowMinor, baseCurrency, masked = masked),
                                "Savings Rate" to "${bundle.periodSavingsRatePct}%",
                            ),
                            onDownload = {
                                pendingCsvToSave = incomeExpenseCsv
                                saveReportLauncher.launch("ledgerflow_income_expense_$stamp.csv")
                            },
                            onShare = {
                                vm.shareTextReport(
                                    title = "Share Income & Expense Statement",
                                    fileName = "ledgerflow_income_expense_$stamp.csv",
                                    contents = incomeExpenseCsv,
                                ) { intent ->
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { vm.showMessage("No app available to share report") }
                                }
                            },
                        )

                        ReportDownloadCard(
                            title = "Financial Assets & Net Worth",
                            subtitle = "${bundle.accountBalances.size} accounts • ${holdings.count { it.deletedAtEpochMs == null && it.quantity > 0.0 }} holdings",
                            highlights = listOf(
                                "Liquid Assets" to MoneyFormatter.formatMinor(bundle.financialHealth.liquidAssetsMinor, baseCurrency, masked = masked),
                                "Investments" to MoneyFormatter.formatMinor(bundle.financialHealth.investmentValueMinor, baseCurrency, masked = masked),
                                "Net Worth" to MoneyFormatter.formatMinor(bundle.financialHealth.netWorthMinor, baseCurrency, masked = masked),
                            ),
                            onDownload = {
                                pendingCsvToSave = assetsCsv
                                saveReportLauncher.launch("ledgerflow_financial_assets_$stamp.csv")
                            },
                            onShare = {
                                vm.shareTextReport(
                                    title = "Share Financial Assets Report",
                                    fileName = "ledgerflow_financial_assets_$stamp.csv",
                                    contents = assetsCsv,
                                ) { intent ->
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { vm.showMessage("No app available to share report") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // Custom Start/End Date Picker Flow
    if (customPickerStep == 1) {
        val startState = rememberDatePickerState(initialSelectedDateMillis = customStartMs ?: (nowMs - 30L * 86_400_000L))
        DatePickerDialog(
            onDismissRequest = { customPickerStep = 0 },
            confirmButton = {
                TextButton(
                    onClick = {
                        customStartMs = startState.selectedDateMillis ?: (nowMs - 30L * 86_400_000L)
                        customPickerStep = 2
                    },
                ) { Text("Next: End Date") }
            },
            dismissButton = { TextButton(onClick = { customPickerStep = 0 }) { Text("Cancel") } },
        ) {
            DatePicker(state = startState, title = { Text("Select Start Date", modifier = Modifier.padding(16.dp)) })
        }
    } else if (customPickerStep == 2) {
        val endState = rememberDatePickerState(initialSelectedDateMillis = customEndMs ?: nowMs)
        DatePickerDialog(
            onDismissRequest = { customPickerStep = 0 },
            confirmButton = {
                TextButton(
                    onClick = {
                        customEndMs = endState.selectedDateMillis ?: nowMs
                        preset = AnalyticsRangePreset.CUSTOM
                        customPickerStep = 0
                    },
                ) { Text("Apply Range") }
            },
            dismissButton = { TextButton(onClick = { customPickerStep = 0 }) { Text("Cancel") } },
        ) {
            DatePicker(state = endState, title = { Text("Select End Date", modifier = Modifier.padding(16.dp)) })
        }
    }

    // Inline Credit Card Limit Editor
    if (editingLimitCard != null) {
        val card = editingLimitCard!!
        val isJpy = baseCurrency.equals("JPY", ignoreCase = true)
        var limitText by remember(card.accountId) {
            mutableStateOf(
                if (card.limitBaseMinor > 0L) {
                    if (isJpy) card.limitBaseMinor.toString()
                    else String.format(Locale.US, "%.2f", card.limitBaseMinor / 100.0)
                } else ""
            )
        }
        val parsed = if (limitText.isBlank()) 0L else MoneyFormatter.parseDecimalToMinor(limitText, baseCurrency)

        AlertDialog(
            onDismissRequest = { editingLimitCard = null },
            title = { Text("Set Credit Limit • ${card.name}") },
            text = {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                    label = { Text("Credit Limit ($baseCurrency)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = parsed != null,
                    onClick = {
                        vm.updateAccountCreditLimit(card.accountId, (parsed ?: 0L).coerceAtLeast(0L))
                        editingLimitCard = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingLimitCard = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CashFlowBucketsChart(buckets: List<com.ledgerflow.app.domain.CashFlowBucket>) {
    val inflowColor = EmeraldPrimary
    val outflowColor = LedgerThemeColors.outflowBar
    val maxVal = buckets.maxOfOrNull { maxOf(it.inflowMinor, it.outflowMinor) }?.coerceAtLeast(1L) ?: 1L
    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
        ) {
            val n = buckets.size.coerceAtLeast(1)
            val slotW = size.width / n.toFloat()
            val barW = (slotW * 0.28f).coerceIn(8f, 24f)
            val gap = (barW * 0.28f).coerceAtLeast(3f)
            val maxBarH = size.height - 8f

            buckets.forEachIndexed { idx, b ->
                val centerX = idx * slotW + slotW / 2f
                val inH = ((b.inflowMinor.toFloat() / maxVal.toFloat()) * maxBarH).coerceAtLeast(4f)
                val outH = ((b.outflowMinor.toFloat() / maxVal.toFloat()) * maxBarH).coerceAtLeast(4f)
                drawRoundRect(
                    color = inflowColor,
                    topLeft = Offset(centerX - barW - gap / 2f, size.height - inH),
                    size = Size(barW, inH),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                drawRoundRect(
                    color = outflowColor,
                    topLeft = Offset(centerX + gap / 2f, size.height - outH),
                    size = Size(barW, outH),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val visibleLabels = if (buckets.size <= 6) buckets else listOf(buckets.first(), buckets[buckets.size / 2], buckets.last())
            visibleLabels.forEach {
                Text(it.label, fontSize = 10.sp, color = MutedSlate, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatMiniCol(label: String, value: String, valueColor: Color = SlateInk) {
    Column {
        Text(label, fontSize = 10.sp, color = MutedSlate)
        Text(value, style = TabularStyle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
private fun StatBox(
    label: String,
    value: String,
    fg: Color,
    bg: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(10.dp),
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
        Spacer(Modifier.height(2.dp))
        Text(value, style = TabularStyle, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, fontSize = 11.sp, color = MutedSlate)
    }
}

@Composable
private fun ComparisonRow(
    label: String,
    currentText: String,
    prevText: String,
    deltaPct: Float?,
    higherIsGood: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
            Text("Prior: $prevText", fontSize = 11.sp, color = MutedSlate)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(currentText, style = TabularStyle, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlateInk)
            if (deltaPct != null) {
                val up = deltaPct >= 0f
                val good = if (higherIsGood) up else !up
                Text(
                    text = "${if (up) "+" else ""}${String.format(Locale.US, "%.1f", deltaPct)}%",
                    style = TabularStyle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (good) EmeraldDark else RoseOutflow,
                )
            }
        }
    }
}

@Composable
private fun NatureRow(label: String, pctText: String, amountText: String, dotColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dotColor))
            Text(label, fontSize = 12.sp, color = SlateInk)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(pctText, style = TabularStyle, fontSize = 12.sp, color = MutedSlate)
            Text(amountText, style = TabularStyle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SlateInk)
        }
    }
}

@Composable
private fun ReportDownloadCard(
    title: String,
    subtitle: String,
    highlights: List<Pair<String, String>>,
    onDownload: () -> Unit,
    onShare: () -> Unit,
) {
    CleanCard(padding = 16.dp) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
        Text(subtitle, fontSize = 12.sp, color = MutedSlate)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            highlights.forEach { (k, v) ->
                Column {
                    Text(k, fontSize = 10.sp, color = MutedSlate)
                    Text(v, style = TabularStyle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onDownload,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download CSV", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, tint = SlateInk, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share", fontSize = 12.sp, color = SlateInk)
            }
        }
    }
}
