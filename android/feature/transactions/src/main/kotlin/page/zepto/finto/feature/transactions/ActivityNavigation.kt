package page.zepto.finto.feature.transactions

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import page.zepto.finto.core.model.OpenLedger

fun NavGraphBuilder.activityScreen(
    onBack: () -> Unit,
    onOpenLedger: OpenLedger,
    onOpenAccount: (String) -> Unit,
    onOpenInstallment: (String) -> Unit,
) {
    composable<ActivityDestination> {
        ActivityRoute(onBack = onBack, onOpenLedger = onOpenLedger, onOpenAccount = onOpenAccount, onOpenInstallment = onOpenInstallment)
    }
}
