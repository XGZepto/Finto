package page.zepto.finto.feature.imports

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import page.zepto.finto.core.model.OpenLedger

@Serializable
data object ImportDestination

fun NavGraphBuilder.importScreen(onBack: () -> Unit, onOpenLedger: OpenLedger) {
    composable<ImportDestination> { ImportRoute(onBack = onBack, onOpenLedger = onOpenLedger) }
}
