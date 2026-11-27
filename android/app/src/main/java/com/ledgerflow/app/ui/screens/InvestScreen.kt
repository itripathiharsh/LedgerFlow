package com.ledgerflow.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.data.local.HoldingEntity
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryDonutChart
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.EmptyPlaceholder
import com.ledgerflow.app.ui.components.PieChartSlice
import com.ledgerflow.app.ui.components.SectionHeader
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

@Composable
fun InvestScreen(
    vm: LedgerViewModel,
    onOpenDrawer: () -> Unit = {},
    onOpenSettings: () -> Unit,
) {
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
    val activeClasses = listOf(summary.stocksValueMinor, summary.cryptoValueMinor, summary.forexValueMinor).count { it > 0L }
    val stocksColor = EmeraldPrimary
    val cryptoColor = SkySync
    val forexColor = MutedSlate
    val allocationSlices = remember(summary, stocksColor, cryptoColor, forexColor) {
        if (summary.totalValueMinor <= 0L) {
            emptyList()
        } else {
            val sum = summary.totalValueMinor.toFloat()
            listOfNotNull(
                summary.stocksValueMinor.takeIf { it > 0L }?.let {
                    PieChartSlice("stocks", "Stocks", it, stocksColor, it / sum)
                },
                summary.cryptoValueMinor.takeIf { it > 0L }?.let {
                    PieChartSlice("crypto", "Crypto", it, cryptoColor, it / sum)
                },
                summary.forexValueMinor.takeIf { it > 0L }?.let {
                    PieChartSlice("forex", "Forex Cash", it, forexColor, it / sum)
                },
            )
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
                    Column {
                        Text("Investments", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Text("${holdings.size} positions • $baseCurrency", fontSize = 12.sp, color = MutedSlate)
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = SlateInk)
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

        // Asset Allocation Donut Chart Card
        item {
            CleanCard(padding = 16.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Allocation", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    Text("$activeClasses Active Classes", fontSize = 12.sp, color = MutedSlate)
                }
                Spacer(Modifier.height(12.dp))
                CategoryDonutChart(
                    slices = allocationSlices,
                    totalMinor = total,
                    currency = baseCurrency,
                    masked = masked,
                    centerSubtitle = "Portfolio",
                    emptyMessage = "No portfolio allocation yet.",
                )
            }
        }

        // Holdings List
        item {
            SectionHeader(
                title = "Holdings (${holdings.size})",
                actionText = "+ Add Holding",
                onActionClick = { showAddHolding = true },
            )
        }

        if (holdings.isEmpty()) {
            item { EmptyPlaceholder("No holdings tracked", "Tap + Add Holding above to track stocks, crypto, or FX reserves.") }
        } else {
            items(holdings, key = { it.id }) { h ->
                val valLocal = AnalyticsCalculator.safeMultiplyMinor(h.quantity, h.currentPriceMinor)
                val costLocal = AnalyticsCalculator.safeMultiplyMinor(h.quantity, h.avgCostMinor)
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

        val parsedQty = qty.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
        val parsedCost = MoneyFormatter.parseDecimalToMinor(avgCost, baseCurrency) ?: 0L
        val parsedCur = MoneyFormatter.parseDecimalToMinor(currentPrice, baseCurrency) ?: parsedCost
        val canSubmit = symbol.isNotBlank() && parsedQty > 0.0 && parsedQty <= 1_000_000_000.0 && parsedCur > 0L

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
                        onValueChange = { qty = MoneyFormatter.sanitizeDecimalInput(it, maxDecimalsOverride = 6) },
                        label = { Text("Quantity") },
                        placeholder = { Text("0") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = avgCost,
                        onValueChange = { avgCost = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency) },
                        label = { Text("Avg Buy Price ($baseCurrency)") },
                        placeholder = { Text(if (baseCurrency == "JPY") "0" else "0.00") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = currentPrice,
                        onValueChange = { currentPrice = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency) },
                        label = { Text("Current Price ($baseCurrency, optional)") },
                        placeholder = { Text("Defaults to buy price") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
            mutableStateOf(
                if (holding.currency.equals("JPY", ignoreCase = true)) {
                    holding.currentPriceMinor.toString()
                } else {
                    String.format(Locale.US, "%.2f", holding.currentPriceMinor / 100.0)
                }
            )
        }
        val parsedNew = MoneyFormatter.parseDecimalToMinor(priceText, holding.currency) ?: 0L

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
                        onValueChange = { priceText = MoneyFormatter.sanitizeDecimalInput(it, holding.currency) },
                        label = { Text("Current Unit Price (${holding.currency})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
