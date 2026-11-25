package com.ledgerflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.EmptyPlaceholder
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.AmberSoft
import com.ledgerflow.app.ui.theme.AmberWarn
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.SkySoft
import com.ledgerflow.app.ui.theme.SkySync
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.TabularStyle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun BudgetsScreen(vm: LedgerViewModel) {
    val budgets by vm.budgets.collectAsState()
    val planned by vm.plannedPayments.collectAsState()
    val transactions by vm.allTransactions.collectAsState()
    val categories by vm.categories.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val vault by vm.vaultState.collectAsState()

    val baseCurrency = vault.baseCurrency
    val masked = vault.hideBalances
    val dayKey = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val monthStart = remember(dayKey) { vm.currentMonthStartEpochMs() }
    val monthFraction = remember(dayKey) { vm.currentMonthFraction() }
    val daysRemaining = remember(dayKey) { vm.daysRemainingInMonth() }

    val progressList = remember(budgets, transactions, baseCurrency, monthStart, monthFraction) {
        AnalyticsCalculator.computeBudgets(budgets, transactions, baseCurrency, monthStart, monthFraction)
    }

    val totalLimit = progressList.sumOf { FxConverter.convertMinor(it.budget.limitMinor, it.budget.currency, baseCurrency) }
    val totalSpent = progressList.sumOf { it.spentMinor }
    val totalPlanned = planned.filter { it.deletedAtEpochMs == null && it.txType == "EXPENSE" }
        .sumOf { FxConverter.convertMinor(it.amountMinor, it.currency, baseCurrency) }
    val safeRemaining = (totalLimit - totalSpent).coerceAtLeast(0L)
    val dailySafe = safeRemaining / daysRemaining.coerceAtLeast(1)
    val allBudgetsOnTrack = progressList.isNotEmpty() && progressList.all { it.onTrack }

    val catMap = remember(categories) { categories.associateBy { it.id } }
    val dateFmt = remember { SimpleDateFormat("MMM d", Locale.US) }

    var selectedTab by remember { mutableIntStateOf(0) } // 0=Budgets, 1=Analytics, 2=Planned
    var showNewBudget by remember { mutableStateOf(false) }
    var showNewPlanned by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Planning", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                Surface(
                    onClick = { if (selectedTab == 2) showNewPlanned = true else showNewBudget = true },
                    shape = RoundedCornerShape(999.dp),
                    color = EmeraldPrimary,
                ) {
                    Text(
                        text = if (selectedTab == 2) "+ New Bill" else "+ New Budget",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        // Segmented switcher
        item {
            Surface(shape = RoundedCornerShape(14.dp), color = SlateTint, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(4.dp)) {
                    listOf("Budgets", "Analytics", "Planned").forEachIndexed { idx, label ->
                        val active = selectedTab == idx
                        Surface(
                            onClick = { selectedTab = idx },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) Color.White else Color.Transparent,
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
                // Safe-to-Spend Hero Card
                item {
                    CleanCard(padding = 18.dp) {
                        Text("Monthly Safe-to-Spend", fontSize = 12.sp, color = MutedSlate, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        StatusPill(
                            text = when {
                                progressList.isEmpty() -> "No active monthly budgets"
                                allBudgetsOnTrack -> "Within monthly budget pace"
                                else -> "Ahead of monthly budget pace"
                            },
                            containerColor = if (allBudgetsOnTrack || progressList.isEmpty()) EmeraldSoft else AmberSoft,
                            contentColor = if (allBudgetsOnTrack || progressList.isEmpty()) EmeraldDark else AmberWarn,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${MoneyFormatter.formatMinor(safeRemaining, baseCurrency, masked = masked)} left",
                            style = TabularStyle,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = SlateInk,
                        )
                        Text(
                            text = "${MoneyFormatter.formatMinor(dailySafe, baseCurrency, masked = masked)} / day for remaining $daysRemaining days",
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

                if (progressList.isEmpty()) {
                    item { EmptyPlaceholder("No budgets created", "Tap + New Budget above to add a limit.") }
                } else {
                    items(progressList, key = { it.budget.id }) { bp ->
                        val cat = catMap[bp.budget.categoryId]
                        val limitBase = FxConverter.convertMinor(bp.budget.limitMinor, bp.budget.currency, baseCurrency)
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
                                        text = "${MoneyFormatter.formatMinor(bp.remainingMinor, baseCurrency, masked = masked)} left",
                                        style = TabularStyle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (bp.onTrack) EmeraldDark else AmberWarn,
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
                                color = if (bp.onTrack) EmeraldPrimary else AmberWarn,
                                trackColor = SlateTint,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusPill(
                                    text = if (bp.onTrack) "On track" else "Ahead of pace",
                                    containerColor = if (bp.onTrack) EmeraldSoft else AmberSoft,
                                    contentColor = if (bp.onTrack) EmeraldDark else AmberWarn,
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
                // Analytics Category Breakdown
                item {
                    val expensesByCat = remember(transactions, categories, baseCurrency, monthStart) {
                        transactions
                            .filter { it.txType == "EXPENSE" && it.occurredAtEpochMs >= monthStart }
                            .groupBy { catMap[it.categoryId]?.name ?: "Other" }
                            .mapValues { (_, list) -> list.sumOf { abs(FxConverter.convertMinor(it.amountMinor, it.currency, baseCurrency)) } }
                            .entries
                            .sortedByDescending { it.value }
                    }
                    val maxCatSpend = (expensesByCat.firstOrNull()?.value ?: 1L).coerceAtLeast(1L).toFloat()

                    CleanCard(padding = 16.dp) {
                        Text("Spending by Category (This Month)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Spacer(Modifier.height(12.dp))
                        if (expensesByCat.isEmpty()) {
                            Text("No expense records this month.", color = MutedSlate, fontSize = 13.sp)
                        } else {
                            expensesByCat.forEach { (catName, amount) ->
                                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(catName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                                        Text(MoneyFormatter.formatMinor(amount, baseCurrency, masked = masked), style = TabularStyle, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { (amount / maxCatSpend).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
                                        color = EmeraldPrimary,
                                        trackColor = SlateTint,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                // Planned Payments Timeline
                if (planned.isEmpty()) {
                    item { EmptyPlaceholder("No planned bills", "Tap + New Bill to schedule rent, utilities, or subscriptions.") }
                } else {
                    items(planned, key = { it.id }) { p ->
                        val cat = catMap[p.categoryId]
                        CleanCard(padding = 14.dp) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CategoryIconBadge(cat?.iconKey ?: "home", cat?.colorHex ?: "#0284C7", size = 38.dp)
                                    Column {
                                        Text("${p.title} • ${dateFmt.format(Date(p.nextDueAtEpochMs))}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                                        Spacer(Modifier.height(2.dp))
                                        StatusPill(if (p.autoPay) "Auto-Pay" else p.cadence, SkySoft, SkySync)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = MoneyFormatter.formatMinor(-p.amountMinor, p.currency, masked = masked),
                                        style = TabularStyle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SlateInk,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { vm.payPlannedNow(p.id) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp),
                                        ) {
                                            Text("Record", fontSize = 11.sp, color = EmeraldPrimary)
                                        }
                                        OutlinedButton(
                                            onClick = { vm.deletePlannedPayment(p.id) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp),
                                        ) {
                                            Text("Del", fontSize = 11.sp, color = AmberWarn)
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
        var selectedCatId by remember { mutableStateOf(categories.firstOrNull { it.kind == "EXPENSE" }?.id ?: categories.firstOrNull()?.id) }
        val parsedLimit = MoneyFormatter.parseDecimalToMinor(amount) ?: 0L

        AlertDialog(
            onDismissRequest = { showNewBudget = false },
            title = { Text("Create Monthly Budget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Budget Name") }, singleLine = true)
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Limit ($baseCurrency)") },
                        placeholder = { Text("0.00") },
                        singleLine = true,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.filter { it.kind == "EXPENSE" }.forEach { c ->
                            FilterChip(selected = selectedCatId == c.id, onClick = { selectedCatId = c.id }, label = { Text(c.name, fontSize = 11.sp) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = parsedLimit > 0L,
                    onClick = {
                        vm.addBudget(name.ifBlank { catMap[selectedCatId]?.name ?: "Budget" }, selectedCatId, parsedLimit, baseCurrency)
                        showNewBudget = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewBudget = false }) { Text("Cancel") } },
        )
    }

    if (showNewPlanned) {
        var title by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var cadence by remember { mutableStateOf("MONTHLY") }
        val parsedAmount = MoneyFormatter.parseDecimalToMinor(amount) ?: 0L

        AlertDialog(
            onDismissRequest = { showNewPlanned = false },
            title = { Text("New Planned Bill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Bill Name") }, singleLine = true)
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Amount ($baseCurrency)") },
                        placeholder = { Text("0.00") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("WEEKLY", "MONTHLY", "YEARLY").forEach { c ->
                            FilterChip(selected = cadence == c, onClick = { cadence = c }, label = { Text(c, fontSize = 11.sp) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = title.isNotBlank() && parsedAmount > 0L,
                    onClick = {
                        vm.addPlannedPayment(title, parsedAmount, baseCurrency, accounts.firstOrNull()?.id, categories.firstOrNull()?.id, cadence)
                        showNewPlanned = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Save Bill") }
            },
            dismissButton = { TextButton(onClick = { showNewPlanned = false }) { Text("Cancel") } },
        )
    }
}
