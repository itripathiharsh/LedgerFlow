package com.ledgerflow.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.TabularStyle
import java.util.Locale

data class PieChartSlice(
    val id: String,
    val label: String,
    val valueMinor: Long,
    val color: Color,
    val ratio: Float,
)

fun parseSliceColor(hex: String, fallback: Color = EmeraldPrimary): Color =
    runCatching { Color(hex.toColorInt()) }.getOrDefault(fallback)

@Composable
fun CategoryDonutChart(
    slices: List<PieChartSlice>,
    totalMinor: Long,
    currency: String,
    masked: Boolean,
    centerSubtitle: String = "Total",
    modifier: Modifier = Modifier,
) {
    if (slices.isEmpty() || totalMinor <= 0L) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SlateTint)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No expense or allocation records yet.",
                fontSize = 12.sp,
                color = MutedSlate,
            )
        }
        return
    }

    var selectedIdx by remember(slices) { mutableIntStateOf(-1) }
    val selected = slices.getOrNull(selectedIdx)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(164.dp)) {
                val strokePx = 26.dp.toPx()
                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                val topLeft = Offset(strokePx / 2f, strokePx / 2f)
                val gapDegrees = if (slices.size > 1) 3f else 0f
                var startAngle = -90f

                slices.forEachIndexed { idx, slice ->
                    val rawSweep = slice.ratio * 360f
                    val sweep = (rawSweep - gapDegrees).coerceAtLeast(if (rawSweep > 0f) 2f else 0f)
                    val isDimmed = selectedIdx >= 0 && selectedIdx != idx
                    val activeStroke = if (selectedIdx == idx) strokePx + 5.dp.toPx() else strokePx
                    val insetDelta = (activeStroke - strokePx) / 2f
                    drawArc(
                        color = if (isDimmed) slice.color.copy(alpha = 0.28f) else slice.color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(topLeft.x - insetDelta, topLeft.y - insetDelta),
                        size = Size(arcSize.width + insetDelta * 2f, arcSize.height + insetDelta * 2f),
                        style = Stroke(width = activeStroke, cap = StrokeCap.Butt),
                    )
                    startAngle += rawSweep
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = selected?.label ?: centerSubtitle,
                    fontSize = 11.sp,
                    color = MutedSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = MoneyFormatter.formatMinor(
                        selected?.valueMinor ?: totalMinor,
                        currency,
                        masked = masked,
                    ),
                    style = TabularStyle,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateInk,
                )
                if (selected != null) {
                    Text(
                        text = String.format(Locale.US, "%.1f%%", selected.ratio * 100f),
                        style = TabularStyle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = EmeraldDark,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEachIndexed { idx, slice ->
                val isSelected = selectedIdx == idx
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) SlateTint else Color.Transparent)
                        .clickable { selectedIdx = if (isSelected) -1 else idx }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(slice.color),
                        )
                        Text(
                            text = slice.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = SlateInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = MoneyFormatter.formatMinor(slice.valueMinor, currency, masked = masked),
                            style = TabularStyle,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SlateInk,
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f%%", slice.ratio * 100f),
                            style = TabularStyle,
                            fontSize = 11.sp,
                            color = MutedSlate,
                            modifier = Modifier.width(44.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

private val heatmapPalette = listOf(
    Color(0xFFF1F5F9), // Level 0: Zero spend
    Color(0xFFD1FAE5), // Level 1: Low spend
    Color(0xFF6EE7B7), // Level 2: Moderate spend
    Color(0xFF10B981), // Level 3: High spend
    Color(0xFF047857), // Level 4: Peak spend
)

@Composable
fun SpendHeatmapCard(
    monthlyData: AnalyticsCalculator.MonthlyHeatmapData,
    rollingData: AnalyticsCalculator.RollingHeatmapData,
    currency: String,
    masked: Boolean,
    allowModeSwitch: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableIntStateOf(0) } // 0 = Monthly Calendar, 1 = 12-Week GitHub Grid
    var selectedCellDayStart by remember(monthlyData, rollingData, mode) {
        val defaultCell = if (mode == 0) {
            monthlyData.cells.firstOrNull { it.dayOfMonth == monthlyData.todayDayOfMonth }
                ?: monthlyData.cells.lastOrNull { it.totalMinor > 0L }
                ?: monthlyData.cells.firstOrNull()
        } else {
            rollingData.cells.lastOrNull { !it.isFuture && it.totalMinor > 0L }
                ?: rollingData.cells.lastOrNull { !it.isFuture }
        }
        mutableLongStateOf(defaultCell?.dayStartMs ?: -1L)
    }

    val activeCells = if (mode == 0) monthlyData.cells else rollingData.cells
    val inspected = activeCells.firstOrNull { it.dayStartMs == selectedCellDayStart }
    val activeDays = if (mode == 0) monthlyData.activeDays else rollingData.activeDays
    val maxDaily = if (mode == 0) monthlyData.maxDailyMinor else rollingData.maxDailyMinor

    CleanCard(padding = 16.dp, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Daily Spend Map", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                Text(
                    text = if (mode == 0) {
                        "${monthlyData.monthLabel} • $activeDays active spend days"
                    } else {
                        "Last ${rollingData.weekCount} weeks • $activeDays active days"
                    },
                    fontSize = 12.sp,
                    color = MutedSlate,
                )
            }
            if (allowModeSwitch) {
                Surface(shape = RoundedCornerShape(10.dp), color = SlateTint) {
                    Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf("Month", "12W").forEachIndexed { idx, label ->
                            val active = mode == idx
                            Surface(
                                onClick = { mode = idx },
                                shape = RoundedCornerShape(8.dp),
                                color = if (active) Color.White else Color.Transparent,
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) SlateInk else MutedSlate,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                StatusPill(monthlyData.monthLabel, EmeraldSoft, EmeraldDark)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (mode == 0) {
            val weekdays = listOf("M", "T", "W", "T", "F", "S", "S")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                weekdays.forEach { d ->
                    Text(
                        text = d,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MutedSlate,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            val totalSlots = monthlyData.firstDayWeekdayOffset + monthlyData.cells.size
            val rowCount = (totalSlots + 6) / 7
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (r in 0 until rowCount) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (c in 0 until 7) {
                            val slotIdx = r * 7 + c
                            val dayIdx = slotIdx - monthlyData.firstDayWeekdayOffset
                            val cell = monthlyData.cells.getOrNull(dayIdx)
                            if (cell == null) {
                                Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                            } else {
                                val bg = if (cell.isFuture) Color(0xFFF8FAFC) else heatmapPalette[cell.level.coerceIn(0, 4)]
                                val isToday = cell.dayOfMonth == monthlyData.todayDayOfMonth
                                val isSelected = cell.dayStartMs == selectedCellDayStart
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(bg)
                                        .then(
                                            when {
                                                isSelected -> Modifier.border(1.5.dp, SlateInk, RoundedCornerShape(6.dp))
                                                isToday -> Modifier.border(1.dp, EmeraldDark, RoundedCornerShape(6.dp))
                                                cell.level == 0 -> Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                                else -> Modifier
                                            }
                                        )
                                        .clickable { selectedCellDayStart = cell.dayStartMs },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = cell.dayOfMonth.toString(),
                                        fontSize = 10.sp,
                                        fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = when {
                                            cell.isFuture -> MutedSlate.copy(alpha = 0.45f)
                                            cell.level >= 3 -> Color.White
                                            else -> SlateInk.copy(alpha = 0.78f)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Classic GitHub 7-row x N-column contribution matrix
            val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(end = 6.dp)) {
                    dayLabels.forEachIndexed { idx, lbl ->
                        Box(modifier = Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (idx % 2 == 0) lbl else "",
                                fontSize = 9.sp,
                                color = MutedSlate,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (w in 0 until rollingData.weekCount) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (d in 0 until 7) {
                                val cell = rollingData.cells.getOrNull(w * 7 + d)
                                if (cell != null) {
                                    val bg = if (cell.isFuture) Color(0xFFF8FAFC) else heatmapPalette[cell.level.coerceIn(0, 4)]
                                    val isSelected = cell.dayStartMs == selectedCellDayStart
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(bg)
                                            .then(
                                                when {
                                                    isSelected -> Modifier.border(1.5.dp, SlateInk, RoundedCornerShape(4.dp))
                                                    cell.level == 0 -> Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                                    else -> Modifier
                                                }
                                            )
                                            .clickable { selectedCellDayStart = cell.dayStartMs },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Inspected day readout + GitHub Less..More legend
        if (inspected != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SlateTint,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (inspected.txCount > 0) {
                            "${inspected.label}: ${inspected.txCount} ${if (inspected.txCount == 1) "expense" else "expenses"}"
                        } else {
                            "${inspected.label}: No expenses"
                        },
                        fontSize = 12.sp,
                        color = SlateInk,
                    )
                    Text(
                        text = MoneyFormatter.formatMinor(inspected.totalMinor, currency, masked = masked),
                        style = TabularStyle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (inspected.totalMinor > 0L) EmeraldDark else MutedSlate,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (maxDaily > 0L) {
                    "Peak: ${MoneyFormatter.formatMinor(maxDaily, currency, masked = masked)}"
                } else {
                    "Tap any day to inspect"
                },
                style = TabularStyle,
                fontSize = 11.sp,
                color = MutedSlate,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("Less", fontSize = 10.sp, color = MutedSlate)
                heatmapPalette.forEachIndexed { idx, col ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(col)
                            .then(if (idx == 0) Modifier.border(0.75.dp, BorderSubtle, RoundedCornerShape(2.dp)) else Modifier),
                    )
                }
                Text("More", fontSize = 10.sp, color = MutedSlate)
            }
        }
    }
}
