package com.ledgerflow.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.ledgerflow.app.domain.CalculatorEngine
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.TabularStyle
import java.util.Locale

@Composable
fun AddTransactionScreen(
    vm: LedgerViewModel,
    initialType: String,
    onDismiss: () -> Unit,
) {
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val rules by vm.magicRules.collectAsState()
    val vault by vm.vaultState.collectAsState()

    var txType by remember { mutableStateOf(initialType) }
    var expr by remember { mutableStateOf("48.50") }
    var currency by remember { mutableStateOf(vault.baseCurrency) }
    var selectedAccountId by remember(accounts) { mutableStateOf(accounts.firstOrNull()?.id ?: "") }
    var transferTargetId by remember(accounts) { mutableStateOf(accounts.getOrNull(1)?.id ?: accounts.firstOrNull()?.id ?: "") }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var merchant by remember { mutableStateOf("Trader Joe's") }
    var note by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("weekly") }
    var recurring by remember { mutableStateOf(false) }

    val evaluatedMinor = remember(expr) { CalculatorEngine.evaluateToMinor(expr) ?: 0L }
    val autoMatch = remember(merchant, note, rules) { MagicRuleEngine.apply(merchant, note, rules) }
    val effectiveCatId = selectedCategoryId ?: autoMatch.categoryId ?: categories.firstOrNull()?.id

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Top modal bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SlateTint),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = SlateInk)
            }

            Surface(shape = RoundedCornerShape(999.dp), color = SlateTint) {
                Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("EXPENSE" to "Expense", "INCOME" to "Income", "TRANSFER" to "Transfer").forEach { (key, title) ->
                        val active = txType == key
                        Surface(
                            onClick = { txType = key },
                            shape = RoundedCornerShape(999.dp),
                            color = if (active) SlateInk else Color.Transparent,
                        ) {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) Color.White else MutedSlate,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            StatusPill("Local E2EE", EmeraldSoft, EmeraldDark)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // Hero Amount Card
            CleanCard(padding = 16.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("AMOUNT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (currency != vault.baseCurrency) {
                            val fx = FxConverter.convertMinor(evaluatedMinor, currency, vault.baseCurrency)
                            Text("≈ ${MoneyFormatter.formatMinor(fx, vault.baseCurrency)}", style = TabularStyle, fontSize = 12.sp, color = MutedSlate)
                        }
                        Surface(
                            onClick = {
                                val list = listOf("USD", "EUR", "GBP", "INR")
                                currency = list[(list.indexOf(currency) + 1) % list.size]
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = SlateTint,
                        ) {
                            Text("$currency ▾", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SlateInk, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                val signPrefix = when (txType) {
                    "EXPENSE" -> "-"
                    "INCOME" -> "+"
                    else -> ""
                }
                Text(
                    text = "$signPrefix${MoneyFormatter.currencySymbol(currency)}$expr",
                    style = TabularStyle,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (txType == "EXPENSE") RoseOutflow else if (txType == "INCOME") EmeraldDark else SlateInk,
                )
                Spacer(Modifier.height(10.dp))
                val sym = MoneyFormatter.currencySymbol(currency)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("10", "25", "50", "100").forEach { inc ->
                        Surface(
                            onClick = {
                                val next = (evaluatedMinor / 100.0) + inc.toDouble()
                                expr = if (next % 1.0 == 0.0) next.toInt().toString() else String.format(Locale.US, "%.2f", next)
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = SlateTint,
                        ) {
                            Text("+$sym$inc", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = SlateInk, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    Surface(
                        onClick = { expr = "0" },
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xFFFFF1F2),
                    ) {
                        Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RoseOutflow, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }

            // Details Card
            CleanCard(padding = 14.dp) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant / Payee") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                Text("Account", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.forEach { acc ->
                        FilterChip(
                            selected = selectedAccountId == acc.id,
                            onClick = { selectedAccountId = acc.id },
                            label = { Text("${acc.name} (${MoneyFormatter.formatMinor(acc.balanceMinor, acc.currency)})", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EmeraldSoft, selectedLabelColor = EmeraldDark),
                        )
                    }
                }

                if (txType == "TRANSFER") {
                    Spacer(Modifier.height(8.dp))
                    Text("Transfer To", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { acc ->
                            FilterChip(
                                selected = transferTargetId == acc.id,
                                onClick = { transferTargetId = acc.id },
                                label = { Text(acc.name, fontSize = 12.sp) },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Category", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                        if (selectedCategoryId == null && autoMatch.matched) {
                            StatusPill("Auto-Rule Suggested", EmeraldSoft, EmeraldDark)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { cat ->
                            FilterChip(
                                selected = effectiveCatId == cat.id,
                                onClick = { selectedCategoryId = cat.id },
                                label = { Text(cat.name, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EmeraldSoft, selectedLabelColor = EmeraldDark),
                            )
                        }
                    }
                }

                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f),
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("#Label") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (txType != "TRANSFER") {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recurring monthly payment", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SlateInk)
                        Switch(
                            checked = recurring,
                            onCheckedChange = { recurring = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                        )
                    }
                }
            }
        }

        // Docked Calculator Keypad & Primary CTA
        Spacer(Modifier.height(8.dp))
        CalculatorPad(
            onKey = { key ->
                expr = when (key) {
                    "DEL" -> if (expr.length <= 1) "0" else expr.dropLast(1)
                    else -> if (expr == "0" && key.first().isDigit()) key else expr + key
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                val ok = vm.addTransaction(
                    txType = txType,
                    amountExpr = expr,
                    currency = currency,
                    accountId = selectedAccountId,
                    transferAccountId = transferTargetId,
                    manualCategoryId = selectedCategoryId,
                    merchant = merchant,
                    note = note,
                    labelsCsv = label,
                    recurringMonthly = recurring,
                )
                if (ok) onDismiss()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
        ) {
            val actionLabel = when (txType) {
                "INCOME" -> "Save Income"
                "TRANSFER" -> "Save Transfer"
                else -> "Save Expense"
            }
            Text(
                text = "$actionLabel (${MoneyFormatter.formatMinor(evaluatedMinor, currency)})",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun CalculatorPad(onKey: (String) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3", "÷"),
        listOf("4", "5", "6", "×"),
        listOf("7", "8", "9", "-"),
        listOf(".", "0", "DEL", "+"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val isOp = key in listOf("÷", "×", "-", "+")
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clickable { onKey(key) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isOp) Color(0xFFE0E7FF) else Color.White,
                        border = BorderStroke(1.dp, BorderSubtle),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (key == "DEL") {
                                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = "Backspace", tint = SlateInk, modifier = Modifier.size(18.dp))
                            } else {
                                Text(key, style = TabularStyle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                            }
                        }
                    }
                }
            }
        }
    }
}
