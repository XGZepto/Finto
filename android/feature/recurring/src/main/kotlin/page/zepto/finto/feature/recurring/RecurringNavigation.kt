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

/** Recurring charges, instalment plans and a plan's schedule. */
fun NavGraphBuilder.recurringScreens(navController: NavController, onOpenLedger: OpenLedger) {
    composable<RecurringDestination> { Placeholder("Recurring", onBack = navController::popBackStack) }
    composable<InstallmentsDestination> { Placeholder("Instalments", onBack = navController::popBackStack) }
    composable<InstallmentDestination> { Placeholder("Instalment", onBack = navController::popBackStack) }
}
