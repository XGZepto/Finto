package page.zepto.finto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import page.zepto.finto.core.data.IncomingFiles
import page.zepto.finto.core.data.repository.SessionState
import page.zepto.finto.feature.accounts.accountsScreen
import page.zepto.finto.feature.auth.LoginRoute
import page.zepto.finto.feature.imports.importScreen
import page.zepto.finto.feature.settings.settingsScreen
import page.zepto.finto.feature.summary.SummaryDestination
import page.zepto.finto.feature.summary.summaryScreen
import page.zepto.finto.feature.transactions.ActivityDestination
import page.zepto.finto.feature.transactions.activityScreen

@Composable
fun FintoApp(session: SessionState, appVersion: String, incomingFiles: IncomingFiles) {
    when (session) {
        // The splash screen covers this; draw only the background behind it.
        SessionState.Restoring -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        is SessionState.SignedOut -> LoginRoute(notice = session.notice)
        is SessionState.SignedIn -> FintoShell(appVersion, incomingFiles)
    }
}

/**
 * Five top-level destinations in a bottom bar on phones and a rail on tablets
 * and foldables, chosen by window size.
 */
@Composable
private fun FintoShell(appVersion: String, incomingFiles: IncomingFiles) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())

    val incoming by incomingFiles.file.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        if (incoming != null) navController.navigateToTopLevel(TopLevelDestination.Import)
    }

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = current?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true
                item(
                    selected = selected,
                    onClick = { navController.navigateToTopLevel(destination) },
                    icon = { Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null) },
                    label = { Text(destination.label) },
                )
            }
        },
    ) {
        // The bottom bar already pads for the gesture area; screens must not pad for it again.
        val consumed = if (layoutType == NavigationSuiteType.NavigationBar) {
            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
        } else {
            WindowInsets(0)
        }
        NavHost(
            navController = navController,
            startDestination = SummaryDestination,
            modifier = Modifier.consumeWindowInsets(consumed),
        ) {
            summaryScreen(
                onOpenCategory = { category, label, from ->
                    navController.navigate(
                        ActivityDestination(category = category, categoryLabel = label, from = from, spendingOnly = true),
                    )
                },
            )
            activityScreen(onBack = navController::popBackStack)
            accountsScreen(
                onOpenAccount = { id, name -> navController.navigate(ActivityDestination(accountId = id, accountName = name)) },
            )
            importScreen(onOpenActivity = { navController.navigateToTopLevel(TopLevelDestination.Activity) })
            settingsScreen(appVersion = appVersion)
        }
    }
}

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
