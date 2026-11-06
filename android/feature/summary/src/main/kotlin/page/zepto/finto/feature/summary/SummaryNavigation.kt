package page.zepto.finto.feature.summary

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import page.zepto.finto.core.model.OpenLedger

@Serializable
data object SummaryDestination

fun NavGraphBuilder.summaryScreen(onOpenLedger: OpenLedger, onOpenImport: () -> Unit) {
    composable<SummaryDestination> { SummaryRoute(onOpenLedger = onOpenLedger, onOpenImport = onOpenImport) }
}
