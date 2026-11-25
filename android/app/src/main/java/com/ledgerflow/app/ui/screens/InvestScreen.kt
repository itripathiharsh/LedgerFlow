package com.ledgerflow.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
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

    var timeframeIdx by remember { mutableIntStateOf(2) } // 1M
    var showAddHolding by remember { mutableStateOf(false) }

    val total = summary.totalValueMinor
    val stocksPct = if (total > 0L) ((summary.stocksValueMinor.toDouble() / total) * 100.0).toInt() else 0
    val cryptoPct = if (total > 0L) ((summary.cryptoValueMinor.toDouble() / total) * 100.0).toInt() else 0
    val forexPct = if (total > 0L) (100 - stocksPct - cryptoPct).coerceAtLeast(0) else 0

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
                    Text("• Cached in Room • Offline Ready", fontSize = 11.sp, color = EmeraldDark, fontWeight = FontWeight.Medium)
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

        // Hero Portfolio Card
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
                    text = "$gainStr ($pctStr) All Time",
                    containerColor = if (summary.gainMinor >= 0) EmeraldSoft else RoseSoft,
                    contentColor = if (summary.gainMinor >= 0) EmeraldDark else RoseOutflow,
                )
                Spacer(Modifier.height(14.dp))
                PortfolioAreaChart(timeframeIdx)
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("1D", "1W", "1M", "1Y", "ALL").forEachIndexed { idx, label ->
                        val active = timeframeIdx == idx
                        Surface(
                            onClick = { timeframeIdx = idx },
                            shape = RoundedCornerShape(999.dp),
                            color = if (active) SlateInk else SlateTint,
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) Color.White else MutedSlate,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }

        // Asset Allocation Card
        item {
            CleanCard(padding = 16.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Allocation", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    Text("3 Asset Classes", fontSize = 12.sp, color = MutedSlate)
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

        // Holdings List (zero ellipsis truncation!)
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
                val valBase = FxConverter.convertMinor(valLocal, h.currency, baseCurrency)
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
                CleanCard(padding = 14.dp) {
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
                        Text(
                            text = String.format(Locale.US, "%s%.2f%%", if (h.dayChangeBps >= 0) "+" else "", h.dayChangeBps / 100.0),
                            style = TabularStyle,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (h.dayChangeBps >= 0) EmeraldDark else RoseOutflow,
                        )
                    }
                }
            }
        }
    }

    if (showAddHolding) {
        var symbol by remember { mutableStateOf("AAPL") }
        var name by remember { mutableStateOf("Apple Inc.") }
        var qty by remember { mutableStateOf("5") }
        var price by remember { mutableStateOf("230.00") }
        var assetClass by remember { mutableStateOf("STOCKS") }

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
                    OutlinedTextField(value = symbol, onValueChange = { symbol = it }, label = { Text("Symbol") }, singleLine = true)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(value = qty, onValueChange = { qty = it }, label = { Text("Quantity") }, singleLine = true)
                    OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Unit Price ($baseCurrency)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val q = qty.toDoubleOrNull() ?: 0.0
                        val p = MoneyFormatter.parseDecimalToMinor(price) ?: 0L
                        vm.addHolding(symbol, name, assetClass, assetClass, q, p, p, baseCurrency)
                        showAddHolding = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddHolding = false }) { Text("Cancel") } },
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

@Composable
private fun PortfolioAreaChart(timeframeIdx: Int) {
    val curves = listOf(
        listOf(0.55f, 0.58f, 0.56f, 0.61f, 0.64f, 0.68f),
        listOf(0.45f, 0.49f, 0.52f, 0.50f, 0.60f, 0.72f),
        listOf(0.32f, 0.42f, 0.44f, 0.56f, 0.62f, 0.85f),
        listOf(0.20f, 0.35f, 0.48f, 0.44f, 0.70f, 0.90f),
        listOf(0.15f, 0.25f, 0.38f, 0.55f, 0.75f, 0.94f),
    )
    val pts = curves.getOrElse(timeframeIdx) { curves[2] }
    Canvas(modifier = Modifier.fillMaxWidth().height(92.dp)) {
        val stepX = size.width / (pts.size - 1).coerceAtLeast(1)
        val strokePath = Path()
        val fillPath = Path()
        pts.forEachIndexed { idx, p ->
            val x = idx * stepX
            val y = size.height * (1f - p)
            if (idx == 0) {
                strokePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                val prevX = (idx - 1) * stepX
                val prevY = size.height * (1f - pts[idx - 1])
                val cx = (prevX + x) / 2f
                strokePath.cubicTo(cx, prevY, cx, y, x, y)
                fillPath.cubicTo(cx, prevY, cx, y, x, y)
            }
            if (idx == pts.lastIndex) {
                fillPath.lineTo(x, size.height)
                fillPath.close()
            }
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(listOf(EmeraldPrimary.copy(alpha = 0.22f), Color.Transparent)),
        )
        drawPath(
            path = strokePath,
            color = EmeraldPrimary,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = EmeraldPrimary,
            radius = 5.dp.toPx(),
            center = Offset(size.width, size.height * (1f - pts.last())),
        )
    }
}
