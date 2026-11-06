package page.zepto.finto.feature.ask

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

/**
 * Lifts a bottom-docked element clear of the keyboard. The app's navigation
 * bar already sits below it, so only the part of the keyboard that rises above
 * this element's own bottom edge is padded for; plain `imePadding` would also
 * count the navigation bar the keyboard covers and float the field too high.
 */
@Composable
internal fun Modifier.keyboardPadding(): Modifier {
    val density = LocalDensity.current
    val keyboard = WindowInsets.ime.getBottom(density)
    var below by remember { mutableIntStateOf(0) }
    val lift = with(density) { (keyboard - below).coerceAtLeast(0).toDp() }
    return onGloballyPositioned { coordinates ->
        val root = coordinates.findRootCoordinates()
        val bottom = root.localBoundingBoxOf(coordinates, clipBounds = false).bottom
        below = (root.size.height - bottom).roundToInt().coerceAtLeast(0)
    }.padding(bottom = lift)
}
