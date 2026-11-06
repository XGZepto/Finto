package page.zepto.finto.feature.ask

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import page.zepto.finto.core.model.OpenLedger

@Serializable
data object AskDestination

/** Questions to the ledger in plain language, answered with figures and rows. */
fun NavGraphBuilder.askScreens(navController: NavController, onOpenLedger: OpenLedger) {
    composable<AskDestination> { Placeholder("Ask", onBack = navController::popBackStack) }
}
