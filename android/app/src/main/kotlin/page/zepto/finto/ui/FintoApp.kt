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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import page.zepto.finto.core.data.IncomingFiles
import page.zepto.finto.core.data.repository.SessionState
import androidx.navigation.compose.composable
import page.zepto.finto.core.designsystem.theme.FintoMotion
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.OpenLedger
import page.zepto.finto.feature.health.ReviewDestination
import page.zepto.finto.feature.accounts.AccountDetailDestination
import page.zepto.finto.feature.accounts.accountDetailScreen
import page.zepto.finto.feature.accounts.accountsScreen
import page.zepto.finto.feature.ask.askScreens
import page.zepto.finto.feature.health.healthScreens
import page.zepto.finto.feature.imports.ImportDestination
import page.zepto.finto.feature.investments.investmentsScreens
import page.zepto.finto.feature.recurring.InstallmentDestination
import page.zepto.finto.feature.recurring.recurringScreens
import page.zepto.finto.feature.reports.reportsScreen
import page.zepto.finto.feature.reports.timelineScreen
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
 * Five tabs in a bottom bar on phones and a rail on tablets and foldables,
 * chosen by window size. Switching tabs fades through; drilling in slides
 * along the shared axis, and back reverses it.
 */
@Composable
private fun FintoShell(appVersion: String, incomingFiles: IncomingFiles) {
    val navController = rememberNavController()
    val entries by navController.currentBackStack.collectAsStateWithLifecycle()
    val selectedTab = entries.selectedTab()
    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())

    val incoming by incomingFiles.file.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        if (incoming != null) {
            navController.navigateToTopLevel(TopLevelDestination.More)
            navController.navigate(ImportDestination) { launchSingleTop = true }
        }
    }

    val openLedger: OpenLedger = { filter, title -> navController.navigate(ActivityDestination.of(filter, title)) }
    val openAccount: (String) -> Unit = { id -> navController.navigate(AccountDetailDestination(id)) }
    val openTransaction: (LedgerFilter, String?, String) -> Unit = { filter, title, id ->
        navController.navigate(ActivityDestination.of(filter, title, txn = id))
    }

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == selectedTab
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
            enterTransition = { if (targetState.isTabRoot()) FintoMotion.fadeThroughIn() else FintoMotion.sharedAxisIn(forward = true) },
            exitTransition = { if (targetState.isTabRoot()) FintoMotion.fadeThroughOut() else FintoMotion.sharedAxisOut(forward = true) },
            popEnterTransition = { if (initialState.isTabRoot()) FintoMotion.fadeThroughIn() else FintoMotion.sharedAxisIn(forward = false) },
            popExitTransition = { if (initialState.isTabRoot()) FintoMotion.fadeThroughOut() else FintoMotion.sharedAxisOut(forward = false) },
        ) {
            summaryScreen(
                onOpenLedger = openLedger,
                onOpenImport = {
                    navController.navigateToTopLevel(TopLevelDestination.More)
                    navController.navigate(ImportDestination) { launchSingleTop = true }
                },
            )
            activityScreen(
                onBack = navController::popBackStack,
                onOpenLedger = openLedger,
                onOpenAccount = openAccount,
                onOpenInstallment = { id -> navController.navigate(InstallmentDestination(id)) },
            )
            reportsScreen(onOpenLedger = openLedger)
            accountsScreen(
                onOpenAccount = openAccount,
                onOpenGroup = { key -> navController.navigate(AccountDetailDestination(key, group = true)) },
            )
            accountDetailScreen(navController, onOpenLedger = openLedger, onOpenTransaction = openTransaction)
            composable<MoreDestination> { MoreScreen(onOpen = { route -> navController.navigate(route) { launchSingleTop = true } }) }
            importScreen(
                onBack = navController::popBackStack,
                onOpenLedger = openLedger,
                onOpenTransaction = openTransaction,
                onOpenAccount = openAccount,
                onOpenMatching = { navController.navigate(ReviewDestination) { launchSingleTop = true } },
            )
            settingsScreen(appVersion = appVersion, onBack = navController::popBackStack)
            recurringScreens(navController, onOpenLedger = openLedger)
            investmentsScreens(navController)
            healthScreens(navController, onOpenLedger = openLedger, onOpenAccount = openAccount)
            timelineScreen(onBack = navController::popBackStack, onOpenLedger = openLedger)
            askScreens(navController, onOpenLedger = openLedger)
        }
    }
}

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    // Tapping the tab you are on returns to its root, as on the web.
    val stack = currentBackStack.value
    if (stack.selectedTab() == destination && stack.lastOrNull()?.isTabRoot() == false) {
        if (popBackStack(destination.routeClass, inclusive = false)) return
    }
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
