package page.zepto.finto.feature.recurring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import page.zepto.finto.core.designsystem.component.SkeletonBlock
import page.zepto.finto.core.designsystem.theme.SeriesColors
import page.zepto.finto.core.designsystem.theme.tabular
import kotlin.math.abs

/** A round initial for a merchant, tinted by its name so a row is recognisable at a glance. */
@Composable
internal fun MerchantMark(name: String, modifier: Modifier = Modifier) {
    val color = SeriesColors[abs(name.lowercase().hashCode()) % SeriesColors.size]
    Box(
        modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // Pulled towards the body colour so pale series colours still read on a light surface.
            color = lerp(color, MaterialTheme.colorScheme.onSurface, 0.35f),
        )
    }
}

/** A small label: a cadence, a status, a confidence. */
@Composable
internal fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A labelled figure that may hold one line per currency. */
@Composable
internal fun FigureTile(label: String, lines: List<String>, modifier: Modifier = Modifier, muted: Boolean = false) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val style = MaterialTheme.typography.titleMedium.tabular().copy(
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            lines.forEach { line ->
                // A long figure steps down in size rather than losing digits to an ellipsis.
                BasicText(
                    line,
                    style = style,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
                )
            }
        }
    }
}

/** First-load placeholder: a hero block and a few list rows. */
@Composable
internal fun ListSkeleton(modifier: Modifier = Modifier, hero: Boolean = true, rows: Int = 6) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (hero) {
            SkeletonBlock(Modifier.fillMaxWidth(), height = 112.dp)
            Spacer(Modifier.height(4.dp))
        }
        repeat(rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.size(40.dp), height = 40.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.6f), height = 14.dp)
                    SkeletonBlock(Modifier.fillMaxWidth(0.35f), height = 12.dp)
                }
                Spacer(Modifier.width(16.dp))
                SkeletonBlock(Modifier.width(64.dp), height = 14.dp)
            }
        }
    }
}

/** Shows [notice] once in [host], then reports it shown. */
@Composable
internal fun NoticeEffect(notice: String?, host: SnackbarHostState, onShown: () -> Unit) {
    LaunchedEffect(notice) {
        if (notice != null) {
            host.showSnackbar(notice)
            onShown()
        }
    }
}

internal fun plural(count: Int, one: String, many: String = "${one}s") = "$count ${if (count == 1) one else many}"
