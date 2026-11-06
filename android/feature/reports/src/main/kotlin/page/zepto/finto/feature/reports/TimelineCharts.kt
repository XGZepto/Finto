package page.zepto.finto.feature.reports

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.theme.FintoMotion
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.SeriesColors
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.Coverage
import page.zepto.finto.core.model.CoverageAccount
import page.zepto.finto.core.model.DisplayDates
import java.time.YearMonth

private val MinSlot = 28.dp

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction
private val MaxBar = 40.dp

/**
 * Stacked monthly columns. Each column is its own canvas so a tap lands on
 * one month; the band under the finger is the drill. Columns keep a readable
 * width and the row scrolls sideways, opening on the latest month, when the
 * months outnumber the screen. A change of scale eases each band from its old
 * place; new data grows from the baseline.
 */
@Composable
internal fun CompositionChart(
    columns: List<TimelineColumn>,
    colorOf: (Segment) -> Color,
    describe: (TimelineColumn) -> String,
    actionLabel: (Segment) -> String,
    onBandClick: (TimelineColumn, Segment) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
) {
    val previous = remember { mutableStateOf<Map<String, Map<String, Segment>>>(emptyMap()) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(columns) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(FintoMotion.LONG + 150, easing = FintoMotion.EmphasizedDecelerate))
        previous.value = columns.associate { column -> column.month to column.segments.associateBy { it.bucket } }
    }
    val grid = MaterialTheme.colorScheme.outlineVariant

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val count = columns.size.coerceAtLeast(1)
        // When the months overflow, whole columns fill the view, so none is cut at its edge.
        val fit = (maxWidth / MinSlot).toInt().coerceAtLeast(1)
        val slot = if (count <= fit) maxWidth / count else maxWidth / fit
        val scroll = rememberScrollState(Int.MAX_VALUE)
        Row(Modifier.horizontalScroll(scroll, enabled = slot * count > maxWidth)) {
            columns.forEachIndexed { index, column ->
                val before = previous.value[column.month]
                Column(Modifier.width(slot), horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(height)
                            .pointerInput(column) {
                                detectTapGestures { offset ->
                                    TimelineMath.segmentAt(column, 1f - offset.y / size.height)?.let { onBandClick(column, it) }
                                }
                            }
                            .semantics {
                                contentDescription = describe(column)
                                customActions = column.segments.filter { it.amount > 0 }.map { segment ->
                                    CustomAccessibilityAction(actionLabel(segment)) { onBandClick(column, segment); true }
                                }
                            },
                    ) {
                        for (line in 1..3) {
                            val y = size.height * line / 4f
                            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        }
                        val barWidth = (size.width * 0.78f).coerceAtMost(MaxBar.toPx())
                        val left = (size.width - barWidth) / 2
                        val p = progress.value
                        val placed = column.segments.map { segment ->
                            val old = before?.get(segment.bucket)
                            val bottom = if (old != null) lerp(old.bottom, segment.bottom, p) else segment.bottom * p
                            val extent = if (old != null) lerp(old.height, segment.height, p) else segment.height * p
                            Triple(segment, bottom, extent)
                        }
                        val top = placed.maxOfOrNull { (_, bottom, extent) -> bottom + extent } ?: 0f
                        if (top <= 0f) return@Canvas
                        val corner = CornerRadius(4.dp.toPx())
                        val outline = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = left,
                                    top = size.height * (1f - top),
                                    right = left + barWidth,
                                    bottom = size.height,
                                    topLeftCornerRadius = corner,
                                    topRightCornerRadius = corner,
                                ),
                            )
                        }
                        val gap = 1.dp.toPx()
                        clipPath(outline) {
                            placed.forEach { (segment, bottom, extent) ->
                                val pixels = size.height * extent
                                if (pixels <= 0f) return@forEach
                                val y = size.height * (1f - bottom - extent)
                                // A hairline between bands, when the band can spare it.
                                val trim = if (pixels > gap * 3) gap else 0f
                                drawRect(colorOf(segment), Offset(left, y + trim), Size(barWidth, pixels - trim))
                            }
                        }
                    }
                    MonthTick(column.month, first = index == 0)
                }
            }
        }
    }
}

@Composable
private fun MonthTick(month: String, first: Boolean) {
    val parsed = runCatching { YearMonth.parse(month) }.getOrNull()
    val showYear = first || parsed?.monthValue == 1
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics { }) {
        Text(
            DisplayDates.monthAbbreviation(month),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
        Text(
            if (showYear && parsed != null) "'${parsed.year % 100}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A small square of a band's colour, for legends. */
@Composable
internal fun Swatch(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

private enum class Cell(val key: String, val label: String) {
    Statement("statement", "statement"),
    Export("export", "export only"),
    None("none", "none"),
}

@Composable
private fun cellColor(cell: String): Color = when (cell) {
    Cell.Statement.key -> SeriesColors[1]
    Cell.Export.key -> SeriesColors[2].copy(alpha = 0.65f)
    Cell.None.key -> FintoThemeTokens.money.negative.copy(alpha = 0.4f)
    // Before the account's first activity: it did not exist, which is not a gap.
    else -> Color.Transparent
}

/** Per account, month by month: whether a statement backs it, only an export, or nothing. */
@Composable
internal fun CoverageGrid(coverage: Coverage, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cell.entries.forEach { cell ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(cellColor(cell.key))
                    Spacer(Modifier.width(6.dp))
                    Text(cell.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        coverage.accounts.forEach { account -> CoverageRow(account, coverage.months) }
        if (coverage.months.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                val style = MaterialTheme.typography.labelSmall.tabular()
                Text(monthLabel(coverage.months.first()), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(monthLabel(coverage.months.last()), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CoverageRow(account: CoverageAccount, months: List<String>) {
    val counts = TimelineMath.coverageCounts(account)
    val colors = account.cells.map { cellColor(it) }
    val missing = MaterialTheme.colorScheme.error
    Column(
        Modifier.clearAndSetSemantics { contentDescription = "${account.accountName}: ${counts.ifEmpty { "no months" }}" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                account.accountName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                buildAnnotatedString {
                    counts.split(" · ").forEachIndexed { index, part ->
                        if (index > 0) append(" · ")
                        if (part.endsWith("missing")) withStyle(SpanStyle(color = missing)) { append(part) } else append(part)
                    }
                },
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val n = months.size.coerceAtLeast(colors.size).coerceAtLeast(1)
            val gap = if (size.width / n > 4.dp.toPx()) 1.dp.toPx() else 0f
            val cell = (size.width - gap * (n - 1)) / n
            colors.forEachIndexed { index, color ->
                if (color.alpha > 0f) {
                    drawRect(color, Offset(index * (cell + gap), 0f), Size(cell, size.height))
                }
            }
        }
    }
}
