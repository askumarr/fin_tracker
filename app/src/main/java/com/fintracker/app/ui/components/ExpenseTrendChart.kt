package com.fintracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fintracker.app.ui.util.DateFormatters
import com.fintracker.app.ui.util.MoneyFormat

data class MonthlySpendPoint(
    val period: DateFormatters.MonthPeriod,
    val spentPaise: Long
)

@Composable
fun ExpenseTrendChart(
    points: List<MonthlySpendPoint>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    lineColor: Color = MaterialTheme.colorScheme.secondary,
    onMonthClick: ((DateFormatters.MonthPeriod) -> Unit)? = null
) {
    if (points.isEmpty()) return

    val maxSpend = points.maxOf { it.spentPaise }.coerceAtLeast(1L)
    val total = points.sumOf { it.spentPaise }
    val peak = points.maxByOrNull { it.spentPaise }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text("Expense trend", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Last ${points.size} months · IST",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    MoneyFormat.formatPaise(total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "total spend",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .pointerInput(points, onMonthClick) {
                    if (onMonthClick == null) return@pointerInput
                    detectTapGestures { tap ->
                        val gap = 6.dp.toPx()
                        val barWidth = ((size.width - gap * (points.size + 1)) / points.size)
                            .coerceAtLeast(8.dp.toPx())
                        val index = ((tap.x - gap) / (barWidth + gap)).toInt()
                        if (index in points.indices) {
                            val barLeft = gap + index * (barWidth + gap)
                            if (tap.x in barLeft..(barLeft + barWidth)) {
                                onMonthClick(points[index].period)
                            }
                        }
                    }
                }
        ) {
            val labelReserve = 22.dp.toPx()
            val chartHeight = size.height - labelReserve
            val gap = 6.dp.toPx()
            val barWidth = ((size.width - gap * (points.size + 1)) / points.size)
                .coerceAtLeast(8.dp.toPx())
            val centers = mutableListOf<Offset>()

            points.forEachIndexed { index, point ->
                val ratio = point.spentPaise.toFloat() / maxSpend.toFloat()
                val barHeight = (chartHeight * ratio).coerceAtLeast(
                    if (point.spentPaise > 0) 4.dp.toPx() else 0f
                )
                val left = gap + index * (barWidth + gap)
                val top = chartHeight - barHeight
                drawRoundRect(
                    color = barColor.copy(alpha = if (point == peak) 1f else 0.55f),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
                centers += Offset(left + barWidth / 2f, top)
            }

            if (centers.size >= 2) {
                val path = Path().apply {
                    moveTo(centers.first().x, centers.first().y)
                    for (i in 1 until centers.size) {
                        lineTo(centers[i].x, centers[i].y)
                    }
                }
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 2.5.dp.toPx())
                )
                centers.forEach { center ->
                    drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = center)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            points.forEachIndexed { index, point ->
                Text(
                    text = if (points.size > 8 && index % 2 == 1) "" else point.period.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
            }
        }
        peak?.let {
            if (it.spentPaise > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Peak ${it.period.label}: ${MoneyFormat.formatPaise(it.spentPaise)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
