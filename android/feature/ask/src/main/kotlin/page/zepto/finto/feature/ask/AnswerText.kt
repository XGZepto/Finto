package page.zepto.finto.feature.ask

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import page.zepto.finto.core.designsystem.theme.tabular
import kotlin.math.max
import kotlin.math.min

private const val CARET = "caret"

/**
 * The answer as it is written: text that arrives in bursts is let out at an
 * even pace, quick when far behind and gentle when close, so it reads like
 * typing rather than jumping a paragraph at a time.
 */
@Composable
internal fun rememberRevealedText(text: String, streaming: Boolean): String {
    var shown by remember { mutableIntStateOf(if (streaming) 0 else text.length) }
    LaunchedEffect(text, streaming) {
        if (shown > text.length) shown = text.length
        while (shown < text.length) {
            withFrameNanos { }
            val backlog = text.length - shown
            val step = if (streaming) max(1, backlog / 10) else max(8, backlog / 3)
            shown = min(text.length, shown + step)
        }
    }
    return text.take(shown)
}

/** Renders [blocks]; [caret] draws a cursor after the last line while text is still arriving. */
@Composable
internal fun AnswerContent(blocks: List<AnswerBlock>, caret: Boolean, modifier: Modifier = Modifier) {
    val body = MaterialTheme.typography.bodyLarge
    val caretContent = caretContent()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            val last = caret && index == blocks.lastIndex
            when (block) {
                is AnswerBlock.Heading -> Text(
                    annotate(block.parts, last),
                    style = MaterialTheme.typography.titleMedium,
                    inlineContent = caretContent,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                )
                is AnswerBlock.Paragraph -> Text(annotate(block.parts, last), style = body, inlineContent = caretContent)
                is AnswerBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.items.forEachIndexed { i, item ->
                        Row {
                            Text(
                                if (block.ordered) "${i + 1}." else "•",
                                style = body.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 22.dp),
                            )
                            Text(annotate(item, last && i == block.items.lastIndex), style = body, inlineContent = caretContent)
                        }
                    }
                }
                is AnswerBlock.Table -> AnswerTable(block)
            }
        }
        if (caret && (blocks.isEmpty() || blocks.last() is AnswerBlock.Table)) {
            Text(annotate(emptyList(), true), style = body, inlineContent = caretContent)
        }
    }
}

private fun annotate(parts: List<AnswerPart>, caret: Boolean): AnnotatedString = buildAnnotatedString {
    parts.forEach { part ->
        if (part.strong) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part.text) } else append(part.text)
    }
    if (caret) {
        append(' ')
        appendInlineContent(CARET, "…")
    }
}

@Composable
private fun caretContent(): Map<String, InlineTextContent> {
    val color = MaterialTheme.colorScheme.primary
    val blink = rememberInfiniteTransition(label = "caret")
    val alpha by blink.animateFloat(1f, 0.15f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "caret-alpha")
    return remember(color) {
        mapOf(
            CARET to InlineTextContent(Placeholder(0.45.em, 1.05.em, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.fillMaxSize().alpha(alpha).clip(RoundedCornerShape(2.dp)).background(color))
            },
        )
    }
}

/**
 * A pipe table. Columns take the width of their widest cell (capped, so a long
 * cell wraps), the first column takes any spare width, figure columns align
 * to the end, and a table wider than the screen scrolls sideways.
 */
@Composable
private fun AnswerTable(block: AnswerBlock.Table) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val headerFill = MaterialTheme.colorScheme.surfaceContainerHigh
    val shape = RoundedCornerShape(12.dp)
    val columns = block.header.size
    var rowBottoms by remember { mutableStateOf(emptyList<Int>()) }
    val cap = with(LocalDensity.current) { 220.dp.roundToPx() }
    val stroke = with(LocalDensity.current) { 1.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).border(1.dp, line, shape)) {
        val available = constraints.maxWidth
        Layout(
            content = {
                block.header.forEachIndexed { column, cell -> TableCell(cell, block.numeric.getOrElse(column) { false }, header = true) }
                block.rows.forEach { row ->
                    row.forEachIndexed { column, cell -> TableCell(cell, block.numeric.getOrElse(column) { false }, header = false) }
                }
            },
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .drawBehind {
                    val bottoms = rowBottoms
                    if (bottoms.isEmpty()) return@drawBehind
                    drawRect(headerFill, size = Size(size.width, bottoms.first().toFloat()))
                    bottoms.dropLast(1).forEach { y ->
                        drawLine(line, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), stroke)
                    }
                },
        ) { measurables, _ ->
            val widths = IntArray(columns)
            measurables.forEachIndexed { i, cell ->
                val column = i % columns
                widths[column] = max(widths[column], min(cap, cell.maxIntrinsicWidth(Constraints.Infinity)))
            }
            val natural = widths.sum()
            if (natural < available && columns > 0) widths[0] += available - natural
            val placeables = measurables.mapIndexed { i, cell -> cell.measure(Constraints.fixedWidth(widths[i % columns])) }
            val rows = if (columns == 0) 0 else placeables.size / columns
            val heights = IntArray(rows) { r -> (0 until columns).maxOf { placeables[r * columns + it].height } }
            rowBottoms = heights.toList().runningFold(0, Int::plus).drop(1)
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in 0 until rows) {
                    var x = 0
                    for (c in 0 until columns) {
                        placeables[r * columns + c].place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}

@Composable
private fun TableCell(parts: List<AnswerPart>, numeric: Boolean, header: Boolean) {
    val base: TextStyle = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    Text(
        annotate(parts, caret = false),
        style = if (numeric) base.tabular() else base,
        fontWeight = if (header) FontWeight.SemiBold else null,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        textAlign = if (numeric) TextAlign.End else TextAlign.Start,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
