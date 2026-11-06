package page.zepto.finto.feature.investments

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.theme.FintoMotion
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.ValuePoint
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Where each point sits in a unit square: x by date, y by value (0 at the top). */
internal fun chartPositions(points: List<ValuePoint>): List<Offset> {
    if (points.isEmpty()) return emptyList()
    val days = points.map { LocalDate.parse(it.asOfDate.take(10)) }
    val first = days.first()
    val span = ChronoUnit.DAYS.between(first, days.last()).toFloat()
    val values = points.map { it.value.amount.toFloat() }
    val low = values.min()
    val high = values.max()
    // A flat history sits in the middle rather than on an edge.
    val pad = if (high == low) 1f else (high - low) * 0.12f
    val bottom = low - pad
    val range = (high + pad) - bottom
    return points.indices.map { index ->
        val x = if (span == 0f) index / (points.size - 1).coerceAtLeast(1).toFloat() else ChronoUnit.DAYS.between(first, days[index]) / span
        Offset(x, 1f - (values[index] - bottom) / range)
    }
}

/** A dated value line that draws in from the left, with a soft fill under it. */
@Composable
internal fun ValueChart(
    points: List<ValuePoint>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
) {
    val positions = remember(points) { chartPositions(points) }
    val still = LocalInspectionMode.current
    val reveal = remember(points) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(points) { reveal.animateTo(1f, tween(FintoMotion.LONG + 300, easing = FintoMotion.EmphasizedDecelerate)) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = points.takeIf { it.size >= 2 }?.let {
        "Value from ${MoneyFormat.formatWhole(it.first().value)} on ${DisplayDates.medium(it.first().asOfDate)} " +
            "to ${MoneyFormat.formatWhole(it.last().value)} on ${DisplayDates.medium(it.last().asOfDate)}"
    }.orEmpty()

    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height).padding(vertical = 6.dp)) {
            for (line in 0..3) {
                val y = size.height * line / 3f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            if (positions.size < 2) return@Canvas
            val inset = 4.dp.toPx()
            val plotted = positions.map { Offset(inset + it.x * (size.width - inset * 2), it.y * size.height) }
            val line = Path().apply {
                moveTo(plotted.first().x, plotted.first().y)
                plotted.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(plotted.last().x, size.height)
                lineTo(plotted.first().x, size.height)
                close()
            }
            clipRect(right = size.width * reveal.value) {
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
                drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(color, radius = 4.dp.toPx(), center = plotted.last())
                drawCircle(Color.White.copy(alpha = 0.9f), radius = 1.75.dp.toPx(), center = plotted.last())
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                DisplayDates.short(points.first().asOfDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                DisplayDates.short(points.last().asOfDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
