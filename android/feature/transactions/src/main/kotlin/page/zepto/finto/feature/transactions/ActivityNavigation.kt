package page.zepto.finto.feature.transactions

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.activityScreen(onBack: () -> Unit) {
    composable<ActivityDestination> { ActivityRoute(onBack = onBack) }
}
