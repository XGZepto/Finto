package page.zepto.finto.feature.investments

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object InvestmentsDestination

/** Investment snapshots, holdings and contributions, and the MPF statement import. */
fun NavGraphBuilder.investmentsScreens(navController: NavController) {
    composable<InvestmentsDestination> { Placeholder("Investments", onBack = navController::popBackStack) }
}
