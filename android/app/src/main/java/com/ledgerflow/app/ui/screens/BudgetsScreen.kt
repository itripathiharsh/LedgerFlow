package com.ledgerflow.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.SavingsGoalEntity
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.SectionHeader
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.components.categoryIcon
import com.ledgerflow.app.ui.components.parseSliceColor
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.AmberSoft
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
import com.ledgerflow.app.ui.theme.SurfaceCard
import com.ledgerflow.app.ui.theme.TabularStyle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val goalColorChoices = listOf(
    "#059669",
    "#0284C7",
    "#D97706",
    "#14B8A6",
    "#E11D48",
    "#8B5CF6",
)

private val goalIconChoices = listOf(
    "savings" to "Goal",
    "home" to "Home",
    "flight" to "Travel",
    "school" to "Study",
    "shopping_bag" to "Gear",
    "directions_transit" to "Car",
    "medical_services" to "Health",
    "gift" to "Gift",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen(
    vm: LedgerViewModel,
    initialTab: Int = 0,
    addTrigger: Int = 0,
    onOpenDrawer: () -> Unit = {},
    onOpenSettings: () -> Unit,
) {
    val budgets by vm.budgets.collectAsState()
    val planned by vm.plannedPayments.collectAsState()
    val savingsGoals by vm.savingsGoals.collectAsState()
    val transactions by vm.allTransactions.collectAsState()
    val categories by vm.categories.collectAsState()
    val allCategoriesLookup by vm.categoriesIncludingDeleted.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val vault by vm.vaultState.collectAsState()

    val baseCurrency = vault.baseCurrency
    val masked = vault.hideBalances
    val dayKey = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val monthStart = remember(dayKey) { vm.currentMonthStartEpochMs() }
    val monthEnd = remember(dayKey) { vm.currentMonthEndEpochMs() }
    val monthFraction = remember(dayKey) { vm.currentMonthFraction() }
    val daysRemaining = remember(dayKey) { vm.daysRemainingInMonth() }

    val progressList = remember(budgets, transactions, allCategoriesLookup, baseCurrency, monthStart, monthFraction) {
        AnalyticsCalculator.computeBudgets(budgets, transactions, baseCurrency, monthStart, monthFraction, allCategoriesLookup)
    }

    val totalLimit = remember(budgets, baseCurrency) {
        AnalyticsCalculator.computeTotalBudgetLimit(budgets, baseCurrency)
    }
    val totalSpent = remember(budgets, transactions, allCategoriesLookup, baseCurrency, monthStart) {
        AnalyticsCalculator.computeTotalBudgetedSpent(budgets, transactions, baseCurrency, monthStart, allCategoriesLookup)
    }
    val totalPlanned = remember(planned, baseCurrency, monthStart, monthEnd) {
        AnalyticsCalculator.computeMonthlyPlannedExpenseMinor(planned, baseCurrency, monthStart, monthEnd)
    }
    val netRemaining = totalLimit - totalSpent
    val safeRemaining = netRemaining.coerceAtLeast(0L)
    val isOverTotalBudget = progressList.isNotEmpty() && netRemaining < 0L
    val dailySafe = safeRemaining / daysRemaining.coerceAtLeast(1)
    val allBudgetsOnTrack = progressList.isNotEmpty() && progressList.all { it.onTrack }

    val catMap = remember(allCategoriesLookup) { allCategoriesLookup.associateBy { it.id } }
    val accMap = remember(accounts) { accounts.associateBy { it.id } }
    val dateFmt = remember { SimpleDateFormat("MMM d", Locale.US) }
    val todayBounds = remember(dayKey) {
        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = startCal.timeInMillis
        startCal.add(Calendar.DAY_OF_MONTH, 1)
        startMs to startCal.timeInMillis
    }

    var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) } // 0=Budgets, 1=Goals, 2=Planned
    LaunchedEffect(initialTab) {
        selectedTab = initialTab.coerceIn(0, 2)
    }
    var showNewBudget by remember { mutableStateOf(false) }
    var showNewPlanned by remember { mutableStateOf(false) }
    var showGoalEditor by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<SavingsGoalEntity?>(null) }
    var addFundsTarget by remember { mutableStateOf<SavingsGoalEntity?>(null) }
    var goalStatusFilter by remember { mutableStateOf("ACTIVE") }

    LaunchedEffect(addTrigger) {
        if (addTrigger > 0) {
            when (selectedTab) {
                1 -> {
                    editingGoal = null
                    showGoalEditor = true
                }
                2 -> showNewPlanned = true
                else -> showNewBudget = true
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Open menu", tint = SlateInk)
                    }
                    Text("Budgets & Goals", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = SlateInk)
                }
            }
        }

        // Segmented switcher: Budgets | Goals | Planned
        item {
            Surface(shape = RoundedCornerShape(14.dp), color = SlateTint, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(4.dp)) {
                    listOf("Budgets", "Goals", "Planned").forEachIndexed { idx, label ->
                        val active = selectedTab == idx
                        Surface(
                            onClick = { selectedTab = idx },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) SurfaceCard else Color.Transparent,
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (active) SlateInk else MutedSlate)
                            }
                        }
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                if (progressList.isEmpty()) {
                    item {
                        CleanCard(padding = 18.dp) {
                            Text(
                                text = "Create your first monthly budget",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = SlateInk,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Set an overall spending cap or per-category limit to track daily safe spend.",
                                fontSize = 13.sp,
                                color = MutedSlate,
                            )
                            if (totalPlanned > 0L) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Upcoming planned bills: ${MoneyFormatter.formatMinor(totalPlanned, baseCurrency, masked = masked)}",
                                    style = TabularStyle,
                                    fontSize = 12.sp,
                                    color = SkySync,
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { showNewBudget = true },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Create Budget", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    // Safe-to-Spend Hero Card (strictly shown when at least 1 budget exists)
                    item {
                        CleanCard(padding = 16.dp) {
                            Text("Monthly Safe-to-Spend", fontSize = 12.sp, color = MutedSlate, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            StatusPill(
                                text = when {
                                    isOverTotalBudget -> "Over monthly budget"
                                    allBudgetsOnTrack -> "Within monthly budget pace"
                                    else -> "Ahead of monthly budget pace"
                                },
                                containerColor = when {
                                    isOverTotalBudget -> RoseSoft
                                    allBudgetsOnTrack -> EmeraldSoft
                                    else -> AmberSoft
                                },
                                contentColor = when {
                                    isOverTotalBudget -> RoseOutflow
                                    allBudgetsOnTrack -> EmeraldDark
                                    else -> AmberWarn
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (isOverTotalBudget) {
                                    "${MoneyFormatter.formatMinor(abs(netRemaining), baseCurrency, masked = masked)} over"
                                } else {
                                    "${MoneyFormatter.formatMinor(safeRemaining, baseCurrency, masked = masked)} left"
                                },
                                style = TabularStyle,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isOverTotalBudget) RoseOutflow else SlateInk,
                            )
                            Text(
                                text = if (isOverTotalBudget) {
                                    "Monthly spending exceeded budgeted limit by ${MoneyFormatter.formatMinor(abs(netRemaining), baseCurrency, masked = masked)}"
                                } else {
                                    "${MoneyFormatter.formatMinor(dailySafe, baseCurrency, masked = masked)} / day for remaining $daysRemaining days"
                                },
                                style = TabularStyle,
                                fontSize = 12.sp,
                                color = MutedSlate,
                            )
                            Spacer(Modifier.height(12.dp))
                            val sumAll = totalSpent + totalPlanned + safeRemaining
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(SlateTint),
                            ) {
                                if (sumAll > 0L) {
                                    if (totalSpent > 0L) Box(Modifier.weight(totalSpent.toFloat()).fillMaxHeight().background(SlateInk))
                                    if (totalPlanned > 0L) Box(Modifier.weight(totalPlanned.toFloat()).fillMaxHeight().background(SkySync))
                                    if (safeRemaining > 0L) Box(Modifier.weight(safeRemaining.toFloat()).fillMaxHeight().background(EmeraldPrimary))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Spent ${MoneyFormatter.formatMinor(totalSpent, baseCurrency, masked = masked)}", fontSize = 11.sp, color = SlateInk)
                                Text("Bills ${MoneyFormatter.formatMinor(totalPlanned, baseCurrency, masked = masked)}", fontSize = 11.sp, color = SkySync)
                                Text("Safe ${MoneyFormatter.formatMinor(safeRemaining, baseCurrency, masked = masked)}", fontSize = 11.sp, color = EmeraldDark)
                            }
                        }
                    }

                    item {
                        SectionHeader(
                            title = "Active Budgets (${progressList.size})",
                            actionText = "+ New Budget",
                            onActionClick = { showNewBudget = true },
                        )
                    }

                    items(progressList, key = { it.budget.id }) { bp ->
                        val cat = catMap[bp.budget.categoryId]
                        val limitBase = FxConverter.convertMinor(bp.budget.limitMinor, bp.budget.currency, baseCurrency)
                        val isOver = bp.remainingMinor < 0L
                        CleanCard(padding = 16.dp) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CategoryIconBadge(cat?.iconKey ?: "category", cat?.colorHex ?: "#059669", size = 38.dp)
                                    Column {
                                        Text(bp.budget.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                                        Text(
                                            text = "${MoneyFormatter.formatMinor(bp.spentMinor, baseCurrency, masked = masked)} of ${MoneyFormatter.formatMinor(limitBase, baseCurrency, masked = masked)}",
                                            style = TabularStyle,
                                            fontSize = 12.sp,
                                            color = MutedSlate,
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = if (isOver) {
                                            "${MoneyFormatter.formatMinor(abs(bp.remainingMinor), baseCurrency, masked = masked)} over"
                                        } else {
                                            "${MoneyFormatter.formatMinor(bp.remainingMinor, baseCurrency, masked = masked)} left"
                                        },
                                        style = TabularStyle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isOver) RoseOutflow else if (bp.onTrack) EmeraldDark else AmberWarn,
                                    )
                                    Text("${(bp.ratio * 100).toInt()}%", style = TabularStyle, fontSize = 11.sp, color = MutedSlate)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { bp.ratio.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = if (isOver) RoseOutflow else if (bp.onTrack) EmeraldPrimary else AmberWarn,
                                trackColor = SlateTint,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusPill(
                                    text = if (isOver) "Over budget" else if (bp.onTrack) "On track" else "Ahead of pace",
                                    containerColor = if (isOver) RoseSoft else if (bp.onTrack) EmeraldSoft else AmberSoft,
                                    contentColor = if (isOver) RoseOutflow else if (bp.onTrack) EmeraldDark else AmberWarn,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("Month: ${(monthFraction * 100).toInt()}%", fontSize = 11.sp, color = MutedSlate)
                                    TextButton(
                                        onClick = { vm.deleteBudget(bp.budget.id) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    ) {
                                        Text("Remove", fontSize = 11.sp, color = AmberWarn)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                val filteredGoals = savingsGoals.filter { it.status == goalStatusFilter }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val activeCount = savingsGoals.count { it.status == "ACTIVE" }
                        val pausedCount = savingsGoals.count { it.status == "PAUSED" }
                        val reachedCount = savingsGoals.count { it.status == "REACHED" }
                        listOf(
                            Triple("ACTIVE", "Active ($activeCount)", activeCount),
                            Triple("PAUSED", "Paused ($pausedCount)", pausedCount),
                            Triple("REACHED", "Reached ($reachedCount)", reachedCount),
                        ).forEach { (key, label, _) ->
                            FilterChip(
                                selected = goalStatusFilter == key,
                                onClick = { goalStatusFilter = key },
                                label = { Text(label, fontSize = 12.sp) },
                            )
                        }
                    }
                }

                item {
                    SectionHeader(
                        title = "Savings Goals (${filteredGoals.size})",
                        actionText = "+ New Goal",
                        onActionClick = {
                            editingGoal = null
                            showGoalEditor = true
                        },
                    )
                }

                if (filteredGoals.isEmpty()) {
                    item {
                        CleanCard(padding = 22.dp) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CategoryIconBadge("savings", "#059669", size = 54.dp)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = when (goalStatusFilter) {
                                        "PAUSED" -> "No paused goals"
                                        "REACHED" -> "No reached goals yet"
                                        else -> "Save for your goals"
                                    },
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateInk,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = when (goalStatusFilter) {
                                        "PAUSED" -> "Paused goals keep your progress saved until you are ready to resume."
                                        "REACHED" -> "Keep contributing to active goals and they move here once target is met."
                                        else -> "Set a target for your Emergency Fund, New Laptop, or trip and track contributions."
                                    },
                                    fontSize = 13.sp,
                                    color = MutedSlate,
                                )
                                if (goalStatusFilter == "ACTIVE") {
                                    Spacer(Modifier.height(14.dp))
                                    Button(
                                        onClick = {
                                            editingGoal = null
                                            showGoalEditor = true
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text("+ New Goal", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(filteredGoals, key = { it.id }) { goal ->
                        val gp = AnalyticsCalculator.computeGoalProgress(goal, baseCurrency)
                        val accentColor = parseSliceColor(goal.colorHex, EmeraldPrimary)
                        val linkedAcc = goal.linkedAccountId?.let { accMap[it] }
                        val metaLine = listOfNotNull(
                            linkedAcc?.name,
                            goal.targetDateEpochMs?.let { "Target ${dateFmt.format(Date(it))}" },
                        ).joinToString(" • ")

                        CleanCard(
                            padding = 16.dp,
                            onClick = {
                                editingGoal = goal
                                showGoalEditor = true
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    CategoryIconBadge(goal.iconKey, goal.colorHex, size = 40.dp)
                                    Column {
                                        Text(
                                            text = goal.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SlateInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (metaLine.isNotEmpty()) {
                                            Text(metaLine, fontSize = 11.sp, color = MutedSlate)
                                        }
                                    }
                                }
                                StatusPill(
                                    text = "${gp.percent}%",
                                    containerColor = if (goal.status == "REACHED") EmeraldSoft else SlateTint,
                                    contentColor = if (goal.status == "REACHED") EmeraldDark else SlateInk,
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { gp.ratio },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = accentColor,
                                trackColor = SlateTint,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${MoneyFormatter.formatMinor(gp.savedBaseMinor, baseCurrency, masked = masked)} / ${MoneyFormatter.formatMinor(gp.targetBaseMinor, baseCurrency, masked = masked)}",
                                    style = TabularStyle,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SlateInk,
                                )
                                Text(
                                    text = if (gp.remainingBaseMinor == 0L) "Target reached!" else "${MoneyFormatter.formatMinor(gp.remainingBaseMinor, baseCurrency, masked = masked)} left",
                                    style = TabularStyle,
                                    fontSize = 12.sp,
                                    color = if (gp.remainingBaseMinor == 0L) EmeraldDark else MutedSlate,
                                )
                            }

                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (goal.status != "REACHED") {
                                        TextButton(
                                            onClick = { vm.setGoalPaused(goal.id, goal.status != "PAUSED") },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = if (goal.status == "PAUSED") "Resume" else "Pause",
                                                fontSize = 12.sp,
                                                color = MutedSlate,
                                            )
                                        }
                                    }
                                    TextButton(
                                        onClick = { vm.deleteSavingsGoal(goal.id) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text("Remove", fontSize = 12.sp, color = RoseOutflow)
                                    }
                                }
                                if (goal.status != "REACHED") {
                                    OutlinedButton(
                                        onClick = { addFundsTarget = goal },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.45f)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp),
                                    ) {
                                        Text("+ Add Funds", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EmeraldPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                item {
                    SectionHeader(
                        title = "Scheduled Bills & Income (${planned.size})",
                        actionText = "+ New Bill",
                        onActionClick = { showNewPlanned = true },
                    )
                }
                // Planned Payments Timeline
                if (planned.isEmpty()) {
                    item {
                        CleanCard(padding = 18.dp) {
                            Text("No planned bills", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SlateInk)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Schedule rent, utilities, EMI, or recurring income to track due dates.",
                                fontSize = 13.sp,
                                color = MutedSlate,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { showNewPlanned = true },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Add Planned Bill", fontSize = 13.sp)
                            }
                        }
                    }
                } else {
                    val (todayStartMs, tomorrowStartMs) = todayBounds
                    items(planned, key = { it.id }) { p ->
                        val cat = catMap[p.categoryId]
                        val linkedAcc = accMap[p.accountId]
                        val isIncome = p.txType.equals("INCOME", ignoreCase = true)
                        val isOverdue = p.nextDueAtEpochMs < todayStartMs
                        val isDueToday = p.nextDueAtEpochMs in todayStartMs until tomorrowStartMs
                        val dueText = when {
                            isOverdue -> "Overdue • ${dateFmt.format(Date(p.nextDueAtEpochMs))}"
                            isDueToday -> "Due Today"
                            else -> "Due ${dateFmt.format(Date(p.nextDueAtEpochMs))}"
                        }
                        val dueBg = when {
                            isOverdue -> RoseSoft
                            isDueToday -> AmberSoft
                            else -> SkySoft
                        }
                        val dueFg = when {
                            isOverdue -> RoseOutflow
                            isDueToday -> AmberWarn
                            else -> SkySync
                        }
                        CleanCard(padding = 14.dp) {
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
                                    CategoryIconBadge(cat?.iconKey ?: if (isIncome) "payments" else "home", cat?.colorHex ?: if (isIncome) "#059669" else "#0284C7", size = 38.dp)
                                    Column {
                                        Text(p.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                                        Text(
                                            text = listOfNotNull(
                                                linkedAcc?.name,
                                                p.cadence.lowercase().replaceFirstChar { it.uppercase() },
                                            ).joinToString(" • "),
                                            fontSize = 11.sp,
                                            color = MutedSlate,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            StatusPill(dueText, dueBg, dueFg)
                                            Surface(
                                                onClick = { vm.togglePlannedAutoPay(p.id, !p.autoPay) },
                                                shape = RoundedCornerShape(999.dp),
                                                color = if (p.autoPay) EmeraldSoft else SlateTint,
                                            ) {
                                                Text(
                                                    text = if (p.autoPay) "Auto-Pay ON" else "Manual",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (p.autoPay) EmeraldDark else MutedSlate,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = MoneyFormatter.formatMinor(
                                            if (isIncome) p.amountMinor else -p.amountMinor,
                                            p.currency,
                                            showPlus = isIncome,
                                            masked = masked,
                                        ),
                                        style = TabularStyle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isIncome) EmeraldDark else SlateInk,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedButton(
                                            onClick = { vm.payPlannedNow(p.id) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp),
                                        ) {
                                            Text("Record", fontSize = 11.sp, color = EmeraldPrimary)
                                        }
                                        OutlinedButton(
                                            onClick = { vm.skipPlannedPayment(p.id) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp),
                                        ) {
                                            Text("Skip", fontSize = 11.sp, color = MutedSlate)
                                        }
                                        IconButton(
                                            onClick = { vm.deletePlannedPayment(p.id) },
                                            modifier = Modifier.width(28.dp).height(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.DeleteOutline,
                                                contentDescription = "Delete planned bill",
                                                tint = RoseOutflow,
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

    if (showNewBudget) {
        var name by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var selectedCatId by remember { mutableStateOf<String?>(null) }
        var showBudgetCatPicker by remember { mutableStateOf(false) }
        val parsedLimit = MoneyFormatter.parseDecimalToMinor(amount, baseCurrency) ?: 0L
        val existingCatSpent = remember(transactions, selectedCatId, baseCurrency, monthStart, catMap) {
            transactions
                .filter {
                    it.deletedAtEpochMs == null &&
                        it.txType == "EXPENSE" &&
                        it.occurredAtEpochMs >= monthStart &&
                        (selectedCatId == null || com.ledgerflow.app.domain.CategoryTree.isDescendantOrSelf(it.categoryId, selectedCatId, catMap))
                }
                .sumOf { abs(FxConverter.convertMinor(it.amountMinor, it.currency, baseCurrency)) }
        }

        AlertDialog(
            onDismissRequest = { showNewBudget = false },
            title = { Text("Create Monthly Budget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Budget Name") },
                        placeholder = { Text(selectedCatId?.let { catMap[it]?.name } ?: "Monthly Budget") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                        label = { Text("Limit ($baseCurrency)") },
                        placeholder = { Text(if (baseCurrency == "JPY") "0" else "0.00") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    if (amount.isNotBlank() && parsedLimit <= 0L) {
                        Text("Enter a positive budget limit greater than 0.", fontSize = 11.sp, color = RoseOutflow)
                    }
                    com.ledgerflow.app.ui.components.HierarchicalCategorySelectorField(
                        label = "Budget Scope (includes nested subcategories)",
                        selectedCategoryId = selectedCatId,
                        allCategories = categories,
                        nullOptionLabel = "All Categories (Overall)",
                        onClick = { showBudgetCatPicker = true },
                    )
                    if (existingCatSpent > 0L) {
                        val scopeLabel = selectedCatId?.let { catMap[it]?.name } ?: "all expenses"
                        Text(
                            text = if (parsedLimit in 1L until existingCatSpent) {
                                "Already spent ${MoneyFormatter.formatMinor(existingCatSpent, baseCurrency)} on $scopeLabel this month (${MoneyFormatter.formatMinor(existingCatSpent - parsedLimit, baseCurrency)} over this limit)."
                            } else {
                                "Already spent ${MoneyFormatter.formatMinor(existingCatSpent, baseCurrency)} on $scopeLabel this month."
                            },
                            fontSize = 11.sp,
                            color = if (parsedLimit in 1L until existingCatSpent) AmberWarn else MutedSlate,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = parsedLimit > 0L,
                    onClick = {
                        vm.addBudget(
                            name = name.ifBlank { selectedCatId?.let { catMap[it]?.name } ?: "Monthly Budget" },
                            categoryId = selectedCatId,
                            limitMinor = parsedLimit,
                            currency = baseCurrency,
                        )
                        showNewBudget = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewBudget = false }) { Text("Cancel") } },
        )

        if (showBudgetCatPicker) {
            com.ledgerflow.app.ui.components.HierarchicalCategoryPickerSheet(
                title = "Select Budget Scope",
                categories = categories,
                kindFilter = "EXPENSE",
                selectedIds = listOfNotNull(selectedCatId).toSet(),
                multiSelect = false,
                nullOptionLabel = "All Categories (Overall)",
                nullSelected = selectedCatId == null,
                onSelectNull = { selectedCatId = null },
                onSelectSingle = { selectedCatId = it.id },
                onDismiss = { showBudgetCatPicker = false },
            )
        }
    }

    if (showNewPlanned) {
        var title by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var txType by remember { mutableStateOf("EXPENSE") }
        var cadence by remember { mutableStateOf("MONTHLY") }
        var startMode by remember { mutableStateOf("CYCLE") } // TODAY, PLUS_7D, CYCLE
        var autoPay by remember { mutableStateOf(false) }
        var showPlannedCatPicker by remember { mutableStateOf(false) }
        var selectedAccountId by remember(accounts) {
            mutableStateOf(
                accounts.firstOrNull { !AccountTypeCatalog.isLiability(it.accountType) }?.id
                    ?: accounts.firstOrNull()?.id
            )
        }
        val filteredCats = remember(categories, txType) {
            categories.filter { it.kind == txType || it.name.equals("General", ignoreCase = true) }.ifEmpty { categories }
        }
        var selectedCatId by remember(txType, filteredCats) {
            mutableStateOf(
                filteredCats.firstOrNull { it.name.equals("Housing & Bills", ignoreCase = true) && txType == "EXPENSE" }?.id
                    ?: filteredCats.firstOrNull { it.kind == txType }?.id
                    ?: filteredCats.firstOrNull()?.id
            )
        }
        val parsedAmount = MoneyFormatter.parseDecimalToMinor(amount, baseCurrency) ?: 0L

        AlertDialog(
            onDismissRequest = { showNewPlanned = false },
            title = { Text("New Scheduled Payment") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = txType == "EXPENSE", onClick = { txType = "EXPENSE" }, label = { Text("Bill / Expense", fontSize = 11.sp) })
                        FilterChip(selected = txType == "INCOME", onClick = { txType = "INCOME" }, label = { Text("Scheduled Income", fontSize = 11.sp) })
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(if (txType == "INCOME") "Income Name" else "Bill Name") },
                        placeholder = { Text(if (txType == "INCOME") "e.g. Monthly Salary" else "e.g. Rent, Wifi, Home EMI") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                        label = { Text("Amount ($baseCurrency)") },
                        placeholder = { Text(if (baseCurrency == "JPY") "0" else "0.00") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    if (amount.isNotBlank() && parsedAmount <= 0L) {
                        Text("Enter a positive amount greater than 0.", fontSize = 11.sp, color = RoseOutflow)
                    }
                    Text("Account", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        accounts.forEach { acc ->
                            FilterChip(
                                selected = selectedAccountId == acc.id,
                                onClick = { selectedAccountId = acc.id },
                                label = { Text(acc.name, fontSize = 11.sp) },
                            )
                        }
                    }
                    com.ledgerflow.app.ui.components.HierarchicalCategorySelectorField(
                        label = "Category",
                        selectedCategoryId = selectedCatId,
                        allCategories = categories,
                        nullOptionLabel = "Select category",
                        onClick = { showPlannedCatPicker = true },
                    )
                    Text("Repeat Cadence", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("WEEKLY", "MONTHLY", "YEARLY").forEach { c ->
                            FilterChip(selected = cadence == c, onClick = { cadence = c }, label = { Text(c, fontSize = 11.sp) })
                        }
                    }
                    Text("First Due Date", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val cycleLabel = when (cadence) {
                            "WEEKLY" -> "In 1 Week"
                            "YEARLY" -> "In 1 Year"
                            else -> "In 1 Month"
                        }
                        FilterChip(selected = startMode == "TODAY", onClick = { startMode = "TODAY" }, label = { Text("Today", fontSize = 11.sp) })
                        FilterChip(selected = startMode == "PLUS_7D", onClick = { startMode = "PLUS_7D" }, label = { Text("+7 Days", fontSize = 11.sp) })
                        FilterChip(selected = startMode == "CYCLE", onClick = { startMode = "CYCLE" }, label = { Text(cycleLabel, fontSize = 11.sp) })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Pay when due", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = SlateInk)
                            Text("Posts automatically when due date arrives", fontSize = 10.sp, color = MutedSlate)
                        }
                        Switch(
                            checked = autoPay,
                            onCheckedChange = { autoPay = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = title.isNotBlank() && parsedAmount > 0L,
                    onClick = {
                        val now = System.currentTimeMillis()
                        val firstDueMs = when (startMode) {
                            "TODAY" -> now
                            "PLUS_7D" -> now + 7L * 86_400_000L
                            else -> AnalyticsCalculator.advanceDueDate(now, cadence)
                        }
                        vm.addPlannedPayment(
                            title = title,
                            amountMinor = parsedAmount,
                            currency = baseCurrency,
                            accountId = selectedAccountId,
                            categoryId = selectedCatId,
                            cadence = cadence,
                            txType = txType,
                            nextDueAtEpochMs = firstDueMs,
                            autoPay = autoPay,
                        )
                        showNewPlanned = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showNewPlanned = false }) { Text("Cancel") } },
        )

        if (showPlannedCatPicker) {
            com.ledgerflow.app.ui.components.HierarchicalCategoryPickerSheet(
                title = "Select Category",
                categories = categories,
                kindFilter = txType,
                selectedIds = listOfNotNull(selectedCatId).toSet(),
                multiSelect = false,
                onSelectSingle = { selectedCatId = it.id },
                onDismiss = { showPlannedCatPicker = false },
            )
        }
    }

    if (showGoalEditor) {
        GoalEditorBottomSheet(
            existing = editingGoal,
            baseCurrency = editingGoal?.currency ?: baseCurrency,
            accounts = accounts,
            onDismiss = {
                showGoalEditor = false
                editingGoal = null
            },
            onSave = { name, targetMinor, savedMinor, targetDateMs, iconKey, colorHex, linkedAccId ->
                val cur = editingGoal
                if (cur == null) {
                    vm.addSavingsGoal(
                        name = name,
                        targetAmountMinor = targetMinor,
                        alreadySavedMinor = savedMinor,
                        currency = baseCurrency,
                        targetDateEpochMs = targetDateMs,
                        iconKey = iconKey,
                        colorHex = colorHex,
                        linkedAccountId = linkedAccId,
                    )
                } else {
                    vm.updateSavingsGoal(
                        goalId = cur.id,
                        name = name,
                        targetAmountMinor = targetMinor,
                        alreadySavedMinor = savedMinor,
                        targetDateEpochMs = targetDateMs,
                        iconKey = iconKey,
                        colorHex = colorHex,
                        linkedAccountId = linkedAccId,
                    )
                }
                showGoalEditor = false
                editingGoal = null
            },
            onDelete = { id ->
                vm.deleteSavingsGoal(id)
                showGoalEditor = false
                editingGoal = null
            },
        )
    }

    addFundsTarget?.let { target ->
        AddGoalFundsBottomSheet(
            goal = target,
            accounts = accounts,
            onDismiss = { addFundsTarget = null },
            onConfirm = { amountMinor, accountId, deduct ->
                vm.addGoalFunds(target.id, amountMinor, accountId, deduct)
                addFundsTarget = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GoalEditorBottomSheet(
    existing: SavingsGoalEntity?,
    baseCurrency: String,
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        targetMinor: Long,
        savedMinor: Long,
        targetDateMs: Long?,
        iconKey: String,
        colorHex: String,
        linkedAccountId: String?,
    ) -> Unit,
    onDelete: (String) -> Unit,
) {
    val isEdit = existing != null
    fun formatInitial(minor: Long): String {
        if (minor <= 0L) return ""
        val div = if (baseCurrency.equals("JPY", ignoreCase = true)) 1.0 else 100.0
        return if (baseCurrency.equals("JPY", ignoreCase = true)) minor.toString()
        else String.format(Locale.US, "%.2f", minor / div)
    }

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var targetText by remember(existing) { mutableStateOf(formatInitial(existing?.targetAmountMinor ?: 0L)) }
    var savedText by remember(existing) { mutableStateOf(formatInitial(existing?.savedAmountMinor ?: 0L)) }
    var selectedIcon by remember(existing) { mutableStateOf(existing?.iconKey ?: "savings") }
    var selectedColor by remember(existing) { mutableStateOf(existing?.colorHex ?: "#059669") }
    var linkedAccountId by remember(existing, accounts) {
        mutableStateOf(existing?.linkedAccountId?.takeIf { id -> accounts.any { it.id == id } })
    }
    var deadlineMode by remember(existing) {
        mutableStateOf(if (existing?.targetDateEpochMs != null) "KEEP" else "NONE")
    }
    val parsedTarget = MoneyFormatter.parseDecimalToMinor(targetText, baseCurrency) ?: 0L
    val parsedSaved = MoneyFormatter.parseDecimalToMinor(savedText, baseCurrency) ?: 0L
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceCard,
        contentColor = SlateInk,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (isEdit) "Edit Savings Goal" else "New Savings Goal", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SlateInk)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Goal Name") },
                placeholder = { Text("e.g. Emergency Fund, New Laptop") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = targetText,
                onValueChange = { targetText = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                label = { Text("Target Amount ($baseCurrency)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = savedText,
                onValueChange = { savedText = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                label = { Text("Already Saved ($baseCurrency)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Text("Target Date (Optional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = deadlineMode == "NONE", onClick = { deadlineMode = "NONE" }, label = { Text("No deadline", fontSize = 11.sp) })
                if (existing?.targetDateEpochMs != null) {
                    FilterChip(
                        selected = deadlineMode == "KEEP",
                        onClick = { deadlineMode = "KEEP" },
                        label = { Text(dateFmt.format(Date(existing.targetDateEpochMs)), fontSize = 11.sp) },
                    )
                }
                FilterChip(selected = deadlineMode == "M3", onClick = { deadlineMode = "M3" }, label = { Text("+3 Months", fontSize = 11.sp) })
                FilterChip(selected = deadlineMode == "M6", onClick = { deadlineMode = "M6" }, label = { Text("+6 Months", fontSize = 11.sp) })
                FilterChip(selected = deadlineMode == "Y1", onClick = { deadlineMode = "Y1" }, label = { Text("+1 Year", fontSize = 11.sp) })
            }

            Text("Icon & Accent", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                goalIconChoices.forEach { (key, label) ->
                    val active = selectedIcon == key
                    Surface(
                        onClick = { selectedIcon = key },
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) EmeraldSoft else SlateTint,
                        border = if (active) BorderStroke(1.dp, EmeraldPrimary) else null,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = categoryIcon(key),
                                contentDescription = null,
                                tint = if (active) EmeraldDark else SlateInk,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(label, fontSize = 11.sp, color = if (active) EmeraldDark else SlateInk)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                goalColorChoices.forEach { hex ->
                    val selected = selectedColor.equals(hex, ignoreCase = true)
                    val col = runCatching { Color(hex.toColorInt()) }.getOrDefault(EmeraldPrimary)
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(col)
                            .border(if (selected) 2.5.dp else 0.dp, if (selected) SlateInk else Color.Transparent, CircleShape)
                            .clickable { selectedColor = hex },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Text("Linked Account (Optional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = linkedAccountId == null, onClick = { linkedAccountId = null }, label = { Text("None", fontSize = 11.sp) })
                accounts.forEach { acc ->
                    FilterChip(selected = linkedAccountId == acc.id, onClick = { linkedAccountId = acc.id }, label = { Text(acc.name, fontSize = 11.sp) })
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (existing != null) {
                    TextButton(onClick = { onDelete(existing.id) }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = RoseOutflow, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete", color = RoseOutflow)
                    }
                } else {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }

                Button(
                    enabled = name.isNotBlank() && parsedTarget > 0L,
                    onClick = {
                        val cal = Calendar.getInstance()
                        val targetMs = when (deadlineMode) {
                            "M3" -> cal.apply { add(Calendar.MONTH, 3) }.timeInMillis
                            "M6" -> cal.apply { add(Calendar.MONTH, 6) }.timeInMillis
                            "Y1" -> cal.apply { add(Calendar.YEAR, 1) }.timeInMillis
                            "KEEP" -> existing?.targetDateEpochMs
                            else -> null
                        }
                        onSave(name, parsedTarget, parsedSaved, targetMs, selectedIcon, selectedColor, linkedAccountId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (isEdit) "Save Goal" else "Create Goal", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGoalFundsBottomSheet(
    goal: SavingsGoalEntity,
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onConfirm: (amountMinor: Long, accountId: String?, deductFromAccount: Boolean) -> Unit,
) {
    val remaining = (goal.targetAmountMinor - goal.savedAmountMinor).coerceAtLeast(0L)
    var amountText by remember(goal) { mutableStateOf("") }
    var selectedAccountId by remember(goal, accounts) {
        mutableStateOf(goal.linkedAccountId?.takeIf { id -> accounts.any { it.id == id } })
    }
    var deductFromAccount by remember(selectedAccountId) { mutableStateOf(selectedAccountId != null) }
    val parsedAmount = MoneyFormatter.parseDecimalToMinor(amountText, goal.currency) ?: 0L

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceCard,
        contentColor = SlateInk,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add Funds • ${goal.name}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SlateInk)
            Text(
                text = if (remaining > 0L) {
                    "${MoneyFormatter.formatMinor(remaining, goal.currency)} left to reach target"
                } else {
                    "Goal target already reached!"
                },
                style = TabularStyle,
                fontSize = 13.sp,
                color = MutedSlate,
            )

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = MoneyFormatter.sanitizeDecimalInput(it, goal.currency, allowNegative = false) },
                label = { Text("Contribution (${goal.currency})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            if (remaining > 0L) {
                val div = if (goal.currency.equals("JPY", ignoreCase = true)) 1.0 else 100.0
                OutlinedButton(
                    onClick = {
                        amountText = if (goal.currency.equals("JPY", ignoreCase = true)) remaining.toString()
                        else String.format(Locale.US, "%.2f", remaining / div)
                    },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Fill Remaining (${MoneyFormatter.formatMinor(remaining, goal.currency)})", fontSize = 12.sp)
                }
            }

            Text("Source Account (Optional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedAccountId == null,
                    onClick = {
                        selectedAccountId = null
                        deductFromAccount = false
                    },
                    label = { Text("None (Progress only)", fontSize = 11.sp) },
                )
                accounts.forEach { acc ->
                    FilterChip(
                        selected = selectedAccountId == acc.id,
                        onClick = {
                            selectedAccountId = acc.id
                            deductFromAccount = true
                        },
                        label = { Text(acc.name, fontSize = 11.sp) },
                    )
                }
            }

            if (selectedAccountId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Deduct from selected account", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SlateInk)
                        Text("Records an Expense entry in your ledger", fontSize = 11.sp, color = MutedSlate)
                    }
                    Switch(
                        checked = deductFromAccount,
                        onCheckedChange = { deductFromAccount = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = parsedAmount > 0L,
                    onClick = { onConfirm(parsedAmount, selectedAccountId, deductFromAccount) },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Add Funds", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
