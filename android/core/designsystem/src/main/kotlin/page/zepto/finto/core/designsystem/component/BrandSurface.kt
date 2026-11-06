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
import androidx.compose.ui.graphics.Color
import page.zepto.finto.core.designsystem.theme.DarkMoneyColors
import page.zepto.finto.core.designsystem.theme.FintoDarkColors
import page.zepto.finto.core.designsystem.theme.LocalFintoMoneyColors

/**
 * The brand surface: pure black, as the splash screen is, so launching goes
 * from splash to sign-in without a flash. Always dark, whatever the app theme.
 */
@Composable
fun BrandSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalFintoMoneyColors provides DarkMoneyColors) {
        MaterialTheme(colorScheme = FintoDarkColors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(LocalContentColor provides FintoDarkColors.onSurface) {
                Box(modifier.fillMaxSize().background(BRAND_INK), content = content)
            }
        }
    }
}

val BRAND_INK = Color.Black
