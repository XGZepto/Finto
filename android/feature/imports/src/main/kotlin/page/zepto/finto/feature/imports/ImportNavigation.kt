package page.zepto.finto.feature.imports

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object ImportDestination

fun NavGraphBuilder.importScreen(onOpenActivity: () -> Unit) {
    composable<ImportDestination> { ImportRoute(onOpenActivity = onOpenActivity) }
}
