package page.zepto.finto.feature.summary

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object SummaryDestination

fun NavGraphBuilder.summaryScreen(onOpenCategory: (category: String, label: String, from: String) -> Unit) {
    composable<SummaryDestination> { SummaryRoute(onOpenCategory = onOpenCategory) }
}
