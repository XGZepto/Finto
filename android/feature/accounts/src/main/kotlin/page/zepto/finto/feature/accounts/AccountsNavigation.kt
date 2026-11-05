package page.zepto.finto.feature.accounts

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object AccountsDestination

fun NavGraphBuilder.accountsScreen(onOpenAccount: (accountId: String, accountName: String) -> Unit) {
    composable<AccountsDestination> { AccountsRoute(onOpenAccount = onOpenAccount) }
}
