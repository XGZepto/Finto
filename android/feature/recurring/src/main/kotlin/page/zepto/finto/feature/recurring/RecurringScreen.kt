package page.zepto.finto.feature.recurring

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.MoneyFormat

@Composable
fun RecurringRoute(
    onBack: () -> Unit,
    onOpenCommitment: (key: String) -> Unit,
    onOpenPlan: (id: String) -> Unit,
    onOpenPlans: () -> Unit,
    viewModel: RecurringViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RecurringScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onSearch = viewModel::setSearch,
        onKind = viewModel::setKind,
        onCadence = viewModel::setCadence,
        onToggleInactive = viewModel::toggleInactive,
        onOpenCommitment = { onOpenCommitment(it.key) },
        onOpenPlan = { onOpenPlan(it.id) },
        onOpenPlans = onOpenPlans,
        onNoticeShown = viewModel::noticeShown,
    )
}

@Composable
fun RecurringScreen(
    state: RecurringUiState,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onKind: (RecurringKind) -> Unit,
    onCadence: (Cadence?) -> Unit,
    onToggleInactive: () -> Unit,
    onOpenCommitment: (Commitment) -> Unit,
    onOpenPlan: (InstallmentPlan) -> Unit,
    onOpenPlans: () -> Unit,
    onNoticeShown: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    NoticeEffect(state.notice, snackbar, onNoticeShown)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { FintoTopBar("Recurring", onBack = onBack, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val phase = when {
            state.loading -> Phase.Loading
            state.error != null && !state.hasData -> Phase.Failed
            else -> Phase.Content
        }
        AnimatedContent(phase, transitionSpec = { crossfade() }, label = "recurring", modifier = Modifier.padding(padding)) { shown ->
            when (shown) {
                Phase.Loading -> ListSkeleton()
                Phase.Failed -> ErrorState(state.error.orEmpty(), onRetry = onRefresh)
                Phase.Content -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    RecurringContent(
                        state, onSearch, onKind, onCadence, onToggleInactive, onOpenCommitment, onOpenPlan, onOpenPlans,
                    )
                }
            }
        }
    }
}

internal enum class Phase { Loading, Failed, Content }

@Composable
private fun RecurringContent(
    state: RecurringUiState,
    onSearch: (String) -> Unit,
    onKind: (RecurringKind) -> Unit,
    onCadence: (Cadence?) -> Unit,
    onToggleInactive: () -> Unit,
    onOpenCommitment: (Commitment) -> Unit,
    onOpenPlan: (InstallmentPlan) -> Unit,
    onOpenPlans: () -> Unit,
) {
    val view = state.view
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (!state.hasData && !state.filter.isActive) {
            item(key = "empty") {
                MessageState(Icons.Outlined.EventRepeat, "No recurring charges", "Charges tagged subscription or recurring are listed here.")
            }
            return@LazyColumn
        }
        item(key = "hero") { CommittedCard(view, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item(key = "search") { SearchField(state.filter.search, onSearch) }
        item(key = "kind") { KindFilter(state.filter.kind, onKind) }
        item(key = "cadence") { CadenceFilter(state.filter.cadence, onCadence) }
        if (view.isEmpty) {
            item(key = "no-match") { MessageState(Icons.Outlined.FilterAltOff, "Nothing matches this filter", "") }
        }

        if (view.activeSubscriptions.isNotEmpty()) {
            item(key = "subs-header") {
                SectionHeader("Subscriptions", trailing = { Count(view.activeSubscriptions.size) }, modifier = Modifier.animateItem())
            }
            commitmentRows("sub", view.activeSubscriptions, onOpenCommitment)
        }
        if (view.inactiveSubscriptions.isNotEmpty()) {
            item(key = "inactive-header") {
                InactiveHeader(view.inactiveSubscriptions.size, state.inactiveExpanded, onToggleInactive, Modifier.animateItem())
            }
            if (state.inactiveExpanded) commitmentRows("inactive", view.inactiveSubscriptions, onOpenCommitment)
        }
        if (view.plans.isNotEmpty()) {
            item(key = "plans-header") {
                SectionHeader(
                    "Instalment plans",
                    modifier = Modifier.animateItem(),
                    trailing = { TextButton(onClick = onOpenPlans) { Text("All plans") } },
                )
            }
            items(view.plans, key = { "plan-${it.id}" }) { plan ->
                PlanRow(plan, onClick = { onOpenPlan(plan) }, modifier = Modifier.animateItem())
            }
        }
    }
}

private fun LazyListScope.commitmentRows(prefix: String, commitments: List<Commitment>, onOpen: (Commitment) -> Unit) {
    items(commitments, key = { "$prefix-${it.key}" }) { commitment ->
        CommitmentRow(commitment, onClick = { onOpen(commitment) }, modifier = Modifier.animateItem())
    }
}

@Composable
private fun CommittedCard(view: RecurringView, modifier: Modifier = Modifier) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Committed each month", style = MaterialTheme.typography.titleSmall, color = onContainer)
            AnimatedMoneyText(
                view.monthlyTotal.abs(),
                style = MaterialTheme.typography.displaySmall,
                color = onContainer,
            )
            Text(
                "${plural(view.activeSubscriptions.size, "active subscription")} · ${plural(view.plans.size, "instalment plan")}",
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            val notes = listOfNotNull(
                view.irregularCount.takeIf { it > 0 }?.let { "$it irregular, not counted" },
                view.inactiveSubscriptions.size.takeIf { it > 0 }?.let { "$it inactive, not counted" },
            )
            if (notes.isNotEmpty()) {
                Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = onContainer.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("Search merchants") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun KindFilter(kind: RecurringKind, onKind: (RecurringKind) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        RecurringKind.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = kind == option,
                onClick = { onKind(option) },
                shape = SegmentedButtonDefaults.itemShape(index, RecurringKind.entries.size),
                icon = {},
                label = { Text(option.label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun CadenceFilter(cadence: Cadence?, onCadence: (Cadence?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = cadence == null, onClick = { onCadence(null) }, label = { Text("Any cadence") })
        Cadence.entries.forEach { option ->
            FilterChip(selected = cadence == option, onClick = { onCadence(option) }, label = { Text(option.label) })
        }
    }
}

@Composable
internal fun Count(count: Int) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun InactiveHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Inactive",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(turn))
    }
}

@Composable
private fun CommitmentRow(commitment: Commitment, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val counted = commitment.monthly?.takeIf { !commitment.inactive }
    val timing = if (commitment.nextDue != null && !commitment.inactive) {
        "next ${DisplayDates.short(commitment.nextDue)}"
    } else {
        "last ${DisplayDates.short(commitment.lastCharge)}"
    }
    ListRow(
        title = commitment.merchant,
        onClick = onClick,
        modifier = modifier,
        supporting = {
            Tag(commitment.cadence.label)
            Spacer(Modifier.width(8.dp))
            Text(timing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        },
        figure = { MoneyText(commitment.amount, absolute = true, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) },
        note = counted?.let { "${MoneyFormat.format(it, absolute = true)}/mo" } ?: "not counted",
    )
}

@Composable
private fun PlanRow(plan: InstallmentPlan, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListRow(
        title = plan.title,
        onClick = onClick,
        modifier = modifier,
        supporting = {
            Tag("${plan.paidCount} of ${plan.termMonths}")
            Spacer(Modifier.width(8.dp))
            Text(
                "${MoneyFormat.format(plan.outstanding, absolute = true)} left",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        figure = { MoneyText(Recurring.perMonth(plan), absolute = true, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) },
        note = "/mo",
    )
}

/** A merchant row: mark, name, a supporting line, and a figure with a note under it. */
@Composable
private fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: @Composable () -> Unit,
    figure: @Composable () -> Unit,
    note: String,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Show details", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MerchantMark(title)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) { supporting() }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            figure()
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
