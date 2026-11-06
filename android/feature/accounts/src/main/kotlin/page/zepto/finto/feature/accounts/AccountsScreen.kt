package page.zepto.finto.feature.accounts

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.AnimatedMoneyText
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.ListSkeleton
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.SkeletonBlock
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.Money

@Composable
fun AccountsRoute(
    onOpenAccount: (accountId: String) -> Unit,
    onOpenGroup: (key: String) -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountsScreen(state = state, onRefresh = viewModel::refresh, onOpenAccount = onOpenAccount, onOpenGroup = onOpenGroup)
}

@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onRefresh: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onOpenGroup: (String) -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val hasContent = state.groups.isNotEmpty()
    LaunchedEffect(state.error, hasContent) {
        if (state.error != null && hasContent) snackbar.showSnackbar(state.error)
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { FintoTopBar("Accounts", scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val phase = when {
            state.loading -> 0
            state.error != null && !hasContent -> 1
            !hasContent -> 2
            else -> 3
        }
        AnimatedContent(phase, transitionSpec = { crossfade() }, label = "accounts", modifier = Modifier.padding(padding)) { shown ->
            when (shown) {
                0 -> Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth().padding(horizontal = 16.dp), height = 180.dp)
                    ListSkeleton(Modifier.padding(top = 16.dp), rows = 5)
                }
                1 -> ErrorState(state.error.orEmpty(), onRetry = onRefresh)
                2 -> MessageState(Icons.Outlined.AccountBalance, "No accounts yet", "Import a statement to add one.")
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    AccountsContent(state, onOpenAccount, onOpenGroup)
                }
            }
        }
    }
}

@Composable
private fun AccountsContent(state: AccountsUiState, onOpenAccount: (String) -> Unit, onOpenGroup: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.netWorth != null || state.settled != null) {
            item("net-worth") { NetWorthCard(state, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
        items(state.groups, key = { it.key }) { group ->
            ProductCard(group, onOpenAccount, onOpenGroup, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).animateItem())
        }
        if (state.flows.isNotEmpty()) {
            item("flow-header") { SectionHeader("Money flow · ${state.currency}") }
            items(state.flows, key = { "flow-${it.accountId}" }) { row ->
                FlowRow(row, onClick = { onOpenAccount(row.accountId) }, Modifier.animateItem())
            }
        }
        if (state.transfers.isNotEmpty()) {
            item("transfers-header") { SectionHeader("Internal transfers") }
            items(state.transfers, key = { "transfer-${it.from}-${it.to}-${it.amount.currency}" }) { transfer ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).animateItem(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(transfer.fromName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "to", modifier = Modifier.padding(horizontal = 8.dp).size(16.dp))
                    Text(transfer.toName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    MoneyText(transfer.amount, whole = true, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun NetWorthCard(state: AccountsUiState, modifier: Modifier = Modifier) {
    val onCard = MaterialTheme.colorScheme.onPrimaryContainer
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val settled = state.settled
            Text(
                if (settled != null) "Net worth · end of ${DisplayDates.short(settled.asOf)}" else "Net worth",
                style = MaterialTheme.typography.titleSmall,
                color = onCard,
            )
            (settled?.worth ?: state.netWorth)?.let { AnimatedMoneyText(it, style = MaterialTheme.typography.displaySmall, color = onCard) }
            if (settled != null && state.netWorth != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Today ", style = MaterialTheme.typography.bodySmall, color = onCard.copy(alpha = 0.75f))
                    MoneyText(state.netWorth, whole = true, style = MaterialTheme.typography.bodySmall, color = onCard)
                    if (settled.limitedBy.isNotEmpty()) {
                        Text(
                            " · waiting on ${settled.limitedBy.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = onCard.copy(alpha = 0.75f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 8.dp)) {
                state.assets?.let { Figure("Assets", it) }
                state.liabilities?.let { Figure("Liabilities", it) }
            }
            if (state.unconvertible.isNotEmpty()) {
                Text(
                    "Missing rates for ${state.unconvertible.joinToString()}; those balances are excluded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = onCard,
                )
            }
            if (state.series.size > 1) {
                NetWorthChart(state.series, Modifier.padding(top = 12.dp), color = onCard)
            }
        }
    }
}

@Composable
private fun Figure(label: String, money: Money) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
        MoneyText(money, whole = true, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** A product: one tappable account, or a group header with its subaccounts underneath. */
@Composable
private fun ProductCard(group: ProductGroup, onOpenAccount: (String) -> Unit, onOpenGroup: (String) -> Unit, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = modifier.fillMaxWidth()) {
        if (group.single) {
            val row = group.children.single()
            AccountLine(
                eyebrow = listOfNotNull(Labels.humanize(row.account.accountType), status(row)).joinToString(" · "),
                name = row.account.displayName,
                totals = row.positions.map { it.balance },
                onClick = { onOpenAccount(row.account.id) },
                emphasised = true,
            )
        } else {
            AccountLine(
                eyebrow = "${group.children.size} accounts",
                name = group.name,
                totals = group.totals,
                onClick = { onOpenGroup(group.key) },
                emphasised = true,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            group.children.forEach { row ->
                AccountLine(
                    eyebrow = status(row),
                    name = row.account.displayName,
                    totals = row.positions.map { it.balance },
                    onClick = { onOpenAccount(row.account.id) },
                    emphasised = false,
                )
            }
        }
    }
}

private fun status(row: AccountRow): String? = when {
    row.account.closedOn != null -> "Closed"
    !row.account.watchStatements -> "Idle"
    else -> null
}

@Composable
private fun AccountLine(eyebrow: String?, name: String, totals: List<Money>, onClick: () -> Unit, emphasised: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (emphasised) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            eyebrow?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                name,
                style = if (emphasised) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (totals.isEmpty()) {
                Text("—", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            totals.forEach { money ->
                MoneyText(
                    money,
                    style = if (emphasised) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (emphasised) FontWeight.Medium else null,
                    color = signColor(money),
                )
            }
        }
    }
}

@Composable
internal fun signColor(money: Money) = when {
    money.amount < 0 -> FintoThemeTokens.money.negative
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun FlowRow(row: AccountFlowRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        MoneyText(row.inflow, whole = true, style = MaterialTheme.typography.bodyMedium, color = FintoThemeTokens.money.positive, signed = true)
        Spacer(Modifier.width(12.dp))
        MoneyText(row.outflow, whole = true, style = MaterialTheme.typography.bodyMedium)
    }
}
