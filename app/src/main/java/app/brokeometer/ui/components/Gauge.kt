package app.brokeometer.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.brokeometer.ui.theme.BrandColors
import app.brokeometer.ui.theme.LocalBrand
import kotlin.math.cos
import kotlin.math.sin

/** How far round the dial a level sits. A full usual month lands at four fifths of the arc. */
fun meterPosition(level: Float): Float = (level / 1.25f).coerceIn(0f, 1f)

/**
 * The dial. [level] is this month's spend as a share of a usual month, or null when there is
 * no usual month to compare with yet, in which case the needle rests at the start.
 */
@Composable
fun MeterGauge(level: Float?, modifier: Modifier = Modifier, description: String = "") {
    val brand = LocalBrand.current
    val target = level?.let { meterPosition(it) } ?: 0f

    // The needle swings up from rest once, with a little overshoot, like a real gauge.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val position by animateFloatAsState(
        targetValue = if (started) target else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessVeryLow),
        label = "needle",
    )

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.9f)
            .semantics { contentDescription = description }
    ) {
        val stroke = 14.dp.toPx()
        val tickGap = 10.dp.toPx()
        val radius = minOf(size.width / 2f, size.height) - stroke / 2f - 2.dp.toPx()
        val center = Offset(size.width / 2f, size.height - stroke / 2f)
        val arcTopLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)

        // Unlit track.
        drawArc(
            color = brand.track,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        // Lit part, drawn in short pieces so the colour runs from fine to broke along the arc.
        if (level != null && position > 0.004f) {
            val pieces = 48
            val lit = (position * pieces).toInt().coerceAtLeast(1)
            val sweep = 180f * position / lit
            for (i in 0 until lit) {
                val at = (i + 0.5f) / pieces
                drawArc(
                    color = zoneColor(at, brand),
                    startAngle = 180f + i * sweep,
                    sweepAngle = sweep + 0.6f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = if (i == 0) StrokeCap.Round else StrokeCap.Butt),
                )
            }
        }

        // Tick marks inside the arc, a longer one where a usual month ends.
        val tickOuter = radius - stroke / 2f - tickGap
        for (i in 0..10) {
            val fraction = i / 10f
            val usualMark = i == 8
            val length = if (usualMark) 12.dp.toPx() else 5.dp.toPx()
            val a = Math.toRadians((180f + 180f * fraction).toDouble())
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            drawLine(
                color = if (usualMark) brand.needle.copy(alpha = 0.75f) else brand.needle.copy(alpha = 0.28f),
                start = Offset(center.x + dx * tickOuter, center.y + dy * tickOuter),
                end = Offset(center.x + dx * (tickOuter - length), center.y + dy * (tickOuter - length)),
                strokeWidth = if (usualMark) 2.5.dp.toPx() else 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // No needle until there is a usual month to point against.
        if (level != null) drawNeedle(center, radius - stroke / 2f - 4.dp.toPx(), position, brand)
    }
}

private fun DrawScope.drawNeedle(center: Offset, length: Float, position: Float, brand: BrandColors) {
    val a = Math.toRadians((180f + 180f * position).toDouble())
    val dx = cos(a).toFloat()
    val dy = sin(a).toFloat()
    val inner = length * 0.70f
    // Only the outer part of the needle is drawn, so the figure in the middle stays clear.
    drawLine(
        color = brand.needle,
        start = Offset(center.x + dx * inner, center.y + dy * inner),
        end = Offset(center.x + dx * length, center.y + dy * length),
        strokeWidth = 5.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/** Mint at the start of the dial, amber through the middle, red at the end. */
private fun zoneColor(fraction: Float, brand: BrandColors): Color = when {
    fraction < 0.5f -> lerp(brand.fine, brand.careful, (fraction / 0.5f).coerceIn(0f, 1f))
    else -> lerp(brand.careful, brand.broke, ((fraction - 0.5f) / 0.4f).coerceIn(0f, 1f))
}
