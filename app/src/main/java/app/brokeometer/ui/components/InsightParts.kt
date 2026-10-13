package app.brokeometer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.brokeometer.insights.MonthBar
import app.brokeometer.ui.theme.LocalBrand
import app.brokeometer.ui.theme.TABULAR
import java.time.format.TextStyle
import java.util.Locale

/** A rounded panel holding one insight. */
@Composable
fun InsightCard(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            content()
        }
    }
    val m = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = m) { inner() }
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = m) { inner() }
    }
}

/** A large figure with a short line under it. */
@Composable
fun BigFigure(value: String, caption: String, color: Color = Color.Unspecified) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = TABULAR),
            color = color,
            maxLines = 1,
        )
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Plain supporting text inside a card. */
@Composable
fun CardText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        modifier = modifier,
    )
}

/** Spending per month as bars, with a dashed line at a usual month. The current month is amber. */
@Composable
fun MonthBars(bars: List<MonthBar>, usualMinor: Long?, modifier: Modifier = Modifier) {
    val brand = LocalBrand.current
    val past = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val max = maxOf(bars.maxOfOrNull { it.totalMinor } ?: 0L, usualMinor ?: 0L).coerceAtLeast(1L)
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = "Spending for each of the last months" }
        ) {
            val slot = size.width / bars.size.coerceAtLeast(1)
            val barWidth = (slot * 0.58f).coerceIn(3f, 22.dp.toPx())
            bars.forEachIndexed { i, bar ->
                val h = (size.height * bar.totalMinor / max.toFloat()).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color = if (bar.current) brand.action else past,
                    topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f),
                )
                if (bar.awayDays > 0) {
                    val r = 2.5.dp.toPx()
                    drawCircle(
                        color = line,
                        radius = r,
                        center = Offset(i * slot + slot / 2f, size.height - h - r - 4.dp.toPx()),
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                }
            }
            if (usualMinor != null && usualMinor > 0) {
                val y = size.height - size.height * usualMinor / max.toFloat()
                drawLine(
                    color = line,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            bars.forEach { bar ->
                Text(
                    bar.month.month.getDisplayName(TextStyle.NARROW, Locale.UK),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A thin bar with a value against a limit. Turns amber at 80% and red when over. */
@Composable
fun LimitBar(usedMinor: Long, limitMinor: Long, modifier: Modifier = Modifier, color: Color? = null) {
    val brand = LocalBrand.current
    val fraction = if (limitMinor > 0) usedMinor.toFloat() / limitMinor else 0f
    val tone = when {
        fraction > 1f -> brand.broke
        fraction >= 0.8f -> brand.careful
        else -> color ?: brand.fine
    }
    ShareBar(fraction = fraction.coerceIn(0f, 1f), color = tone, modifier = modifier)
}
