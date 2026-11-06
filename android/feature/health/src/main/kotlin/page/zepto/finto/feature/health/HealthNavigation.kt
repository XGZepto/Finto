package page.zepto.finto.feature.health

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import page.zepto.finto.core.model.OpenLedger

@Serializable
data object ReviewDestination

@Serializable
data object IntegrityDestination

/** Ledger health: matching suggestions to accept or reject, and the integrity report. */
fun NavGraphBuilder.healthScreens(
    navController: NavController,
    onOpenLedger: OpenLedger,
    onOpenAccount: (accountId: String) -> Unit,
) {
    composable<ReviewDestination> {
        ReviewRoute(onBack = navController::popBackStack, onOpenLedger = onOpenLedger)
    }
    composable<IntegrityDestination> {
        IntegrityRoute(onBack = navController::popBackStack, onOpenLedger = onOpenLedger, onOpenAccount = onOpenAccount)
    }
}
