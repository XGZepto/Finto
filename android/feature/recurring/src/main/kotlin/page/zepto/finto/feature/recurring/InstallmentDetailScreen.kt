package page.zepto.finto.feature.recurring

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.ShareBar
import page.zepto.finto.core.designsystem.component.StatGrid
import page.zepto.finto.core.designsystem.component.StatTile
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.OpenLedger

@Composable
fun InstallmentDetailRoute(
    onBack: () -> Unit,
    onOpenLedger: OpenLedger,
    onOpenTransaction: (LedgerFilter, String?, String) -> Unit = { filter, title, _ -> onOpenLedger(filter, title) },
    viewModel: InstallmentDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InstallmentDetailScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenCharge = { plan, row ->
            val id = row.txnId
            if (id != null) onOpenTransaction(scheduleFilter(plan, row), plan.title, id) else onOpenLedger(scheduleFilter(plan, row), plan.title)
        },
        onNoticeShown = viewModel::noticeShown,
    )
}

/** The blotter on the plan's account and the day of one charge. */
internal fun scheduleFilter(plan: InstallmentPlan, row: ScheduleRow) =
    LedgerFilter(accounts = listOf(plan.accountId), from = row.date, to = row.date)

@Composable
fun InstallmentDetailScreen(
    state: InstallmentDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCharge: (InstallmentPlan, ScheduleRow) -> Unit,
    onNoticeShown: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    NoticeEffect(state.notice, snackbar, onNoticeShown)
    val plan = state.plan
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FintoTopBar(
                title = plan?.title ?: "Instalment",
                subtitle = plan?.let { Labels.humanize(it.accountId) },
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val phase = when {
            state.loading -> Phase.Loading
            plan == null -> Phase.Failed
            else -> Phase.Content
        }
        AnimatedContent(phase, transitionSpec = { crossfade() }, label = "instalment", modifier = Modifier.padding(padding)) { shown ->
            when (shown) {
                Phase.Loading -> ListSkeleton(rows = 5)
                Phase.Failed -> ErrorState(state.error ?: "Not found.", onRetry = onRefresh)
                Phase.Content -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    plan?.let { PlanDetail(it, state.schedule, onOpenCharge) }
                }
            }
        }
    }
}

@Composable
private fun PlanDetail(plan: InstallmentPlan, schedule: List<ScheduleRow>, onOpenCharge: (InstallmentPlan, ScheduleRow) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item(key = "progress") { ProgressCard(plan, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item(key = "facts") {
            StatGrid(Modifier.padding(top = 8.dp)) {
                val tile = Modifier.weight(1f)
                StatTile("Principal", MoneyFormat.format(plan.principal, absolute = true), tile)
                StatTile("Paid", plan.paid?.let { MoneyFormat.format(it, absolute = true) } ?: "—", tile)
                StatTile("Per month", MoneyFormat.format(plan.perInstallment, absolute = true), tile)
                StatTile("Outstanding", MoneyFormat.format(plan.outstanding, absolute = true), tile)
                StatTile("Remaining", plural(plan.remainingCount, "charge"), tile)
                StatTile("Matched", if (plan.isConfirmed) "Confirmed" else "Automatic", tile)
            }
        }
        item(key = "schedule-header") { SectionHeader("Schedule", trailing = { Count(schedule.size) }) }
        items(schedule, key = { it.key }) { row ->
            ScheduleItem(row, onClick = row.txnId?.let { { onOpenCharge(plan, row) } }, modifier = Modifier.animateItem())
        }
    }
}

@Composable
private fun ProgressCard(plan: InstallmentPlan, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (plan.settledEarly) "Settled after ${plan.paidCount} of ${plan.termMonths}" else "${plan.paidCount} of ${plan.termMonths} paid",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Started ${DisplayDates.medium(plan.startDate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (InstallmentSchedule.isUncertain(plan)) {
                    Tag(
                        "${InstallmentSchedule.confidenceLabel(plan)} match",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                StatusTag(plan.status)
            }
            ShareBar(InstallmentSchedule.progress(plan), progressColor(plan))
        }
    }
}

@Composable
private fun ScheduleItem(row: ScheduleRow, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val projected = row.state == ScheduleState.Projected
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClickLabel = "Show in blotter", onClick = onClick) else it.semantics(mergeDescendants = true) {} }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (projected) 0.62f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeqBadge(row)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(DisplayDates.medium(row.date), style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(row.state.label, row.description?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        MoneyText(row.amount, absolute = true, style = MaterialTheme.typography.bodyLarge, fontWeight = if (projected) null else FontWeight.Medium)
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
            )
        } else {
            Spacer(Modifier.width(24.dp))
        }
    }
}

/** The instalment number in a circle: filled once charged, outlined while projected. */
@Composable
private fun SeqBadge(row: ScheduleRow) {
    val scheme = MaterialTheme.colorScheme
    val shape = CircleShape
    val base = Modifier.size(32.dp).clip(shape)
    val (modifier, content) = when (row.state) {
        ScheduleState.Charged -> base.background(scheme.primary) to scheme.onPrimary
        ScheduleState.Billed -> base.background(scheme.primaryContainer) to scheme.onPrimaryContainer
        ScheduleState.Projected -> base.border(1.dp, scheme.outline, shape) to scheme.onSurfaceVariant
        ScheduleState.Settled -> base.background(FintoThemeTokens.money.positiveContainer) to FintoThemeTokens.money.positive
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (row.seq == null) {
            Icon(Icons.Outlined.DoneAll, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        } else {
            Text(row.seq.toString(), style = MaterialTheme.typography.labelMedium, color = content, fontWeight = FontWeight.SemiBold)
        }
    }
}
