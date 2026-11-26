package com.ledgerflow.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.ads.OnlineOnlyAdBanner
import com.ledgerflow.app.ui.components.CategoryDonutChart
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.EmptyPlaceholder
import com.ledgerflow.app.ui.components.PieChartSlice
import com.ledgerflow.app.ui.components.SectionHeader
import com.ledgerflow.app.ui.components.SourceTagBadge
import com.ledgerflow.app.ui.components.SpendHeatmapCard
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.components.parseSliceColor
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.AmberWarn
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.RoseSoft
import com.ledgerflow.app.ui.theme.SkySoft
import com.ledgerflow.app.ui.theme.SkySync
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.TabularStyle
import java.util.Calendar
import kotlin.math.max

@Composable
fun HomeScreen(
    vm: LedgerViewModel,
    onOpenQuickAdd: (initialType: String) -> Unit,
    onOpenRecords: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenAnalytics: () -> Unit = onOpenBudgets,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vault by vm.vaultState.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val transactions by vm.allTransactions.collectAsState()
    val budgets by vm.budgets.collectAsState()

    val baseCurrency = vault.baseCurrency
    val masked = vault.hideBalances
    val firstName = remember(vault.userName) {
        vault.userName.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
    }
    val dayKey = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val monthStart = remember(dayKey) { vm.currentMonthStartEpochMs() }
    val monthFraction = remember(dayKey) { vm.currentMonthFraction() }

    val dashboard = remember(accounts, transactions, baseCurrency, monthStart) {
        AnalyticsCalculator.computeDashboard(accounts, transactions, baseCurrency, monthStart)
    }
    val budgetProgress = remember(budgets, transactions, baseCurrency, monthStart, monthFraction) {
        AnalyticsCalculator.computeBudgets(budgets, transactions, baseCurrency, monthStart, monthFraction)
    }
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val monthlyHeatmap = remember(transactions, baseCurrency, dayKey) {
        AnalyticsCalculator.computeMonthlySpendHeatmap(transactions, baseCurrency)
    }
    val rollingHeatmap = remember(transactions, baseCurrency, dayKey) {
        AnalyticsCalculator.computeRollingSpendHeatmap(transactions, baseCurrency, weeks = 12)
    }
    val categorySlices = remember(transactions, categories, baseCurrency, monthStart) {
        AnalyticsCalculator.computeCategoryBreakdown(transactions, categories, baseCurrency, monthStart)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (firstName != null) "Hi, $firstName" else "Overview",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateInk,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(onClick = { vm.toggleHideBalances() }) {
                        Icon(
                            imageVector = if (masked) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = "Mask balances",
                            tint = SlateInk,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = SlateInk,
                        )
                    }
                }
            }
        }

        // Hero Balance Card
        item {
            CleanCard(padding = 20.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "TOTAL BALANCE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MutedSlate,
                        letterSpacing = 0.5.sp,
                    )
                    StatusPill(baseCurrency, SlateTint, SlateInk)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = MoneyFormatter.formatMinor(dashboard.netWorthMinor, baseCurrency, masked = masked),
                    style = TabularStyle,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateInk,
                )
                Spacer(Modifier.height(8.dp))
                val netStr = MoneyFormatter.formatMinor(dashboard.netSavingsMinor, baseCurrency, showPlus = true, masked = masked)
                StatusPill(
                    text = "${if (dashboard.savingsRatePercent >= 0) "+" else ""}${dashboard.savingsRatePercent}% savings ($netStr this month)",
                    containerColor = if (dashboard.netSavingsMinor >= 0) EmeraldSoft else RoseSoft,
                    contentColor = if (dashboard.netSavingsMinor >= 0) EmeraldDark else RoseOutflow,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    QuickActionTile("Expense", Icons.Outlined.Add, primary = true) { onOpenQuickAdd("EXPENSE") }
                    QuickActionTile("Transfer", Icons.Outlined.SwapHoriz, primary = false) { onOpenQuickAdd("TRANSFER") }
                    QuickActionTile("Budgets", Icons.Outlined.PieChart, primary = false) { onOpenBudgets() }
                    QuickActionTile("Accounts", Icons.Outlined.AccountBalanceWallet, primary = false) { onOpenAccounts() }
                }
            }
        }

        // Accounts Carousel
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "Accounts", actionText = "Manage", onActionClick = onOpenAccounts)
                if (accounts.isEmpty()) {
                    EmptyPlaceholder("No accounts yet", "Tap Manage to add a wallet, card, or bank account.")
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(accounts, key = { it.id }) { acc ->
                            CleanCard(
                                modifier = Modifier.width(172.dp),
                                padding = 14.dp,
                                onClick = onOpenAccounts,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CategoryIconBadge(if (acc.accountType == "CASH") "payments" else "bank", "#0284C7", size = 32.dp)
                                    StatusPill(acc.accountType, SlateTint, MutedSlate)
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = acc.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SlateInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = MoneyFormatter.formatMinor(acc.balanceMinor, acc.currency, masked = masked),
                                    style = TabularStyle,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateInk,
                                )
                                if (acc.currency != baseCurrency) {
                                    val fxVal = FxConverter.convertMinor(acc.balanceMinor, acc.currency, baseCurrency)
                                    Text(
                                        text = "≈ ${MoneyFormatter.formatMinor(fxVal, baseCurrency, masked = masked)}",
                                        style = TabularStyle,
                                        fontSize = 11.sp,
                                        color = SkySync,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Monthly Cash Flow
        item {
            CleanCard(padding = 16.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Monthly Cash Flow", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    StatusPill("This Month", SlateTint, MutedSlate)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CashPill("EARNED", MoneyFormatter.formatMinor(dashboard.inflowMinor, baseCurrency, showPlus = true, masked = masked), EmeraldSoft, EmeraldDark, Modifier.weight(1f))
                    CashPill("SPENT", MoneyFormatter.formatMinor(-dashboard.outflowMinor, baseCurrency, masked = masked), RoseSoft, RoseOutflow, Modifier.weight(1f))
                    CashPill("SAVED", MoneyFormatter.formatMinor(dashboard.netSavingsMinor, baseCurrency, showPlus = true, masked = masked), SkySoft, SkySync, Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                WeeklyBarChart(dashboard.weeklyInflow, dashboard.weeklyOutflow)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LegendDot("Inflow", EmeraldPrimary)
                        LegendDot("Outflow", Color(0xFFCBD5E1))
                    }
                    Text(
                        text = "${if (dashboard.savingsRatePercent > 0) "+" else ""}${dashboard.savingsRatePercent}% Savings Rate",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (dashboard.savingsRatePercent >= 0) EmeraldDark else RoseOutflow,
                    )
                }
            }
        }

        // Daily Spend Heatmap (GitHub-style monthly map) + Category Breakdown Pie
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "Spend Insights", actionText = "Analytics", onActionClick = onOpenAnalytics)
                SpendHeatmapCard(
                    monthlyData = monthlyHeatmap,
                    rollingData = rollingHeatmap,
                    currency = baseCurrency,
                    masked = masked,
                    allowModeSwitch = true,
                )
            }
        }

        if (categorySlices.isNotEmpty()) {
            item {
                val totalCatSpend = categorySlices.sumOf { it.amountMinor }
                val pieSlices = remember(categorySlices) {
                    categorySlices.map { s ->
                        PieChartSlice(
                            id = s.categoryId ?: s.name,
                            label = s.name,
                            valueMinor = s.amountMinor,
                            color = parseSliceColor(s.colorHex, EmeraldPrimary),
                            ratio = s.ratio,
                        )
                    }
                }
                CleanCard(padding = 16.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Category Breakdown", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        StatusPill("This Month", SlateTint, MutedSlate)
                    }
                    Spacer(Modifier.height(12.dp))
                    CategoryDonutChart(
                        slices = pieSlices,
                        totalMinor = totalCatSpend,
                        currency = baseCurrency,
                        masked = masked,
                        centerSubtitle = "This Month",
                    )
                }
            }
        }

        // Budgets Watch (only when budgets exist or subtle invite)
        if (budgetProgress.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(title = "Budgets Watch", actionText = "Manage", onActionClick = onOpenBudgets)
                    CleanCard(padding = 14.dp) {
                        budgetProgress.take(2).forEachIndexed { idx, bp ->
                            if (idx > 0) HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(bp.budget.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SlateInk)
                                val limitBase = FxConverter.convertMinor(bp.budget.limitMinor, bp.budget.currency, baseCurrency)
                                Text(
                                    text = "${MoneyFormatter.formatMinor(bp.spentMinor, baseCurrency, masked = masked)} / ${MoneyFormatter.formatMinor(limitBase, baseCurrency, masked = masked)}",
                                    style = TabularStyle,
                                    fontSize = 12.sp,
                                    color = MutedSlate,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { bp.ratio.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = if (bp.ratio >= 0.85f) AmberWarn else EmeraldPrimary,
                                trackColor = SlateTint,
                            )
                        }
                    }
                }
            }
        }

        // Recent Activity
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "Recent Activity", actionText = "View All", onActionClick = onOpenRecords)
                CleanCard(padding = 4.dp) {
                    val topTx = transactions.take(4)
                    if (topTx.isEmpty()) {
                        Text("No transactions yet. Tap + to add one.", color = MutedSlate, modifier = Modifier.padding(14.dp), fontSize = 13.sp)
                    } else {
                        topTx.forEachIndexed { idx, tx ->
                            if (idx > 0) HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(horizontal = 12.dp))
                            val cat = categoryMap[tx.categoryId]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenRecords() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CategoryIconBadge(cat?.iconKey ?: "category", cat?.colorHex ?: "#059669", size = 38.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tx.merchant, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(cat?.name ?: "General", fontSize = 12.sp, color = MutedSlate)
                                        SourceTagBadge(tx.sourceTag)
                                    }
                                }
                                Text(
                                    text = MoneyFormatter.formatMinor(tx.amountMinor, tx.currency, showPlus = tx.txType == "INCOME", masked = masked),
                                    style = TabularStyle,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (tx.txType == "INCOME") EmeraldDark else SlateInk,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            OnlineOnlyAdBanner()
        }
    }
}

@Composable
private fun QuickActionTile(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (primary) EmeraldPrimary else SlateTint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (primary) Color.White else SlateInk,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = SlateInk)
    }
}

@Composable
private fun CashPill(
    label: String,
    value: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg)
            Spacer(Modifier.height(2.dp))
            Text(value, style = TabularStyle, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlateInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WeeklyBarChart(inflows: List<Long>, outflows: List<Long>) {
    val maxVal = max(inflows.maxOrNull() ?: 1L, outflows.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp),
        ) {
            val slotW = size.width / 4f
            val barW = 14.dp.toPx()
            val gap = 6.dp.toPx()
            for (i in 0 until 4) {
                val cx = i * slotW + slotW / 2f
                val rawIn = inflows.getOrElse(i) { 0L }
                val rawOut = outflows.getOrElse(i) { 0L }
                if (rawIn > 0L) {
                    val inH = ((rawIn / maxVal) * (size.height - 8f)).coerceAtLeast(6f)
                    drawRoundRect(
                        color = EmeraldPrimary,
                        topLeft = Offset(cx - barW - gap / 2f, size.height - inH),
                        size = Size(barW, inH),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }
                if (rawOut > 0L) {
                    val outH = ((rawOut / maxVal) * (size.height - 8f)).coerceAtLeast(6f)
                    drawRoundRect(
                        color = Color(0xFFCBD5E1),
                        topLeft = Offset(cx + gap / 2f, size.height - outH),
                        size = Size(barW, outH),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            listOf("Wk 1", "Wk 2", "Wk 3", "Wk 4").forEach { lbl ->
                Text(lbl, fontSize = 11.sp, color = MutedSlate, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, fontSize = 11.sp, color = MutedSlate)
    }
}
