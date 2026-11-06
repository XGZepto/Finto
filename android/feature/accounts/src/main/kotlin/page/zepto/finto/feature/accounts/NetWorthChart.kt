package page.zepto.finto.feature.accounts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.theme.FintoMotion
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.NetWorthPoint

/**
 * Month-end net worth as a line that draws itself in. Months no statement has
 * fully confirmed are dashed, as on the web.
 */
@Composable
internal fun NetWorthChart(points: List<NetWorthPoint>, modifier: Modifier = Modifier, height: Dp = 120.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val still = LocalInspectionMode.current
    val reveal = remember(points) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(points) { reveal.animateTo(1f, tween(900, easing = FintoMotion.EmphasizedDecelerate)) }
    val values = points.map { it.balance.amount }
    val low = values.minOrNull() ?: 0L
    val high = values.maxOrNull() ?: 0L
    val span = (high - low).coerceAtLeast(1L).toFloat()
    val description = points.joinToString("; ") { "${DisplayDates.shortMonth(it.bucket)} ${MoneyFormat.formatWhole(it.balance)}" }
    Column(modifier.semantics { contentDescription = "Net worth by month. $description" }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val pad = 6.dp.toPx()
            val usable = size.height - pad * 2
            // Centred in equal slots, so each point sits over its month label.
            fun at(index: Int): Offset = Offset(
                size.width * (index + 0.5f) / points.size,
                pad + usable - usable * (values[index] - low) / span,
            )
            for (line in 1..3) {
                val y = size.height * line / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            if (low < 0 && high > 0) {
                val zero = pad + usable - usable * (0 - low) / span
                drawLine(grid, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 2f)
            }
            clipRect(right = size.width * reveal.value) {
                val area = Path().apply {
                    moveTo(at(0).x, size.height)
                    points.indices.forEach { lineTo(at(it).x, at(it).y) }
                    lineTo(at(points.lastIndex).x, size.height)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.18f), Color.Transparent)))
                for (index in 1..points.lastIndex) {
                    val dashed = !points[index].complete
                    drawLine(
                        color,
                        at(index - 1),
                        at(index),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
                    )
                }
                drawCircle(color, radius = 4.dp.toPx(), center = at(points.lastIndex), style = Stroke(2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val step = ((points.size + 5) / 6).coerceAtLeast(1)
            points.forEachIndexed { index, point ->
                Text(
                    if ((points.lastIndex - index) % step == 0) DisplayDates.monthAbbreviation(point.bucket) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
