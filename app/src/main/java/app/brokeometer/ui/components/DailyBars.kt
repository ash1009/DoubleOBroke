package app.brokeometer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.brokeometer.ui.theme.LocalBrand

/**
 * One bar per day of the month. Today is amber, days still to come are short stubs, and days
 * away on a trip are small rings. [todayIndex] is zero-based, or -1 when the month shown is not
 * the current one.
 */
@Composable
fun DailyBars(
    perDay: List<Long>,
    todayIndex: Int,
    modifier: Modifier = Modifier,
    awayIndexes: Set<Int> = emptySet(),
) {
    val brand = LocalBrand.current
    val past = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    val future = brand.track
    val max = (perDay.maxOrNull() ?: 0L).coerceAtLeast(1L)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .semantics { contentDescription = "Spending for each day of the month" }
        ) {
            val count = perDay.size.coerceAtLeast(1)
            val slot = size.width / count
            val barWidth = (slot * 0.62f).coerceAtLeast(2f)
            val stub = 3.dp.toPx()
            perDay.forEachIndexed { index, value ->
                if (index in awayIndexes && value == 0L && index != todayIndex) {
                    val radius = (barWidth / 2f).coerceAtMost(3.5.dp.toPx())
                    drawCircle(
                        color = past,
                        radius = radius,
                        center = Offset(index * slot + slot / 2f, size.height - radius - 1.dp.toPx()),
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                    return@forEachIndexed
                }
                val isFuture = todayIndex >= 0 && index > todayIndex
                val height = if (value > 0) {
                    (size.height * value.toFloat() / max.toFloat()).coerceAtLeast(stub * 1.6f)
                } else {
                    stub
                }
                val color = when {
                    index == todayIndex -> brand.action
                    index in awayIndexes -> future
                    isFuture -> future
                    value == 0L -> future
                    else -> past
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            val style = MaterialTheme.typography.labelSmall
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            Text("1", style = style, color = color, modifier = Modifier.weight(1f))
            Text(perDay.size.toString(), style = style, color = color)
        }
    }
}
