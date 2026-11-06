package page.zepto.finto.feature.recurring

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.ShareBar
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.MoneyFormat

@Composable
fun InstallmentsRoute(
    onBack: () -> Unit,
    onOpenPlan: (id: String) -> Unit,
    viewModel: InstallmentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InstallmentsScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onActiveOnly = viewModel::setActiveOnly,
        onOpenPlan = { onOpenPlan(it.id) },
        onNoticeShown = viewModel::noticeShown,
    )
}

@Composable
fun InstallmentsScreen(
    state: InstallmentsUiState,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onActiveOnly: (Boolean) -> Unit,
    onOpenPlan: (InstallmentPlan) -> Unit,
    onNoticeShown: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    NoticeEffect(state.notice, snackbar, onNoticeShown)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { FintoTopBar("Instalments", onBack = onBack, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "tiles") { LiabilityTiles(state) }
                item(key = "toggle") { PlansHeader(state.activeOnly, onActiveOnly) }
                item(key = "plans") {
                    val phase = when {
                        state.loading -> Phase.Loading
                        state.error != null && state.response == null -> Phase.Failed
                        else -> Phase.Content
                    }
                    AnimatedContent(phase, transitionSpec = { crossfade() }, label = "plans") { shown ->
                        when (shown) {
                            Phase.Loading -> ListSkeleton(hero = false, rows = 4)
                            Phase.Failed -> ErrorState(state.error.orEmpty(), onRetry = onRefresh)
                            Phase.Content -> if (state.plans.isEmpty()) {
                                MessageState(Icons.Outlined.CalendarMonth, "No instalment plans detected", "")
                            } else {
                                Spacer(Modifier.height(0.dp))
                            }
                        }
                    }
                }
                if (!state.loading) {
                    items(state.plans, key = { it.id }) { plan ->
                        PlanCard(plan, onClick = { onOpenPlan(plan) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun LiabilityTiles(state: InstallmentsUiState) {
    val response = state.response
    val outstanding = response?.outstandingByCurrency.orEmpty().map { MoneyFormat.formatWhole(it.money, absolute = true) }
    val monthly = response?.committedMonthlyByCurrency.orEmpty().map { MoneyFormat.formatWhole(it.money, absolute = true) }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tile = Modifier.weight(1f).fillMaxHeight()
        if (response == null) {
            FigureTile("Outstanding", listOf("—"), tile, muted = true)
            FigureTile("Monthly", listOf("—"), tile, muted = true)
            FigureTile("Active plans", listOf("—"), tile, muted = true)
        } else {
            FigureTile("Outstanding", outstanding.ifEmpty { listOf("Nothing owed") }, tile, muted = outstanding.isEmpty())
            FigureTile("Monthly", monthly.ifEmpty { listOf("—") }, tile, muted = monthly.isEmpty())
            FigureTile("Active plans", listOf(state.activeCount.toString()), tile)
        }
    }
}

@Composable
private fun PlansHeader(activeOnly: Boolean, onActiveOnly: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Plans",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        SingleChoiceSegmentedButtonRow {
            listOf(true to "Active", false to "All").forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = activeOnly == value,
                    onClick = { onActiveOnly(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    icon = {},
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
internal fun statusColors(status: String): Pair<Color, Color> = when (status) {
    "completed" -> FintoThemeTokens.money.positiveContainer to FintoThemeTokens.money.positive
    "cancelled" -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
}

@Composable
internal fun StatusTag(status: String) {
    val (container, content) = statusColors(status)
    Tag(Labels.humanize(status), container = container, content = content)
}

@Composable
internal fun progressColor(plan: InstallmentPlan): Color =
    if (plan.status == "completed") FintoThemeTokens.money.positive else MaterialTheme.colorScheme.primary

@Composable
private fun PlanCard(plan: InstallmentPlan, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Show schedule", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlanTitle(plan, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            MoneyText(plan.perInstallment, absolute = true, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("/mo", style = MaterialTheme.typography.labelMedium, color = muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${Labels.humanize(plan.accountId)} · ${DisplayDates.short(plan.startDate)}",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${MoneyFormat.format(plan.outstanding, absolute = true)} left",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShareBar(InstallmentSchedule.progress(plan), progressColor(plan), Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(InstallmentSchedule.progressLabel(plan), style = MaterialTheme.typography.labelMedium, color = muted)
            Spacer(Modifier.width(10.dp))
            StatusTag(plan.status)
        }
    }
}

/** The plan's name, with its match confidence when the grouping was a judgement call. */
@Composable
internal fun PlanTitle(plan: InstallmentPlan, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            plan.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (InstallmentSchedule.isUncertain(plan)) {
            Spacer(Modifier.width(8.dp))
            Tag(
                InstallmentSchedule.confidenceLabel(plan),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
