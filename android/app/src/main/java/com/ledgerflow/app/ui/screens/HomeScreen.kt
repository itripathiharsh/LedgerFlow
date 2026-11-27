package com.ledgerflow.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.CategoryHierarchy
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.ads.OnlineOnlyAdBanner
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.ReviewTransactionBottomSheet
import com.ledgerflow.app.ui.components.SectionHeader
import com.ledgerflow.app.ui.components.SourceTagBadge
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
import com.ledgerflow.app.ui.theme.resolveIsDarkTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@Composable
fun HomeScreen(
    vm: LedgerViewModel,
    onOpenDrawer: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenPendingRecords: () -> Unit = onOpenRecords,
    onOpenBudgets: () -> Unit,
    onOpenGoals: () -> Unit = onOpenBudgets,
    onOpenAccounts: () -> Unit,
    onOpenAddAccount: () -> Unit = onOpenAccounts,
    onOpenSettings: () -> Unit,
) {
    val vault by vm.vaultState.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val allCategoriesLookup by vm.categoriesIncludingDeleted.collectAsState()
    val transactions by vm.allTransactions.collectAsState()
    val pendingReview by vm.pendingReviewTransactions.collectAsState()
    val budgets by vm.budgets.collectAsState()
    val savingsGoals by vm.savingsGoals.collectAsState()

    val baseCurrency = vault.baseCurrency
    val masked = vault.hideBalances
    val firstName = remember(vault.userName) {
        val rawFirst = vault.userName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        MoneyFormatter.toTitleCaseName(rawFirst).takeIf { it.isNotBlank() }
    }
    val dayKey = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val monthStart = remember(dayKey) { vm.currentMonthStartEpochMs() }
    val monthFraction = remember(dayKey) { vm.currentMonthFraction() }

    val dashboard = remember(accounts, transactions, baseCurrency, monthStart) {
        AnalyticsCalculator.computeDashboard(accounts, transactions, baseCurrency, monthStart)
    }
    val pendingSummary = remember(pendingReview, baseCurrency) {
        AnalyticsCalculator.computePendingReviewSummary(pendingReview, baseCurrency)
    }
    val accountSummary = remember(accounts, baseCurrency) {
        AnalyticsCalculator.computeAccountSummary(accounts, baseCurrency)
    }
    val budgetProgress = remember(budgets, transactions, allCategoriesLookup, baseCurrency, monthStart, monthFraction) {
        AnalyticsCalculator.computeBudgets(budgets, transactions, baseCurrency, monthStart, monthFraction, allCategoriesLookup)
    }
    val accMap = remember(accounts) { accounts.associateBy { it.id } }
    val categoryMap = remember(allCategoriesLookup) { allCategoriesLookup.associateBy { it.id } }
    val pendingDateFmt = remember { SimpleDateFormat("MMM d • h:mm a", Locale.US) }

    var homeTab by remember { mutableIntStateOf(0) } // 0 = Overview, 1 = Budgets & Goals
    var reviewingTx by remember { mutableStateOf<TransactionEntity?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Top Header: Menu + Home (with Title Case greeting) on left; Theme, Mask, Settings on right
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
                        Text("Home", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        if (firstName != null) {
                            Text("Hi, $firstName", fontSize = 12.sp, color = MutedSlate)
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val isDark = resolveIsDarkTheme(vault.themeMode)
                    IconButton(onClick = { vm.toggleDarkLightMode(isDark) }) {
                        Icon(
                            imageVector = if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = if (isDark) "Switch to Light mode" else "Switch to Dark mode",
                            tint = SlateInk,
                        )
                    }
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

        // Top Sub-Tabs: Overview | Budgets & Goals
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SlateTint,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    listOf("Overview", "Budgets & Goals").forEachIndexed { idx, label ->
                        val active = homeTab == idx
                        Surface(
                            onClick = { homeTab = idx },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) SurfaceCard else Color.Transparent,
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 8.dp),
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) SlateInk else MutedSlate,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (homeTab == 0) {
            // MODULE 1: Accounts Strip + Records / Manage Accounts Pills
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(accounts, key = { it.id }) { acc ->
                            val isOwed = acc.balanceMinor < 0L
                            val isDebt = AccountTypeCatalog.isLiability(acc.accountType) || isOwed
                            CleanCard(
                                modifier = Modifier.width(156.dp),
                                padding = 12.dp,
                                onClick = onOpenAccounts,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CategoryIconBadge(
                                        iconKey = AccountTypeCatalog.iconKey(acc.accountType),
                                        tintHex = if (isDebt && isOwed) "#E11D48" else "#059669",
                                        size = 28.dp,
                                    )
                                    Text(
                                        text = acc.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SlateInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = if (isOwed && AccountTypeCatalog.isLiability(acc.accountType)) {
                                        "Owed ${MoneyFormatter.formatMinor(abs(acc.balanceMinor), acc.currency, masked = masked)}"
                                    } else {
                                        MoneyFormatter.formatMinor(acc.balanceMinor, acc.currency, masked = masked)
                                    },
                                    style = TabularStyle,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOwed) RoseOutflow else SlateInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        item(key = "add_account_tile") {
                            Surface(
                                onClick = onOpenAddAccount,
                                modifier = Modifier
                                    .width(96.dp)
                                    .height(86.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = SlateTint,
                                border = BorderStroke(1.dp, BorderSubtle),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = "Add Account",
                                        tint = EmeraldPrimary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text("Add", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EmeraldPrimary)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        QuickPillLink(
                            label = "Records",
                            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                            onClick = onOpenRecords,
                            modifier = Modifier.weight(1f),
                        )
                        QuickPillLink(
                            label = "Manage Accounts",
                            icon = Icons.Outlined.AccountBalanceWallet,
                            onClick = onOpenAccounts,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // PENDING REVIEW SECTION (Detected payments awaiting user category & purpose)
            if (pendingReview.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeader(
                            title = "Pending Review (${pendingSummary.count})",
                            actionText = "Review all →",
                            onActionClick = onOpenPendingRecords,
                        )
                        CleanCard(
                            padding = 12.dp,
                            borderColor = AmberWarn.copy(alpha = 0.45f),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Total pending: ${MoneyFormatter.formatMinor(pendingSummary.totalVolumeMinor, baseCurrency, masked = masked)}",
                                    style = TabularStyle,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateInk,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (pendingSummary.unassignedCount > 0) {
                                        StatusPill("${pendingSummary.unassignedCount} unassigned", AmberSoft, AmberWarn)
                                    }
                                    StatusPill("${pendingSummary.count} to classify", AmberSoft, AmberWarn)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            pendingReview.take(4).forEachIndexed { idx, ptx ->
                                if (idx > 0) {
                                    HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { reviewingTx = ptx }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ptx.merchant.ifBlank { "Merchant not provided" },
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = SlateInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = pendingDateFmt.format(Date(ptx.occurredAtEpochMs)),
                                            fontSize = 11.sp,
                                            color = MutedSlate,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            StatusPill(
                                                text = ptx.paymentAppName.ifBlank { "Payment Alert" },
                                                containerColor = SkySoft,
                                                contentColor = SkySync,
                                            )
                                            if (ptx.accountId.isBlank()) {
                                                StatusPill("Unassigned Account", AmberSoft, AmberWarn)
                                            } else {
                                                accMap[ptx.accountId]?.let { acc ->
                                                    StatusPill(acc.name, SlateTint, MutedSlate)
                                                }
                                            }
                                            if (ptx.isRefundOrReversal) {
                                                StatusPill("Refund", EmeraldSoft, EmeraldDark)
                                            }
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = MoneyFormatter.formatMinor(
                                                ptx.amountMinor,
                                                ptx.currency,
                                                showPlus = ptx.txType == "INCOME",
                                                masked = masked,
                                            ),
                                            style = TabularStyle,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (ptx.txType == "INCOME") EmeraldDark else RoseOutflow,
                                        )
                                        Text("Tap to classify", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmeraldPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // MODULE 2: Unified Total Balance & Current Cash Flow Card
            item {
                CleanCard(padding = 18.dp) {
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
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateInk,
                    )
                    if (accountSummary.liabilitiesMinor > 0L) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Assets ${MoneyFormatter.formatMinor(accountSummary.assetsMinor, baseCurrency, masked = masked)} • Debt ${MoneyFormatter.formatMinor(accountSummary.liabilitiesMinor, baseCurrency, masked = masked)}",
                            style = TabularStyle,
                            fontSize = 11.sp,
                            color = MutedSlate,
                        )
                    }

                    HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Current Cash Flow", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        StatusPill(
                            text = "${if (dashboard.savingsRatePercent >= 0) "+" else ""}${dashboard.savingsRatePercent}% Saved",
                            containerColor = if (dashboard.netSavingsMinor >= 0L) EmeraldSoft else RoseSoft,
                            contentColor = if (dashboard.netSavingsMinor >= 0L) EmeraldDark else RoseOutflow,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CashPill(
                            label = "EARNED",
                            value = MoneyFormatter.formatMinor(dashboard.inflowMinor, baseCurrency, showPlus = true, masked = masked),
                            bg = EmeraldSoft,
                            fg = EmeraldDark,
                            modifier = Modifier.weight(1f),
                        )
                        CashPill(
                            label = "SPENT",
                            value = MoneyFormatter.formatMinor(-dashboard.outflowMinor, baseCurrency, masked = masked),
                            bg = RoseSoft,
                            fg = RoseOutflow,
                            modifier = Modifier.weight(1f),
                        )
                        CashPill(
                            label = "SAVED",
                            value = MoneyFormatter.formatMinor(dashboard.netSavingsMinor, baseCurrency, showPlus = true, masked = masked),
                            bg = SlateTint,
                            fg = if (dashboard.netSavingsMinor >= 0L) EmeraldDark else RoseOutflow,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    WeeklyBarChart(dashboard.weeklyInflow, dashboard.weeklyOutflow)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LegendDot("Inflow", EmeraldPrimary)
                        LegendDot("Outflow", LedgerThemeColors.outflowBar)
                    }
                }
            }

            // MODULE 3: Recent Records (Last 3 transactions + "See all →")
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(
                        title = "Recent Records",
                        actionText = "See all →",
                        onActionClick = onOpenRecords,
                    )
                    CleanCard(padding = 4.dp) {
                        val topTx = transactions.take(3)
                        if (topTx.isEmpty()) {
                            Text(
                                text = "No transactions yet. Tap + to add one.",
                                color = MutedSlate,
                                modifier = Modifier.padding(16.dp),
                                fontSize = 13.sp,
                            )
                        } else {
                            topTx.forEachIndexed { idx, tx ->
                                if (idx > 0) HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(horizontal = 12.dp))
                                val isPending = tx.reviewStatus == TransactionEntity.STATUS_PENDING_REVIEW
                                val assigned = with(CategoryHierarchy) { tx.allAssignedCategoryIds() }.mapNotNull { categoryMap[it] }
                                val cat = assigned.firstOrNull() ?: categoryMap[tx.categoryId]
                                val catLabel = if (isPending) {
                                    accMap[tx.accountId]?.name ?: "Unassigned Account"
                                } else {
                                    assigned.map { it.name }.distinct().joinToString(" + ").ifBlank { cat?.name ?: tx.txType }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { reviewingTx = tx }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CategoryIconBadge(cat?.iconKey ?: "category", cat?.colorHex ?: if (isPending) "#D97706" else "#059669", size = 36.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tx.merchant.ifBlank { "Merchant not provided" },
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = SlateInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(catLabel, fontSize = 12.sp, color = MutedSlate, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            SourceTagBadge(tx.sourceTag, tx.paymentAppName)
                                            if (isPending) {
                                                StatusPill("Pending", AmberSoft, AmberWarn)
                                            }
                                        }
                                    }
                                    Text(
                                        text = MoneyFormatter.formatMinor(tx.amountMinor, tx.currency, showPlus = tx.txType == "INCOME", masked = masked),
                                        style = TabularStyle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (tx.txType == "INCOME") EmeraldDark else if (tx.txType == "EXPENSE") RoseOutflow else SlateInk,
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
        } else {
            // Sub-Tab 2: Clean Monthly Budgets & Savings Goals overview
            val activeGoals = savingsGoals.filter { it.status == "ACTIVE" }
            if (budgetProgress.isEmpty() && activeGoals.isEmpty()) {
                item {
                    CleanCard(padding = 18.dp) {
                        Text("Create your first budget or goal", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Track monthly spending caps or save toward milestones like an Emergency Fund or new gear.",
                            fontSize = 13.sp,
                            color = MutedSlate,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = onOpenBudgets,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Open Budgets & Goals", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                if (budgetProgress.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Monthly Budgets (${budgetProgress.size})",
                            actionText = "Manage →",
                            onActionClick = onOpenBudgets,
                        )
                    }
                    items(budgetProgress, key = { it.budget.id }) { bp ->
                        val limitBase = FxConverter.convertMinor(bp.budget.limitMinor, bp.budget.currency, baseCurrency)
                        val isOver = bp.remainingMinor < 0L
                        CleanCard(padding = 14.dp, onClick = onOpenBudgets) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(bp.budget.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SlateInk)
                                Text(
                                    text = if (isOver) {
                                        "${MoneyFormatter.formatMinor(abs(bp.remainingMinor), baseCurrency, masked = masked)} over"
                                    } else {
                                        "${MoneyFormatter.formatMinor(bp.remainingMinor, baseCurrency, masked = masked)} left"
                                    },
                                    style = TabularStyle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOver) RoseOutflow else if (bp.onTrack) EmeraldDark else AmberWarn,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { bp.ratio.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = if (isOver) RoseOutflow else if (bp.onTrack) EmeraldPrimary else AmberWarn,
                                trackColor = SlateTint,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${MoneyFormatter.formatMinor(bp.spentMinor, baseCurrency, masked = masked)} spent of ${MoneyFormatter.formatMinor(limitBase, baseCurrency, masked = masked)}",
                                style = TabularStyle,
                                fontSize = 11.sp,
                                color = MutedSlate,
                            )
                        }
                    }
                }

                if (activeGoals.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Savings Goals (${activeGoals.size})",
                            actionText = "Goals →",
                            onActionClick = onOpenGoals,
                        )
                    }
                    items(activeGoals, key = { it.id }) { goal ->
                        val gp = AnalyticsCalculator.computeGoalProgress(goal, baseCurrency)
                        val accentColor = parseSliceColor(goal.colorHex, EmeraldPrimary)
                        CleanCard(padding = 14.dp, onClick = onOpenGoals) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    CategoryIconBadge(goal.iconKey, goal.colorHex, size = 34.dp)
                                    Text(
                                        text = goal.name,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = SlateInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                StatusPill("${gp.percent}%", EmeraldSoft, EmeraldDark)
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { gp.ratio },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = accentColor,
                                trackColor = SlateTint,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${MoneyFormatter.formatMinor(gp.savedBaseMinor, baseCurrency, masked = masked)} saved of ${MoneyFormatter.formatMinor(gp.targetBaseMinor, baseCurrency, masked = masked)}",
                                style = TabularStyle,
                                fontSize = 11.sp,
                                color = MutedSlate,
                            )
                        }
                    }
                }
            }
        }
    }

    reviewingTx?.let { target ->
        ReviewTransactionBottomSheet(
            tx = target,
            accounts = accounts,
            categories = categories,
            baseCurrency = baseCurrency,
            onDismiss = { reviewingTx = null },
            onDelete = { del ->
                vm.deleteTransaction(del.id)
                reviewingTx = null
            },
            onSave = { txType, magMinor, accountId, transferTargetId, categoryId, categoryIdsCsv, spendingPurpose, merchant, note, labelsCsv ->
                vm.confirmOrUpdateTransaction(
                    txId = target.id,
                    txType = txType,
                    magnitudeMinor = magMinor,
                    currency = target.currency,
                    accountId = accountId,
                    transferAccountId = transferTargetId,
                    categoryId = categoryId,
                    categoryIdsCsv = categoryIdsCsv,
                    spendingPurpose = spendingPurpose,
                    merchant = merchant,
                    note = note,
                    labelsCsv = labelsCsv,
                    markConfirmed = true,
                )
                reviewingTx = null
            },
        )
    }
}

@Composable
private fun QuickPillLink(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = SurfaceCard,
        border = BorderStroke(1.dp, BorderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SlateInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
    val inflowColor = EmeraldPrimary
    val outflowColor = LedgerThemeColors.outflowBar
    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
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
                        color = inflowColor,
                        topLeft = Offset(cx - barW - gap / 2f, size.height - inH),
                        size = Size(barW, inH),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }
                if (rawOut > 0L) {
                    val outH = ((rawOut / maxVal) * (size.height - 8f)).coerceAtLeast(6f)
                    drawRoundRect(
                        color = outflowColor,
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
