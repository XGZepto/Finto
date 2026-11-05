package page.zepto.finto.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The web app's palette: near-black ink, a cool blue accent, green for money
// in and red for money out. Used when dynamic colour is off or unavailable.

internal val FintoDarkColors = darkColorScheme(
    primary = Color(0xFF5AA9E6),
    onPrimary = Color(0xFF00243D),
    primaryContainer = Color(0xFF1A4A6E),
    onPrimaryContainer = Color(0xFFCFE5FF),
    secondary = Color(0xFF4EC98B),
    onSecondary = Color(0xFF00391F),
    secondaryContainer = Color(0xFF14513A),
    onSecondaryContainer = Color(0xFFB4F1CB),
    // Amber: reminders and warnings (an overdue statement), not errors.
    tertiary = Color(0xFFE6A85A),
    onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF4A3414),
    onTertiaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFE9EAEC),
    surface = Color(0xFF0B0C0E),
    onSurface = Color(0xFFE9EAEC),
    surfaceVariant = Color(0xFF2A2D31),
    onSurfaceVariant = Color(0xFFA4AAB1),
    surfaceContainerLowest = Color(0xFF070809),
    surfaceContainerLow = Color(0xFF111214),
    surfaceContainer = Color(0xFF15171A),
    surfaceContainerHigh = Color(0xFF1C1E21),
    surfaceContainerHighest = Color(0xFF24272B),
    outline = Color(0xFF5F656C),
    outlineVariant = Color(0xFF31353A),
    error = Color(0xFFF0736C),
    onError = Color(0xFF4A0A06),
    errorContainer = Color(0xFF6A1F1A),
    onErrorContainer = Color(0xFFFFDAD6),
)

internal val FintoLightColors = lightColorScheme(
    primary = Color(0xFF22679C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D35),
    secondary = Color(0xFF1F7A4D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB4F1CB),
    onSecondaryContainer = Color(0xFF002112),
    tertiary = Color(0xFF8A5100),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE6C4),
    onTertiaryContainer = Color(0xFF2C1700),
    background = Color(0xFFF4F4F2),
    onBackground = Color(0xFF17181A),
    surface = Color(0xFFF4F4F2),
    onSurface = Color(0xFF17181A),
    surfaceVariant = Color(0xFFE2E2DE),
    onSurfaceVariant = Color(0xFF494C50),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFEFEC),
    surfaceContainer = Color(0xFFE9E9E6),
    surfaceContainerHigh = Color(0xFFE3E3E0),
    surfaceContainerHighest = Color(0xFFDDDDDA),
    outline = Color(0xFF888C92),
    outlineVariant = Color(0xFFDCDCD8),
    error = Color(0xFFC0392F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Money in and money out keep their meaning under every scheme, dynamic included. */
data class FintoMoneyColors(
    val positive: Color,
    val negative: Color,
    val positiveContainer: Color,
    val negativeContainer: Color,
)

internal val DarkMoneyColors = FintoMoneyColors(
    positive = Color(0xFF4EC98B),
    negative = Color(0xFFF0736C),
    positiveContainer = Color(0x294EC98B),
    negativeContainer = Color(0x29F0736C),
)

internal val LightMoneyColors = FintoMoneyColors(
    positive = Color(0xFF1F7A4D),
    negative = Color(0xFFC0392F),
    positiveContainer = Color(0x1F1F7A4D),
    negativeContainer = Color(0x1FC0392F),
)

/** Distinct, colour-blind-safe-ish series for ranked buckets and charts. */
val SeriesColors = listOf(
    Color(0xFF5AA9E6),
    Color(0xFF4EC98B),
    Color(0xFFE6A85A),
    Color(0xFFC8B6F2),
    Color(0xFFF0736C),
    Color(0xFF6CD4D0),
    Color(0xFF9AA0A6),
)
