package page.zepto.finto.feature.recurring

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import page.zepto.finto.core.model.OpenLedger

@Serializable
data object RecurringDestination

@Serializable
data object InstallmentsDestination

@Serializable
data class InstallmentDestination(val id: String)

/** One recurring merchant, by its normalised name ([Commitment.key]). */
@Serializable
data class RecurringItemDestination(val key: String)

/** Recurring charges, instalment plans and a plan's schedule. */
fun NavGraphBuilder.recurringScreens(navController: NavController, onOpenLedger: OpenLedger) {
    composable<RecurringDestination> {
        RecurringRoute(
            onBack = navController::popBackStack,
            onOpenCommitment = { key -> navController.navigate(RecurringItemDestination(key)) },
            onOpenPlan = { id -> navController.navigate(InstallmentDestination(id)) },
            onOpenPlans = { navController.navigate(InstallmentsDestination) { launchSingleTop = true } },
        )
    }
    composable<RecurringItemDestination> {
        RecurringDetailRoute(onBack = navController::popBackStack, onOpenLedger = onOpenLedger)
    }
    composable<InstallmentsDestination> {
        InstallmentsRoute(
            onBack = navController::popBackStack,
            onOpenPlan = { id -> navController.navigate(InstallmentDestination(id)) },
        )
    }
    composable<InstallmentDestination> {
        InstallmentDetailRoute(onBack = navController::popBackStack, onOpenLedger = onOpenLedger)
    }
}
