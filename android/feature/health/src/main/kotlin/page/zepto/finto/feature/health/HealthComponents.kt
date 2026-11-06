package page.zepto.finto.feature.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A short uppercase label on a tinted ground: KEEP, DROP, 12 MONTHS. */
@Composable
internal fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

/** A status dot: green for reconciled, red for a problem. */
@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}
