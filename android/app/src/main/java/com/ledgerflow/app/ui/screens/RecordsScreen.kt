package com.ledgerflow.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.EmptyPlaceholder
import com.ledgerflow.app.ui.components.SourceTagBadge
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.SkySync
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.TabularStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun RecordsScreen(
    vm: LedgerViewModel,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val transactions by vm.filteredTransactions.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val magicRules by vm.magicRules.collectAsState()
    val vault by vm.vaultState.collectAsState()
    val query by vm.searchQuery.collectAsState()
    val filter by vm.recordsFilter.collectAsState()

    val baseCurrency = vault.baseCurrency
    val masked = vault.hideBalances
    val accMap = remember(accounts) { accounts.associateBy { it.id } }
    val catMap = remember(categories) { categories.associateBy { it.id } }

    var pendingDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var showRulesDialog by remember { mutableStateOf(false) }

    val inflow = remember(transactions, baseCurrency) {
        transactions.filter { it.txType == "INCOME" }.sumOf { abs(FxConverter.convertMinor(it.amountMinor, it.currency, baseCurrency)) }
    }
    val outflow = remember(transactions, baseCurrency) {
        transactions.filter { it.txType == "EXPENSE" }.sumOf { abs(FxConverter.convertMinor(it.amountMinor, it.currency, baseCurrency)) }
    }

    val dayFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.US) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.US) }
    val grouped = remember(transactions) {
        transactions.groupBy { dayFormat.format(Date(it.occurredAtEpochMs)) }
    }

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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Records", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    StatusPill("${transactions.size}", EmeraldSoft, EmeraldDark)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showRulesDialog = true }) {
                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = "Auto-Rules", tint = SlateInk)
                    }
                    IconButton(onClick = {
                        vm.exportAndShare(asCsv = true) { intent ->
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                                .onFailure { vm.showMessage("No app found to share exported CSV") }
                        }
                    }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "Export CSV", tint = SlateInk)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = SlateInk)
                    }
                }
            }
        }

        // Instant SQLite FTS4 Search Bar
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setSearchQuery(it) },
                placeholder = { Text("Search notes, merchants, #labels...", fontSize = 14.sp, color = MutedSlate) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MutedSlate) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setSearchQuery("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Filter Chips
        item {
            val filters = listOf(
                "ALL" to "All",
                "MANUAL" to "Manual",
                "GPAY_AUTO" to "GPay Auto",
                "PLANNED" to "Planned",
                "IMPORT" to "Imported",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filters.forEach { (key, label) ->
                    FilterChip(
                        selected = filter == key,
                        onClick = { vm.setRecordsFilter(key) },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SlateInk,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }
        }

        // KPI Cashflow Strip
        item {
            CleanCard(padding = 12.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    KpiCol("INFLOW", MoneyFormatter.formatMinor(inflow, baseCurrency, showPlus = true, masked = masked), EmeraldDark)
                    KpiCol("OUTFLOW", MoneyFormatter.formatMinor(-outflow, baseCurrency, masked = masked), RoseOutflow)
                    KpiCol("NET FLOW", MoneyFormatter.formatMinor(inflow - outflow, baseCurrency, showPlus = true, masked = masked), SlateInk)
                }
            }
        }

        if (grouped.isEmpty()) {
            item {
                EmptyPlaceholder("No matching records", "Try clearing filters or add a new transaction.")
            }
        } else {
            grouped.forEach { (dayLabel, itemsInDay) ->
                item(key = "hdr_$dayLabel") {
                    val dayNet = itemsInDay.sumOf {
                        val signed = when (it.txType) {
                            "INCOME" -> abs(it.amountMinor)
                            "EXPENSE" -> -abs(it.amountMinor)
                            else -> 0L
                        }
                        FxConverter.convertMinor(signed, it.currency, baseCurrency)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(dayLabel.uppercase(Locale.US), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
                        Text(
                            text = "Net ${MoneyFormatter.formatMinor(dayNet, baseCurrency, showPlus = true, masked = masked)}",
                            style = TabularStyle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MutedSlate,
                        )
                    }
                }
                item(key = "grp_$dayLabel") {
                    CleanCard(padding = 4.dp) {
                        itemsInDay.forEachIndexed { idx, tx ->
                            if (idx > 0) HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(horizontal = 12.dp))
                            val cat = catMap[tx.categoryId]
                            val acc = accMap[tx.accountId]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pendingDelete = tx }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CategoryIconBadge(cat?.iconKey ?: "category", cat?.colorHex ?: "#059669", size = 38.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(tx.merchant, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (tx.autoRuleApplied) {
                                            StatusPill("Auto-Rule", EmeraldSoft, EmeraldDark)
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "${cat?.name ?: tx.txType} • ${acc?.name ?: "Account"}",
                                            fontSize = 12.sp,
                                            color = MutedSlate,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        SourceTagBadge(tx.sourceTag)
                                        if (tx.labelsCsv.isNotBlank()) {
                                            StatusPill("#${tx.labelsCsv.split(',').first()}", SlateTint, MutedSlate)
                                        }
                                        Text(timeFormat.format(Date(tx.occurredAtEpochMs)), fontSize = 11.sp, color = MutedSlate)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = MoneyFormatter.formatMinor(tx.amountMinor, tx.currency, showPlus = tx.txType == "INCOME", masked = masked),
                                        style = TabularStyle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (tx.txType == "INCOME") EmeraldDark else if (tx.txType == "EXPENSE") RoseOutflow else SlateInk,
                                    )
                                    if (tx.currency != baseCurrency) {
                                        val fx = FxConverter.convertMinor(tx.amountMinor, tx.currency, baseCurrency)
                                        Text(
                                            text = "≈ ${MoneyFormatter.formatMinor(fx, baseCurrency, masked = masked)}",
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
        }
    }

    pendingDelete?.let { tx ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = RoseOutflow) },
            title = { Text("Delete \"${tx.merchant}\"?") },
            text = { Text("This soft-deletes the record offline and immediately adjusts the linked account balance.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTransaction(tx.id)
                    pendingDelete = null
                }) {
                    Text("Delete", color = RoseOutflow, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (showRulesDialog) {
        var newPattern by remember { mutableStateOf("") }
        var newLabel by remember { mutableStateOf("") }
        var selectedCatId by remember { mutableStateOf(categories.firstOrNull()?.id) }

        AlertDialog(
            onDismissRequest = { showRulesDialog = false },
            title = { Text("Auto-Category Rules") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automatically assign categories when merchant or note text matches keywords.", fontSize = 12.sp, color = MutedSlate)
                    if (magicRules.isEmpty()) {
                        Text("No rules saved yet.", fontSize = 12.sp, color = MutedSlate)
                    } else {
                        magicRules.take(5).forEach { r ->
                            Surface(shape = RoundedCornerShape(10.dp), color = SlateTint, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "\"${r.matchPattern}\" → ${catMap[r.targetCategoryId]?.name ?: "Rule"}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = SlateInk,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { vm.deleteMagicRule(r.id) }) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "Delete rule",
                                            tint = RoseOutflow,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newPattern,
                        onValueChange = { newPattern = it },
                        label = { Text("Keyword match (pipe-separated)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text("Optional tag") },
                        singleLine = true,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.take(6).forEach { c ->
                            FilterChip(
                                selected = selectedCatId == c.id,
                                onClick = { selectedCatId = c.id },
                                label = { Text(c.name, fontSize = 11.sp) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newPattern.isNotBlank(),
                    onClick = {
                        vm.addMagicRule(newPattern, selectedCatId, newLabel)
                        showRulesDialog = false
                    },
                ) { Text("Save Rule", color = EmeraldPrimary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRulesDialog = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun KpiCol(title: String, amount: String, color: Color) {
    Column {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
        Spacer(Modifier.height(2.dp))
        Text(amount, style = TabularStyle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}
