package page.zepto.finto.feature.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.LoadingState
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.Position

@Composable
fun AccountsRoute(
    onOpenAccount: (accountId: String, accountName: String) -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountsScreen(state = state, onRefresh = viewModel::refresh, onOpenAccount = onOpenAccount)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onRefresh: () -> Unit,
    onOpenAccount: (String, String) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("Accounts") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.error != null && state.groups.isEmpty() -> ErrorState(state.error, Modifier.padding(padding), onRetry = onRefresh)
            state.groups.isEmpty() -> MessageState(
                Icons.Outlined.AccountBalance,
                "No accounts yet",
                "Import a statement to add one.",
                Modifier.padding(padding),
            )
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    state.netWorth?.let { worth ->
                        item("net-worth") {
                            ElevatedCard(
                                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "Net worth · ${state.currency}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    MoneyText(
                                        worth,
                                        whole = true,
                                        style = MaterialTheme.typography.displaySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    if (state.unconvertible.isNotEmpty()) {
                                        Text(
                                            "Excludes ${state.unconvertible.joinToString()}: no rate into ${state.currency}.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    state.groups.forEach { group ->
                        item("header-${group.type}") {
                            SectionHeader(
                                Labels.accountType(group.type),
                                trailing = {
                                    group.total?.let {
                                        MoneyText(it, whole = true, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                                    }
                                },
                            )
                        }
                        items(group.positions, key = { "${it.accountId}-${it.currency}" }) { position ->
                            PositionRow(position, state.currency, onClick = { onOpenAccount(position.accountId, position.accountName) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionRow(position: Position, currency: String, onClick: () -> Unit) {
    // Where the figure comes from: a printed statement balance, a valuation, or
    // the running total of imported rows that no statement has confirmed yet.
    val basis = when (position.basis) {
        "statement" -> "Statement"
        "investment_snapshot" -> "Valuation"
        else -> "Running total"
    }
    val asOf = (position.basisDate ?: position.lastDate)?.let { " · ${DisplayDates.short(it)}" }.orEmpty()
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "Show transactions", onClick = onClick),
        headlineContent = { Text(position.accountName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${position.currency} · $basis$asOf", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(position.balance, style = MaterialTheme.typography.bodyLarge)
                position.balanceConverted
                    ?.takeIf { it.ok && position.currency != currency }
                    ?.let {
                        MoneyText(
                            it.asMoney(),
                            whole = true,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        },
    )
}
