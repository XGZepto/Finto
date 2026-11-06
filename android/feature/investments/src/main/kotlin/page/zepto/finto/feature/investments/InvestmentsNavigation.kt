package page.zepto.finto.feature.investments

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object InvestmentsDestination

/** Import a bundle of HSBC MPF statement PDFs. */
@Serializable
data object MpfImportDestination

/** Set on the investments entry when a bundle was imported, so it reloads on return. */
private const val IMPORTED = "mpf_imported"

/** Investment snapshots, holdings and contributions, and the MPF statement import. */
fun NavGraphBuilder.investmentsScreens(navController: NavController) {
    composable<InvestmentsDestination> { entry ->
        val viewModel: InvestmentsViewModel = hiltViewModel()
        val imported by entry.savedStateHandle.getStateFlow(IMPORTED, false).collectAsStateWithLifecycle()
        LaunchedEffect(imported) {
            if (imported) {
                entry.savedStateHandle[IMPORTED] = false
                viewModel.refresh()
            }
        }
        InvestmentsRoute(
            onBack = navController::popBackStack,
            onImport = { navController.navigate(MpfImportDestination) { launchSingleTop = true } },
            viewModel = viewModel,
        )
    }
    composable<MpfImportDestination> {
        MpfImportRoute(
            onBack = navController::popBackStack,
            onImported = {
                val previous = navController.previousBackStackEntry
                if (previous?.destination?.hasRoute<InvestmentsDestination>() == true) {
                    previous.savedStateHandle[IMPORTED] = true
                    navController.popBackStack()
                } else {
                    navController.navigate(InvestmentsDestination) {
                        popUpTo<MpfImportDestination> { inclusive = true }
                    }
                }
            },
        )
    }
}
