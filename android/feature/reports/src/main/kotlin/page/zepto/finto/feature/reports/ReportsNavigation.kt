package page.zepto.finto.feature.reports

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import page.zepto.finto.core.model.OpenLedger

@Serializable
data object ReportsDestination

@Serializable
data object TimelineDestination

fun NavGraphBuilder.reportsScreen(onOpenLedger: OpenLedger) {
    composable<ReportsDestination> { Placeholder("Reports", onBack = null) }
}

fun NavGraphBuilder.timelineScreen(onBack: () -> Unit, onOpenLedger: OpenLedger) {
    composable<TimelineDestination> { Placeholder("Timeline", onBack = onBack) }
}
