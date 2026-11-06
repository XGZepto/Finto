package page.zepto.finto.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalFintoMoneyColors = staticCompositionLocalOf { DarkMoneyColors }

/**
 * Material 3 with Material You colour on Android 12+ when [dynamicColor] is
 * on, and Finto's own palette otherwise.
 */
@Composable
fun FintoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FintoDarkColors
        else -> FintoLightColors
    }
    CompositionLocalProvider(
        LocalFintoMoneyColors provides if (darkTheme) DarkMoneyColors else LightMoneyColors,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = FintoTypography,
            shapes = FintoShapes,
            content = content,
        )
    }
}

object FintoThemeTokens {
    val money: FintoMoneyColors
        @Composable @ReadOnlyComposable
        get() = LocalFintoMoneyColors.current
}
