package com.ledgerflow.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.EmptyPlaceholder
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.theme.AlabasterBg
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
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun InvestScreen(vm: LedgerViewModel) {
    val holdings by vm.holdings.collectAsState()
    val vault by vm.vaultState.collectAsState()
    val baseCurrency = vault.baseCurrency
    val masked = vault.hideBalances

    val summary = remember(holdings, baseCurrency) {
        AnalyticsCalculator.computePortfolio(holdings, baseCurrency)
    }

    var showAddHolding by remember { mutableStateOf(false) }
    var selectedHolding by remember { mutableStateOf<HoldingEntity?>(null) }

    val total = summary.totalValueMinor
    val stocksPct = if (total > 0L) ((summary.stocksValueMinor.toDouble() / total) * 100.0).toInt() else 0
    val cryptoPct = if (total > 0L) ((summary.cryptoValueMinor.toDouble() / total) * 100.0).toInt() else 0
    val forexPct = if (total > 0L) (100 - stocksPct - cryptoPct).coerceAtLeast(0) else 0
    val activeClasses = listOf(summary.stocksValueMinor, summary.cryptoValueMinor, summary.forexValueMinor).count { it > 0L }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Investments", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    Text("• Tracked in Local Room • Tap position to update price", fontSize = 11.sp, color = EmeraldDark, fontWeight = FontWeight.Medium)
                }
                Surface(
                    onClick = { showAddHolding = true },
                    shape = RoundedCornerShape(999.dp),
                    color = EmeraldPrimary,
                ) {
                    Text(
                        text = "+ Add Holding",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        // Hero Portfolio Card (strictly real numbers from Room holdings)
        item {
            CleanCard(padding = 18.dp) {
                Text("Total Portfolio Value", fontSize = 12.sp, color = MutedSlate, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = MoneyFormatter.formatMinor(summary.totalValueMinor, baseCurrency, masked = masked),
                    style = TabularStyle,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateInk,
                )
                Spacer(Modifier.height(6.dp))
                val gainStr = MoneyFormatter.formatMinor(summary.gainMinor, baseCurrency, showPlus = true, masked = masked)
                val pctStr = String.format(Locale.US, "%s%.2f%%", if (summary.gainPercent >= 0) "+" else "", summary.gainPercent)
                StatusPill(
                    text = if (holdings.isEmpty()) "No holdings yet" else "$gainStr ($pctStr) vs Cost Basis",
                    containerColor = when {
                        holdings.isEmpty() -> SlateTint
                        summary.gainMinor >= 0 -> EmeraldSoft
                        else -> RoseSoft
                    },
                    contentColor = when {
                        holdings.isEmpty() -> MutedSlate
                        summary.gainMinor >= 0 -> EmeraldDark
                        else -> RoseOutflow
                    },
                )
                if (holdings.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Cost Basis: ${MoneyFormatter.formatMinor(summary.totalCostMinor, baseCurrency, masked = masked)}",
                        style = TabularStyle,
                        fontSize = 12.sp,
                        color = MutedSlate,
                    )
                }
            }
        }

        // Asset Allocation Card
        item {
            CleanCard(padding = 16.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Allocation", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    Text("$activeClasses Active Classes", fontSize = 12.sp, color = MutedSlate)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(SlateTint),
                ) {
                    if (summary.stocksValueMinor > 0) Box(Modifier.weight(summary.stocksValueMinor.toFloat()).fillMaxHeight().background(EmeraldPrimary))
                    if (summary.cryptoValueMinor > 0) Box(Modifier.weight(summary.cryptoValueMinor.toFloat()).fillMaxHeight().background(SkySync))
                    if (summary.forexValueMinor > 0) Box(Modifier.weight(summary.forexValueMinor.toFloat()).fillMaxHeight().background(MutedSlate))
                }
                Spacer(Modifier.height(12.dp))
                AllocationRow("Stocks", "$stocksPct% (${MoneyFormatter.formatMinor(summary.stocksValueMinor, baseCurrency, masked = masked)})", EmeraldPrimary)
                AllocationRow("Crypto", "$cryptoPct% (${MoneyFormatter.formatMinor(summary.cryptoValueMinor, baseCurrency, masked = masked)})", SkySync)
                AllocationRow("Forex Cash", "$forexPct% (${MoneyFormatter.formatMinor(summary.forexValueMinor, baseCurrency, masked = masked)})", MutedSlate)
            }
        }

        // Holdings List
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Holdings", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                Text("${holdings.size} Positions", fontSize = 12.sp, color = MutedSlate)
            }
        }

        if (holdings.isEmpty()) {
            item { EmptyPlaceholder("No holdings tracked", "Tap + Add Holding to track stocks, crypto, or FX reserves.") }
        } else {
            items(holdings, key = { it.id }) { h ->
                val valLocal = (h.quantity * h.currentPriceMinor.toDouble()).roundToLong()
                val costLocal = (h.quantity * h.avgCostMinor.toDouble()).roundToLong()
                val valBase = FxConverter.convertMinor(valLocal, h.currency, baseCurrency)
                val costBase = FxConverter.convertMinor(costLocal, h.currency, baseCurrency)
                val pnlBase = valBase - costBase
                val pnlPct = if (costBase > 0L) (pnlBase.toDouble() / costBase.toDouble()) * 100.0 else 0.0

                val badgeBg = when (h.assetClass) {
                    "CRYPTO" -> SkySoft
                    "FOREX" -> SlateTint
                    else -> EmeraldSoft
                }
                val badgeFg = when (h.assetClass) {
                    "CRYPTO" -> SkySync
                    "FOREX" -> MutedSlate
                    else -> EmeraldDark
                }
                CleanCard(
                    padding = 14.dp,
                    onClick = { selectedHolding = h },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(badgeBg),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(h.symbol.take(4), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = badgeFg)
                            }
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = h.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SlateInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    StatusPill(h.exchange.ifBlank { h.assetClass }, badgeBg, badgeFg)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${formatQty(h.quantity)} units • ${MoneyFormatter.formatMinor(valBase, baseCurrency, masked = masked)}",
                                    style = TabularStyle,
                                    fontSize = 12.sp,
                                    color = MutedSlate,
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = MoneyFormatter.formatMinor(pnlBase, baseCurrency, showPlus = true, masked = masked),
                                style = TabularStyle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (pnlBase >= 0L) EmeraldDark else RoseOutflow,
                            )
                            Text(
                                text = String.format(Locale.US, "%s%.2f%%", if (pnlPct >= 0.0) "+" else "", pnlPct),
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

    if (showAddHolding) {
        var symbol by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var exchange by remember { mutableStateOf("") }
        var qty by remember { mutableStateOf("") }
        var avgCost by remember { mutableStateOf("") }
        var currentPrice by remember { mutableStateOf("") }
        var assetClass by remember { mutableStateOf("STOCKS") }

        val parsedQty = qty.toDoubleOrNull() ?: 0.0
        val parsedCost = MoneyFormatter.parseDecimalToMinor(avgCost) ?: 0L
        val parsedCur = MoneyFormatter.parseDecimalToMinor(currentPrice) ?: parsedCost
        val canSubmit = symbol.isNotBlank() && parsedQty > 0.0 && parsedCur > 0L

        AlertDialog(
            onDismissRequest = { showAddHolding = false },
            title = { Text("Add Holding") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("STOCKS", "CRYPTO", "FOREX").forEach { c ->
                            FilterChip(selected = assetClass == c, onClick = { assetClass = c }, label = { Text(c, fontSize = 11.sp) })
                        }
                    }
                    OutlinedTextField(
                        value = symbol,
                        onValueChange = { symbol = it },
                        label = { Text("Symbol") },
                        placeholder = { Text("Ticker / asset code") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = exchange,
                        onValueChange = { exchange = it },
                        label = { Text("Exchange / Wallet (optional)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = qty,
                        onValueChange = { qty = it },
                        label = { Text("Quantity") },
                        placeholder = { Text("0") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = avgCost,
                        onValueChange = { avgCost = it },
                        label = { Text("Avg Buy Price ($baseCurrency)") },
                        placeholder = { Text("0.00") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = currentPrice,
                        onValueChange = { currentPrice = it },
                        label = { Text("Current Price ($baseCurrency, optional)") },
                        placeholder = { Text("Defaults to buy price") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = canSubmit,
                    onClick = {
                        val effectiveCost = if (parsedCost > 0L) parsedCost else parsedCur
                        vm.addHolding(
                            symbol = symbol,
                            name = name,
                            assetClass = assetClass,
                            exchange = exchange.ifBlank { assetClass },
                            quantity = parsedQty,
                            avgCostMinor = effectiveCost,
                            currentPriceMinor = parsedCur,
                            currency = baseCurrency,
                        )
                        showAddHolding = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddHolding = false }) { Text("Cancel") } },
        )
    }

    selectedHolding?.let { holding ->
        var priceText by remember(holding.id) {
            mutableStateOf(String.format(Locale.US, "%.2f", holding.currentPriceMinor / 100.0))
        }
        val parsedNew = MoneyFormatter.parseDecimalToMinor(priceText) ?: 0L

        AlertDialog(
            onDismissRequest = { selectedHolding = null },
            title = { Text("Update ${holding.symbol}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${holding.name} • Avg Buy: ${MoneyFormatter.formatMinor(holding.avgCostMinor, holding.currency)}",
                        fontSize = 12.sp,
                        color = MutedSlate,
                    )
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Current Unit Price (${holding.currency})") },
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = {
                            vm.deleteHolding(holding.id)
                            selectedHolding = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Delete Holding", color = RoseOutflow, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = parsedNew > 0L,
                    onClick = {
                        vm.updateHoldingPrice(holding.id, parsedNew)
                        selectedHolding = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Save Price") }
            },
            dismissButton = { TextButton(onClick = { selectedHolding = null }) { Text("Cancel") } },
        )
    }
}

private fun formatQty(q: Double): String = if (q % 1.0 == 0.0) q.toInt().toString() else String.format(Locale.US, "%.3f", q).trimEnd('0').trimEnd('.')

@Composable
private fun AllocationRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, fontSize = 13.sp, color = SlateInk)
        }
        Text(value, style = TabularStyle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
    }
}
