package com.fintracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fintracker.app.ui.util.MoneyFormat
import kotlin.math.min

data class CategorySlice(
    val name: String,
    val amountPaise: Long,
    val color: Color
)

@Composable
fun CategorySpendPie(
    slices: List<CategorySlice>,
    modifier: Modifier = Modifier
) {
    if (slices.isEmpty()) return
    val total = slices.sumOf { it.amountPaise }.coerceAtLeast(1L)

    Column(modifier = modifier.fillMaxWidth()) {
        Text("By category", style = MaterialTheme.typography.titleLarge)
        Text(
            "Share of spend this period",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Canvas(modifier = Modifier.size(140.dp)) {
                var start = -90f
                val diameter = min(size.width, size.height)
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                slices.forEach { slice ->
                    val sweep = (slice.amountPaise.toFloat() / total.toFloat()) * 360f
                    drawArc(
                        color = slice.color,
                        startAngle = start,
                        sweepAngle = sweep.coerceAtLeast(0.5f),
                        useCenter = true,
                        topLeft = topLeft,
                        size = Size(diameter, diameter)
                    )
                    start += sweep
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                slices.take(8).forEach { slice ->
                    val pct = (slice.amountPaise * 100.0 / total).toInt()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${slice.name} · $pct%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            MoneyFormat.formatPaise(slice.amountPaise),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

fun categoryPalette(index: Int): Color {
    val colors = listOf(
        Color(0xFF0F5C5C),
        Color(0xFFE07A2F),
        Color(0xFF3D6B8C),
        Color(0xFF8B5E3C),
        Color(0xFF5B7C5A),
        Color(0xFF9C4A6C),
        Color(0xFF6B5B95),
        Color(0xFF4A7C7C)
    )
    return colors[index % colors.size]
}
