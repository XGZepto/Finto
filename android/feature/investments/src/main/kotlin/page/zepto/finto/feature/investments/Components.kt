package page.zepto.finto.feature.investments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.component.SkeletonBlock

/** A small label: a document type, a parser. */
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

/** First-load placeholder: the valuation card, a chart and a few rows. */
@Composable
internal fun InvestmentsSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBlock(Modifier.fillMaxWidth(), height = 128.dp)
        SkeletonBlock(Modifier.fillMaxWidth(), height = 180.dp)
        repeat(4) {
            Row {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.7f), height = 14.dp)
                    SkeletonBlock(Modifier.fillMaxWidth(0.4f), height = 12.dp)
                }
                Spacer(Modifier.width(16.dp))
                SkeletonBlock(Modifier.width(72.dp), height = 14.dp)
            }
        }
    }
}

internal fun plural(count: Int, one: String, many: String = "${one}s") = "$count ${if (count == 1) one else many}"
