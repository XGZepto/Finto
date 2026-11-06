package page.zepto.finto.feature.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object SettingsDestination

fun NavGraphBuilder.settingsScreen(appVersion: String, onBack: () -> Unit) {
    composable<SettingsDestination> { SettingsRoute(appVersion = appVersion, onBack = onBack) }
}
