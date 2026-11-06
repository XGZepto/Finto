package page.zepto.finto.feature.accounts

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.OpenLedger

@Serializable
data object AccountsDestination

/** One account, or a product's subaccounts together when [group] is set ([id] is then its balance group). */
@Serializable
data class AccountDetailDestination(val id: String, val group: Boolean = false)

fun NavGraphBuilder.accountsScreen(onOpenAccount: (accountId: String) -> Unit, onOpenGroup: (key: String) -> Unit) {
    composable<AccountsDestination> { AccountsRoute(onOpenAccount = onOpenAccount, onOpenGroup = onOpenGroup) }
}

/**
 * Account and product pages. Moving between siblings replaces the page rather
 * than stacking one per tap.
 */
fun NavGraphBuilder.accountDetailScreen(
    navController: NavController,
    onOpenLedger: OpenLedger,
    onOpenTransaction: (filter: LedgerFilter, title: String?, id: String) -> Unit,
) {
    composable<AccountDetailDestination> {
        AccountDetailRoute(
            onBack = navController::popBackStack,
            onOpenLedger = onOpenLedger,
            onOpenTransaction = onOpenTransaction,
            onOpenAccount = { id -> navController.navigate(AccountDetailDestination(id)) },
            onSwitchAccount = { id ->
                navController.navigate(AccountDetailDestination(id)) {
                    popUpTo<AccountDetailDestination> { inclusive = true }
                }
            },
            onOpenGroup = { key -> navController.navigate(AccountDetailDestination(key, group = true)) },
        )
    }
}
