package page.zepto.finto.feature.investments

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.AnimatedMoneyText
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.ShareBar
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.SeriesColors
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Holding
import page.zepto.finto.core.model.InvestmentActivity
import page.zepto.finto.core.model.InvestmentDetail
import page.zepto.finto.core.model.InvestmentSnapshot
import page.zepto.finto.core.model.InvestmentSubaccount
import page.zepto.finto.core.model.MoneyFormat

@Composable
fun InvestmentsRoute(
    onBack: () -> Unit,
    onImport: () -> Unit,
    viewModel: InvestmentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A member account is a step into the scheme: back returns to the whole scheme first.
    BackHandler(enabled = state.accountId != null, onBack = viewModel::clearAccount)
    InvestmentsScreen(
        state = state,
        onBack = { if (state.accountId != null) viewModel.clearAccount() else onBack() },
        onRefresh = viewModel::refresh,
        onImport = onImport,
        onSelectScheme = viewModel::selectScheme,
        onSelectSnapshot = viewModel::selectSnapshot,
        onSelectAccount = viewModel::selectAccount,
        onNoticeShown = viewModel::noticeShown,
    )
}

private enum class InvestmentsPhase { Loading, Failed, Empty, Content }

@Composable
fun InvestmentsScreen(
    state: InvestmentsUiState,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onSelectScheme: (String) -> Unit,
    onSelectSnapshot: (String) -> Unit,
    onSelectAccount: (String) -> Unit,
    onNoticeShown: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    NoticeEffect(state.notice, snackbar, onNoticeShown)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FintoTopBar(
                title = state.title,
                subtitle = state.accountId?.let { state.current?.scheme?.let(Investments::friendly) },
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onImport) { Icon(Icons.Outlined.UploadFile, contentDescription = "Import MPF bundle") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val phase = when {
            state.loading -> InvestmentsPhase.Loading
            state.error != null && state.snapshots.isEmpty() -> InvestmentsPhase.Failed
            state.snapshots.isEmpty() -> InvestmentsPhase.Empty
            else -> InvestmentsPhase.Content
        }
        AnimatedContent(phase, transitionSpec = { crossfade() }, label = "investments", modifier = Modifier.padding(padding)) { shown ->
            when (shown) {
                InvestmentsPhase.Loading -> InvestmentsSkeleton()
                InvestmentsPhase.Failed -> ErrorState(state.error.orEmpty(), onRetry = onRefresh)
                InvestmentsPhase.Empty -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    MessageState(
                        Icons.Outlined.ShowChart,
                        "No valuation snapshots imported",
                        "",
                        action = { FilledTonalButton(onClick = onImport) { Text("Import MPF bundle") } },
                    )
                }
                InvestmentsPhase.Content -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    InvestmentsContent(state, onSelectScheme, onSelectSnapshot, onSelectAccount)
                }
            }
        }
    }
}

@Composable
private fun InvestmentsContent(
    state: InvestmentsUiState,
    onSelectScheme: (String) -> Unit,
    onSelectSnapshot: (String) -> Unit,
    onSelectAccount: (String) -> Unit,
) {
    val current = state.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.schemes.size > 1 && state.accountId == null) {
            item(key = "schemes") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.schemes.forEach { scheme ->
                        FilterChip(
                            selected = scheme == state.scheme,
                            onClick = { onSelectScheme(scheme) },
                            label = { Text(Investments.friendly(scheme)) },
                        )
                    }
                }
            }
        }
        item(key = "value") { ValuationCard(state, onSelectSnapshot, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        if (current == null) return@LazyColumn

        item(key = "history") {
            val points = state.visibleHistory
            SectionHeader("Valuation history", trailing = { Count(plural(points.size, "value")) })
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                if (points.size > 1) {
                    ValueChart(points, FintoThemeTokens.money.positive, Modifier.padding(16.dp))
                } else {
                    Text(
                        "One valuation so far",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        if (state.accountId == null && current.subaccounts.isNotEmpty()) {
            item(key = "accounts-header") { SectionHeader("Member accounts", trailing = { Count(current.subaccounts.size.toString()) }) }
            items(current.subaccounts, key = { "account-${it.accountId}" }) { subaccount ->
                SubaccountRow(subaccount, state.accountLabel(subaccount.accountId), onClick = { onSelectAccount(subaccount.accountId) }, Modifier.animateItem())
            }
        }

        item(key = "holdings-header") {
            SectionHeader(
                if (state.accountId != null) "Scheme allocation" else "Fund allocation",
                trailing = { Count(plural(current.holdings.size, "fund")) },
            )
        }
        if (current.holdings.isEmpty()) {
            item(key = "holdings-empty") {
                Text(
                    "No fund allocation in this valuation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        itemsIndexed(current.holdings, key = { _, holding -> "holding-${holding.instrument}" }) { index, holding ->
            HoldingRow(holding, Investments.barFraction(holding, current.holdings), index, Modifier.animateItem())
        }

        if (state.activities.isNotEmpty()) {
            item(key = "activity-header") {
                SectionHeader("Contribution activity", trailing = { Count(plural(state.activities.size, "record")) })
            }
            items(state.activities, key = { "activity-${it.id}" }) { activity ->
                ActivityRow(activity, state.accountLabel(activity.accountId), Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun Count(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun ValuationCard(state: InvestmentsUiState, onSelectSnapshot: (String) -> Unit, modifier: Modifier = Modifier) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val current = state.current
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Current value", style = MaterialTheme.typography.titleSmall, color = onContainer, modifier = Modifier.weight(1f))
                if (current != null && state.scopedSnapshots.size > 1) {
                    SnapshotPicker(state.scopedSnapshots, current, onSelectSnapshot)
                }
            }
            when {
                current != null -> {
                    state.displayedValue?.let {
                        AnimatedMoneyText(it, style = MaterialTheme.typography.displaySmall, color = onContainer)
                    }
                    Text("Valued ${DisplayDates.medium(current.asOfDate)}", style = MaterialTheme.typography.bodyMedium, color = onContainer.copy(alpha = 0.8f))
                    state.change?.let { change -> ChangeLine(change) }
                }
                state.openError != null -> Text(
                    "Couldn't open this snapshot. ${state.openError}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer,
                )
                else -> Text("…", style = MaterialTheme.typography.displaySmall, color = onContainer)
            }
            AnimatedVisibility(state.opening) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ChangeLine(change: ValueChange) {
    val up = change.amount.amount >= 0
    val tint = if (up) FintoThemeTokens.money.positive else FintoThemeTokens.money.negative
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (up) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            val amount = MoneyFormat.format(change.amount).let { if (up) "+$it" else it }
            Text(amount, style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.SemiBold)
            change.percent?.let {
                Spacer(Modifier.width(8.dp))
                Text(Investments.signedPercent(it), style = MaterialTheme.typography.labelLarge, color = tint)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "since ${DisplayDates.short(change.since)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SnapshotPicker(snapshots: List<InvestmentSnapshot>, current: InvestmentDetail, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(DisplayDates.short(current.asOfDate), color = MaterialTheme.colorScheme.onPrimaryContainer)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Valuation date", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            snapshots.forEach { snapshot ->
                DropdownMenuItem(
                    text = { Text(DisplayDates.medium(snapshot.asOfDate), fontWeight = if (snapshot.id == current.id) FontWeight.SemiBold else null) },
                    trailingIcon = { MoneyText(snapshot.total, whole = true, style = MaterialTheme.typography.labelLarge) },
                    onClick = {
                        open = false
                        onSelect(snapshot.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SubaccountRow(subaccount: InvestmentSubaccount, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Show this account", onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Investments.percent(subaccount.allocation)?.let {
                Text("$it of scheme", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        MoneyText(subaccount.balance, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun HoldingRow(holding: Holding, fraction: Float, index: Int, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                holding.instrument,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            MoneyText(holding.marketValue, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        val details = listOfNotNull(
            Investments.percent(holding.allocation),
            Investments.units(holding.units)?.let { "$it units" },
        )
        if (details.isNotEmpty()) {
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ShareBar(fraction, SeriesColors[index % SeriesColors.size])
    }
}

@Composable
private fun ActivityRow(activity: InvestmentActivity, accountLabel: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Investments.friendly(activity.activityType), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    DisplayDates.short(activity.activityDate),
                    activity.contributionType.takeIf { it.isNotBlank() }?.let(Investments::friendly),
                    accountLabel,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        MoneyText(activity.amount, signed = true, style = MaterialTheme.typography.bodyLarge)
    }
}
