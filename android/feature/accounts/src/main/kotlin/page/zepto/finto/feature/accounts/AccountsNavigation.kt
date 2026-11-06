package page.zepto.finto.feature.accounts

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import page.zepto.finto.core.model.OpenLedger

@Serializable
data object AccountsDestination

/** One account, or a balance group when [id] names one. */
@Serializable
data class AccountDetailDestination(val id: String)

fun NavGraphBuilder.accountsScreen(onOpenAccount: (accountId: String) -> Unit) {
    composable<AccountsDestination> { AccountsRoute(onOpenAccount = { id, _ -> onOpenAccount(id) }) }
}

fun NavGraphBuilder.accountDetailScreen(
    onBack: () -> Unit,
    onOpenLedger: OpenLedger,
    onOpenAccount: (accountId: String) -> Unit,
) {
    composable<AccountDetailDestination> { AccountDetailRoute(onBack = onBack, onOpenLedger = onOpenLedger, onOpenAccount = onOpenAccount) }
}
