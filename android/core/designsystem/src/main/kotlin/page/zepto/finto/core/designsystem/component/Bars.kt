package page.zepto.finto.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.theme.FintoMotion

/** One column of a [BarChart]. [value] is a magnitude: draw it as is. */
data class Bar(val key: String, val label: String, val value: Long, val description: String = label)

/**
 * A single series of columns with an optional dashed reference line (a mean,
 * a budget). Bars grow in from the baseline. When [onBarClick] is set a tap
 * picks the nearest column, and [selected] columns stay at full strength while
 * the rest dim.
 */
@Composable
fun BarChart(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    selected: Set<String> = emptySet(),
    reference: Long? = null,
    maxLabels: Int = 6,
    contentDescription: String? = null,
    onBarClick: ((Bar) -> Unit)? = null,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val referenceColor = MaterialTheme.colorScheme.onSurfaceVariant
    val peak = (bars.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(reference ?: 0L).coerceAtLeast(1L)
    val still = LocalInspectionMode.current
    val grow = remember(bars) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(bars) { grow.animateTo(1f, tween(FintoMotion.LONG + 150, easing = FintoMotion.EmphasizedDecelerate)) }

    Column(modifier.semantics { this.contentDescription = contentDescription ?: bars.joinToString("; ") { it.description } }) {
        Canvas(
            Modifier.fillMaxWidth().height(height).let { base ->
                if (onBarClick == null || bars.isEmpty()) base else base.pointerInput(bars) {
                    detectTapGestures { offset ->
                        val index = (offset.x / (size.width / bars.size)).toInt().coerceIn(0, bars.lastIndex)
                        onBarClick(bars[index])
                    }
                }
            },
        ) {
            val slot = size.width / bars.size.coerceAtLeast(1)
            val barWidth = (slot * 0.6f).coerceAtMost(28.dp.toPx())
            for (line in 1..3) {
                val y = size.height * line / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            bars.forEachIndexed { index, bar ->
                val barHeight = size.height * bar.value / peak * grow.value
                val dim = selected.isNotEmpty() && bar.key !in selected
                drawRoundRect(
                    color = if (dim) color.copy(alpha = 0.3f) else color,
                    topLeft = Offset(slot * index + (slot - barWidth) / 2, size.height - barHeight),
                    size = Size(barWidth, barHeight.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            reference?.takeIf { it > 0 }?.let {
                val y = size.height - size.height * it / peak
                drawLine(
                    referenceColor,
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            // Counted back from the latest bar so it is always named.
            val step = ((bars.size + maxLabels - 1) / maxLabels).coerceAtLeast(1)
            bars.forEachIndexed { index, bar ->
                Text(
                    text = if ((bars.lastIndex - index) % step == 0) bar.label else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bar.key in selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
