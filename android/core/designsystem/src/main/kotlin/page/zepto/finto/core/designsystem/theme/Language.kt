package page.zepto.finto.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import page.zepto.finto.core.model.Translations

/** The account's language preference, `en` or `zh-Hant`. */
val LocalFintoLanguage = staticCompositionLocalOf { Translations.ENGLISH }

/** [english], or its Traditional Chinese where the web translates it. */
@Composable
@ReadOnlyComposable
fun tr(english: String): String = Translations.text(english, LocalFintoLanguage.current)
