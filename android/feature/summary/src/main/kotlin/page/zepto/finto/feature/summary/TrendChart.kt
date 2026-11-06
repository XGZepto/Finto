package page.zepto.finto.feature.summary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import page.zepto.finto.core.designsystem.theme.FintoMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.MonthFlow

/**
 * Out and in per month on one scale, so a month that earned more than it spent
 * is visible as such. Paired bars rather than a smoothed line: each month is a
 * discrete statement period.
 */
@Composable
internal fun TrendChart(months: List<MonthFlow>, modifier: Modifier = Modifier, onMonthClick: ((String) -> Unit)? = null) {
    val outColor = MaterialTheme.colorScheme.primary
    val inColor = FintoThemeTokens.money.positive
    val grid = MaterialTheme.colorScheme.outlineVariant
    val peak = months.maxOfOrNull { maxOf(it.spend.amount, it.income.amount) }?.coerceAtLeast(1) ?: 1
    val peakFigure = months.flatMap { listOf(it.spend, it.income) }.maxByOrNull { it.amount }
    val description = months.joinToString("; ") {
        "${DisplayDates.shortMonth(it.month)}: out ${MoneyFormat.formatWhole(it.spend)}, in ${MoneyFormat.formatWhole(it.income)}"
    }
    val still = LocalInspectionMode.current
    val grow = remember(months) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(months) { grow.animateTo(1f, tween(FintoMotion.LONG + 150, easing = FintoMotion.EmphasizedDecelerate)) }
    Column(modifier.semantics { contentDescription = "Money out and in by month. $description" }) {
        val tap = if (onMonthClick == null || months.isEmpty()) Modifier else Modifier.pointerInput(months) {
            detectTapGestures { offset ->
                val index = (offset.x / (size.width / months.size)).toInt().coerceIn(0, months.lastIndex)
                onMonthClick(months[index].month)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(160.dp).then(tap)) {
            val count = months.size.coerceAtLeast(1)
            val slot = size.width / count
            val barWidth = (slot * 0.32f).coerceAtMost(14.dp.toPx())
            val gap = 2.dp.toPx()
            for (line in 1..3) {
                val y = size.height * line / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            months.forEachIndexed { index, month ->
                val center = slot * index + slot / 2
                val outHeight = size.height * month.spend.amount / peak * grow.value
                val inHeight = size.height * month.income.amount / peak * grow.value
                drawRoundRect(
                    color = outColor,
                    topLeft = Offset(center - barWidth - gap / 2, size.height - outHeight),
                    size = Size(barWidth, outHeight.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
                drawRoundRect(
                    color = inColor,
                    topLeft = Offset(center + gap / 2, size.height - inHeight),
                    size = Size(barWidth, inHeight.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            // At most six labels, counted back from the latest month so it is always named.
            val step = ((months.size + 5) / 6).coerceAtLeast(1)
            months.forEachIndexed { index, month ->
                Text(
                    text = if ((months.lastIndex - index) % step == 0) DisplayDates.monthAbbreviation(month.month) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend("Out", outColor)
            Legend("In", inColor)
            Spacer(Modifier.weight(1f))
            peakFigure?.let {
                Text(
                    "Peak ${MoneyFormat.formatWhole(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
