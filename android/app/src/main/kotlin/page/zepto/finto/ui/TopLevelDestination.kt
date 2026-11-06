package page.zepto.finto.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import page.zepto.finto.feature.accounts.AccountDetailDestination
import page.zepto.finto.feature.accounts.AccountsDestination
import page.zepto.finto.feature.ask.AskDestination
import page.zepto.finto.feature.health.IntegrityDestination
import page.zepto.finto.feature.health.ReviewDestination
import page.zepto.finto.feature.imports.ImportDestination
import page.zepto.finto.feature.investments.InvestmentsDestination
import page.zepto.finto.feature.investments.MpfImportDestination
import page.zepto.finto.feature.recurring.InstallmentDestination
import page.zepto.finto.feature.recurring.InstallmentsDestination
import page.zepto.finto.feature.recurring.RecurringDestination
import page.zepto.finto.feature.recurring.RecurringItemDestination
import page.zepto.finto.feature.reports.ReportsDestination
import page.zepto.finto.feature.reports.TimelineDestination
import page.zepto.finto.feature.settings.SettingsDestination
import page.zepto.finto.feature.summary.SummaryDestination
import page.zepto.finto.feature.transactions.ActivityDestination
import kotlin.reflect.KClass

/** The navigation bar, in the web app's order. */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    /** Screens reached from this tab that keep it highlighted. */
    val children: Set<KClass<*>> = emptySet(),
) {
    Summary(SummaryDestination, SummaryDestination::class, "Summary", Icons.Outlined.Insights, Icons.Filled.Insights),
    Activity(
        ActivityDestination(),
        ActivityDestination::class,
        "Activity",
        Icons.AutoMirrored.Outlined.ReceiptLong,
        Icons.AutoMirrored.Filled.ReceiptLong,
    ),
    Reports(ReportsDestination, ReportsDestination::class, "Reports", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    Accounts(
        AccountsDestination,
        AccountsDestination::class,
        "Accounts",
        Icons.Outlined.AccountBalance,
        Icons.Filled.AccountBalance,
        children = setOf(AccountDetailDestination::class),
    ),
    More(
        MoreDestination,
        MoreDestination::class,
        "More",
        Icons.Outlined.MoreHoriz,
        Icons.Filled.MoreHoriz,
        children = setOf(
            RecurringDestination::class,
            RecurringItemDestination::class,
            AskDestination::class,
            ImportDestination::class,
            InstallmentsDestination::class,
            InstallmentDestination::class,
            InvestmentsDestination::class,
            MpfImportDestination::class,
            ReviewDestination::class,
            IntegrityDestination::class,
            TimelineDestination::class,
            SettingsDestination::class,
        ),
    ),
    ;

    fun owns(destination: NavDestination): Boolean =
        destination.hasRoute(routeClass) || children.any { destination.hasRoute(it) }
}

/**
 * A tab's own screen, as opposed to something pushed on top of one. The
 * blotter is both: unscoped it is the Activity tab, scoped it is a drill-down.
 */
fun NavBackStackEntry.isTabRoot(): Boolean {
    val root = TopLevelDestination.entries.firstOrNull { destination.hasRoute(it.routeClass) } ?: return false
    return root != TopLevelDestination.Activity || !toRoute<ActivityDestination>().isScoped
}

/** The tab this screen is itself part of, or null for a drill-down that belongs to where it was opened. */
fun NavBackStackEntry.ownTab(): TopLevelDestination? =
    TopLevelDestination.entries.firstOrNull { tab ->
        tab.owns(destination) && (tab != TopLevelDestination.Activity || isTabRoot())
    }
