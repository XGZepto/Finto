package page.zepto.finto.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import page.zepto.finto.core.designsystem.theme.DarkMoneyColors
import page.zepto.finto.core.designsystem.theme.FintoDarkColors
import page.zepto.finto.core.designsystem.theme.LocalFintoMoneyColors

/**
 * The brand surface: the splash screen's ink, with the mark's blue and green
 * as two faint glows. Always dark, whatever the app theme, so launching goes
 * from splash to sign-in without a flash.
 */
@Composable
fun BrandSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalFintoMoneyColors provides DarkMoneyColors) {
        MaterialTheme(colorScheme = FintoDarkColors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
          CompositionLocalProvider(LocalContentColor provides FintoDarkColors.onSurface) {
            Box(
                modifier
                    .fillMaxSize()
                    .background(BRAND_INK)
                    .drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(Color(0x335AA9E6), Color.Transparent),
                                center = Offset(size.width * 0.15f, size.height * 0.12f),
                                radius = size.maxDimension * 0.55f,
                            ),
                        )
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(Color(0x264EC98B), Color.Transparent),
                                center = Offset(size.width * 0.95f, size.height * 0.92f),
                                radius = size.maxDimension * 0.5f,
                            ),
                        )
                    },
                content = content,
            )
          }
        }
    }
}

val BRAND_INK = Color(0xFF0D0E10)
