package page.zepto.finto.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The Finto mark from the web app's favicon: the F, a plus, and a corner. */
val FintoMarkVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "FintoMark",
        defaultWidth = 64.dp,
        defaultHeight = 64.dp,
        viewportWidth = 64f,
        viewportHeight = 64f,
    ).apply {
        addPath(
            pathData = addPathNodes("M10,0 h44 a10,10 0 0 1 10,10 v44 a10,10 0 0 1 -10,10 h-44 a10,10 0 0 1 -10,-10 v-44 a10,10 0 0 1 10,-10z"),
            fill = SolidColor(Color(0xFF0D0E10)),
        )
        path(fill = SolidColor(Color(0xFFE9EAEC))) {
            moveTo(17f, 14f); horizontalLineToRelative(31f); verticalLineToRelative(8f)
            horizontalLineTo(27f); verticalLineToRelative(9f); horizontalLineToRelative(17f)
            verticalLineToRelative(8f); horizontalLineTo(27f); verticalLineToRelative(18f)
            horizontalLineTo(17f); close()
        }
        path(stroke = SolidColor(Color(0xFF5AA9E6)), strokeLineWidth = 3f, strokeLineCap = StrokeCap.Butt) {
            moveTo(48f, 8f); verticalLineToRelative(12f); moveTo(42f, 14f); horizontalLineToRelative(12f)
        }
        path(stroke = SolidColor(Color(0xFF4EC98B)), strokeLineWidth = 3f) {
            moveTo(43f, 49f); horizontalLineToRelative(9f); verticalLineToRelative(9f)
        }
    }.build()
}

@Composable
fun FintoMark(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Image(FintoMarkVector, contentDescription = null, modifier = modifier.size(size))
}
