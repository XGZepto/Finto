package page.zepto.finto.feature.accounts

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.ListSkeleton
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.ShareBar
import page.zepto.finto.core.designsystem.component.SkeletonBlock
import page.zepto.finto.core.designsystem.component.StatTile
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Drill
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.OpenLedger
import page.zepto.finto.core.model.Position
import page.zepto.finto.core.model.SummaryRow
import page.zepto.finto.core.model.Txn

@Composable
fun AccountDetailRoute(
    onBack: () -> Unit,
    onOpenLedger: OpenLedger,
    onOpenTransaction: (LedgerFilter, String?, String) -> Unit,
    onOpenAccount: (String) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountDetailScreen(
        state = state,
        actions = AccountDetailActions(
            onBack = onBack,
            onRefresh = viewModel::refresh,
            onDrill = { dimension, bucket, label -> onOpenLedger(Drill.into(dimension, bucket, state.scopeFilter), label) },
            onOpenAll = { onOpenLedger(state.scopeFilter.copy(includeTransfers = true), state.title) },
            onOpenTransaction = { txn -> onOpenTransaction(state.scopeFilter.copy(includeTransfers = true), state.title, txn.id) },
            onOpenAccount = onOpenAccount,
            onSwitchAccount = onSwitchAccount,
            onOpenGroup = onOpenGroup,
            onClose = viewModel::close,
            onReopen = viewModel::reopen,
            onWatch = viewModel::watchStatements,
        ),
    )
}

/** Everything the page can do, so the stateless screen keeps a short signature. */
data class AccountDetailActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDrill: (dimension: String, bucket: String, label: String) -> Unit = { _, _, _ -> },
    val onOpenAll: () -> Unit = {},
    val onOpenTransaction: (Txn) -> Unit = {},
    val onOpenAccount: (String) -> Unit = {},
    val onSwitchAccount: (String) -> Unit = {},
    val onOpenGroup: (String) -> Unit = {},
    val onClose: () -> Unit = {},
    val onReopen: () -> Unit = {},
    val onWatch: (Boolean) -> Unit = {},
)

@Composable
fun AccountDetailScreen(state: AccountDetailUiState, actions: AccountDetailActions) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val hasContent = state.title.isNotEmpty() && !state.notFound
    LaunchedEffect(state.error, hasContent) {
        if (state.error != null && hasContent) snackbar.showSnackbar(state.error)
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FintoTopBar(
                title = state.title.ifEmpty { "Account" },
                subtitle = state.subtitle.ifEmpty { null },
                onBack = actions.onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (hasContent) {
                        IconButton(onClick = actions.onOpenAll) {
                            Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = "Open in blotter")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val phase = when {
            state.loading -> 0
            state.notFound -> 1
            state.error != null && !hasContent -> 2
            else -> 3
        }
        AnimatedContent(phase, transitionSpec = { crossfade() }, label = "account", modifier = Modifier.padding(padding)) { shown ->
            when (shown) {
                0 -> Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBlock(Modifier.weight(1f), height = 96.dp)
                        SkeletonBlock(Modifier.weight(1f), height = 96.dp)
                    }
                    ListSkeleton(Modifier.padding(top = 16.dp), rows = 6)
                }
                1 -> MessageState(Icons.Outlined.SearchOff, "Couldn't find this account", "")
                2 -> ErrorState(state.error.orEmpty(), onRetry = actions.onRefresh)
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                        detailContent(state, actions)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.detailContent(state: AccountDetailUiState, actions: AccountDetailActions) {
    val group = state.group
    val row = state.row
    if (group != null) {
        item("group-balance") { GroupBalance(group, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item("subaccounts-header") { SectionHeader("Subaccounts") }
        items(group.children, key = { "child-${it.account.id}" }) { child ->
            SubaccountRow(child, onClick = { actions.onOpenAccount(child.account.id) })
        }
    } else if (row != null) {
        item("balances") { Balances(row.positions) }
        item("strip") {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile("Transactions", state.transactionCount.toString(), Modifier.weight(1f).fillMaxHeight())
                StatTile("Cardholders", state.cardholders.takeIf { it > 0 }?.toString() ?: "—", Modifier.weight(1f).fillMaxHeight())
                val top = state.topCategories.firstOrNull()
                StatTile(
                    "Top spending",
                    top?.let { Labels.humanize(it.bucket) } ?: "—",
                    Modifier.weight(1f).fillMaxHeight(),
                    supporting = top?.let { MoneyFormat.formatWhole(it.spend) },
                )
            }
        }
        state.status?.let { status ->
            item("status") { StatusCard(state, status, actions, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        }
        if (state.siblings.isNotEmpty()) {
            item("siblings") { Siblings(state, actions) }
        }
    }

    if (!state.hasActivity) {
        item("empty") {
            MessageState(
                Icons.Outlined.AccountBalance,
                "No ledger activity for this ${if (group != null) "product" else if (state.parent != null) "subaccount" else "account"} yet",
                "",
            )
        }
        return
    }

    if (!state.flow.isEmpty) {
        item("flow") { MoneyFlow(state, actions) }
    }
    if (state.months.isNotEmpty()) {
        item("months-header") { SectionHeader("Monthly outflow") }
        val peak = state.months.peakSpend()
        items(state.months, key = { "month-${it.bucket}-${it.currency}" }) { month ->
            BarRow(
                label = DisplayDates.shortMonth(month.bucket),
                money = month.spend,
                fraction = month.spend.amount.toFloat() / peak,
                onClick = { actions.onDrill("month", month.bucket, DisplayDates.shortMonth(month.bucket)) },
            )
        }
    }
    if (state.topCategories.isNotEmpty()) {
        item("top-header") { SectionHeader("Top spending") }
        val peak = state.topCategories.peakSpend()
        items(state.topCategories, key = { "category-${it.bucket}-${it.currency}" }) { category ->
            BarRow(
                label = Labels.humanize(category.bucket),
                money = category.spend,
                fraction = category.spend.amount.toFloat() / peak,
                count = category.txnCount,
                onClick = { actions.onDrill("category", category.bucket, Labels.humanize(category.bucket)) },
            )
        }
    }
    if (state.lineages.isNotEmpty()) {
        item("cards-header") { SectionHeader("Cards") }
        items(state.lineages, key = { "card-${it.holder}-${it.numbers.joinToString()}" }) { Lineage(it) }
    }
    if (state.byHolder.isNotEmpty()) {
        item("holders-header") { SectionHeader("Spend by cardholder") }
        items(state.byHolder, key = { "holder-${it.bucket}-${it.currency}" }) { holder ->
            StatRow(holder.bucket.ifBlank { "—" }, holder.spend, holder.txnCount, onClick = { actions.onDrill("cardholder", holder.bucket, holder.bucket) })
        }
    }
    if (state.transfers.isNotEmpty()) {
        item("transfers-header") { SectionHeader("Internal transfers") }
        items(state.transfers, key = { "transfer-${it.from}-${it.to}-${it.amount.currency}" }) { transfer ->
            val outgoing = transfer.from in state.scope
            val peer = if (outgoing) transfer.to else transfer.from
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Open account") { actions.onOpenAccount(peer) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (outgoing) Icons.AutoMirrored.Outlined.ArrowForward else Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = if (outgoing) "To" else "From",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(if (outgoing) transfer.toName else transfer.fromName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                MoneyText(transfer.amount, whole = true, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (state.byKind.isNotEmpty()) {
        item("kinds-header") { SectionHeader("By type") }
        items(state.byKind, key = { "kind-${it.bucket}-${it.currency}" }) { kind ->
            StatRow(Labels.humanize(kind.bucket), kind.spend, kind.txnCount, onClick = { actions.onDrill("kind", kind.bucket, Labels.humanize(kind.bucket)) })
        }
    }
    if (state.recent.isNotEmpty()) {
        item("recent-header") {
            SectionHeader("Recent activity", trailing = { TextButton(onClick = actions.onOpenAll) { Text("View all") } })
        }
        items(state.recent, key = { "txn-${it.id}" }) { txn -> RecentRow(txn, onClick = { actions.onOpenTransaction(txn) }) }
    }
}

@Composable
private fun Balances(positions: List<Position>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (positions.isEmpty()) {
            BalanceCard("Balance", null, "No movement")
        }
        positions.forEach { position ->
            val basis = when (position.basis) {
                "statement" -> "Statement"
                "investment_snapshot" -> "Valuation"
                else -> "Running total"
            }
            BalanceCard(
                "${position.currency} balance",
                position.balance,
                listOfNotNull(basis, position.basisDate?.let { DisplayDates.short(it) }).joinToString(" · "),
            )
        }
    }
}

@Composable
private fun BalanceCard(label: String, money: Money?, supporting: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.widthIn(min = 168.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            if (money != null) {
                MoneyText(money, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            } else {
                Text(supporting, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun GroupBalance(group: ProductGroup, modifier: Modifier = Modifier) {
    val nonZero = group.totals.filterNot { it.isZero() }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Combined balance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (nonZero.isEmpty()) {
                Text(
                    if (group.totals.isEmpty()) "No balance snapshot" else MoneyFormat.format(group.totals.first()),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
            }
            nonZero.forEach { MoneyText(it, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            val empty = group.totals.size - nonZero.size
            if (empty > 0) {
                Text("$empty empty", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun SubaccountRow(row: AccountRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.account.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(Labels.humanize(row.account.accountType), row.cards.takeIf { it > 0 }?.let { "$it card${if (it > 1) "s" else ""}" }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            row.positions.forEach { MoneyText(it.balance, style = MaterialTheme.typography.bodyMedium, color = signColor(it.balance)) }
            if (row.positions.isEmpty()) Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusCard(state: AccountDetailUiState, status: AccountStatus, actions: AccountDetailActions, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (status) {
                    AccountStatus.Closed -> "Closed ${state.account?.closedOn?.let { DisplayDates.medium(it) }.orEmpty()}".trim()
                    AccountStatus.NotWatching -> "Not watching"
                    AccountStatus.Open -> "Open"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                when (status) {
                    AccountStatus.Closed -> "New statements are not expected."
                    AccountStatus.NotWatching -> "Missing statements are not flagged."
                    AccountStatus.Open -> "Missing statements are flagged on Summary."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val enabled = !state.statusBusy
                when (status) {
                    AccountStatus.Closed -> OutlinedButton(onClick = actions.onReopen, enabled = enabled) { Text("Reopen") }
                    AccountStatus.NotWatching -> {
                        OutlinedButton(onClick = { actions.onWatch(true) }, enabled = enabled) { Text("Watch statements") }
                        OutlinedButton(onClick = actions.onClose, enabled = enabled) { Text("Mark closed") }
                    }
                    AccountStatus.Open -> {
                        OutlinedButton(onClick = { actions.onWatch(false) }, enabled = enabled) { Text("Don't expect statements") }
                        OutlinedButton(onClick = actions.onClose, enabled = enabled) { Text("Mark closed") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Siblings(state: AccountDetailUiState, actions: AccountDetailActions) {
    Column {
        SectionHeader(
            state.parent?.name ?: "Subaccounts",
            trailing = state.parent?.let { parent -> { TextButton(onClick = { actions.onOpenGroup(parent.key) }) { Text("All") } } },
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.siblings.forEach { sibling ->
                val current = sibling.account.id == state.account?.id
                FilterChip(
                    selected = current,
                    onClick = { if (!current) actions.onSwitchAccount(sibling.account.id) },
                    label = {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(sibling.account.displayName, style = MaterialTheme.typography.labelLarge)
                            sibling.positions.forEach {
                                Text(MoneyFormat.format(it.balance), style = MaterialTheme.typography.labelSmall.tabular())
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MoneyFlow(state: AccountDetailUiState, actions: AccountDetailActions) {
    Column {
        SectionHeader("Money flow · ${state.flowCurrency}")
        val peak = (state.flow.incoming + state.flow.outgoing).maxOfOrNull { it.amount }?.coerceAtLeast(1L) ?: 1L
        if (state.flow.incoming.isNotEmpty()) {
            FlowLabel("In")
            state.flow.incoming.forEach { node ->
                BarRow(
                    label = node.label,
                    money = Money(node.amount, state.flowCurrency),
                    fraction = node.amount.toFloat() / peak,
                    color = FintoThemeTokens.money.positive,
                    onClick = node.toAccount?.let { peer -> { actions.onOpenAccount(peer) } },
                )
            }
        }
        if (state.flow.outgoing.isNotEmpty()) {
            FlowLabel("Out")
            state.flow.outgoing.forEach { node ->
                BarRow(
                    label = node.label,
                    money = Money(node.amount, state.flowCurrency),
                    fraction = node.amount.toFloat() / peak,
                    onClick = node.toAccount?.let { peer -> { actions.onOpenAccount(peer) } }
                        ?: { actions.onDrill("category", node.key.removePrefix("ext-out-"), node.label) },
                )
            }
        }
    }
}

@Composable
private fun FlowLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun BarRow(
    label: String,
    money: Money,
    fraction: Float,
    count: Int? = null,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                count?.let {
                    Text("$it", style = MaterialTheme.typography.labelSmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                }
                MoneyText(money, whole = true, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            ShareBar(fraction.coerceIn(0.02f, 1f), color, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatRow(label: String, money: Money, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        MoneyText(money, whole = true, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Lineage(lineage: CardLineage) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(lineage.holder, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (lineage.supplementary) Tag("Supplementary")
        if (lineage.reissued) Tag("Reissued")
        lineage.numbers.forEach { number ->
            Text(
                "•$number",
                style = MaterialTheme.typography.labelLarge.tabular(),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun RecentRow(txn: Txn, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            DisplayDates.short(txn.date),
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(txn.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        MoneyText(txn.booked, signed = true, style = MaterialTheme.typography.bodyLarge)
    }
}
