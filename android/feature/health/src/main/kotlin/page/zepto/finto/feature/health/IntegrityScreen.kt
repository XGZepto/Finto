package page.zepto.finto.feature.health

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.SkeletonBlock
import page.zepto.finto.core.designsystem.component.StatGrid
import page.zepto.finto.core.designsystem.component.StatTile
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.BalanceCheck
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.IntegrityReport
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.OpenLedger
import page.zepto.finto.core.model.UnverifiedAccount
import page.zepto.finto.core.model.Violation
import java.text.NumberFormat

@Composable
fun IntegrityRoute(
    onBack: () -> Unit,
    onOpenLedger: OpenLedger,
    onOpenAccount: (accountId: String) -> Unit,
    viewModel: IntegrityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    IntegrityScreen(
        state = state,
        onBack = onBack,
        onRerun = viewModel::refresh,
        onShowAll = viewModel::showAll,
        onInspect = { check ->
            onOpenLedger(IntegrityLogic.inspectFilter(check), check.accountName ?: check.accountId)
        },
        onOpenUnverified = { account -> onOpenLedger(IntegrityLogic.accountFilter(account.accountId), account.displayName) },
        onOpenAccount = onOpenAccount,
        onMessageShown = viewModel::messageShown,
    )
}

private enum class IntegrityPhase { Loading, Failed, Report }

@Composable
fun IntegrityScreen(
    state: IntegrityUiState,
    onBack: () -> Unit,
    onRerun: () -> Unit,
    onShowAll: (Boolean) -> Unit,
    onInspect: (BalanceCheck) -> Unit,
    onOpenUnverified: (UnverifiedAccount) -> Unit,
    onOpenAccount: (accountId: String) -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FintoTopBar(
                "Integrity",
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onRerun, enabled = !state.refreshing) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Re-run")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val report = state.report
        val phase = when {
            report != null -> IntegrityPhase.Report
            state.error != null -> IntegrityPhase.Failed
            else -> IntegrityPhase.Loading
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = { crossfade() },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "integrity-phase",
        ) { shown ->
            when (shown) {
                IntegrityPhase.Loading -> IntegritySkeleton()
                IntegrityPhase.Failed -> ErrorState(state.error.orEmpty(), onRetry = onRerun)
                IntegrityPhase.Report -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRerun,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    report?.let { ReportList(it, state, onShowAll, onInspect, onOpenUnverified, onOpenAccount) }
                }
            }
        }
    }
}

@Composable
private fun ReportList(
    report: IntegrityReport,
    state: IntegrityUiState,
    onShowAll: (Boolean) -> Unit,
    onInspect: (BalanceCheck) -> Unit,
    onOpenUnverified: (UnverifiedAccount) -> Unit,
    onOpenAccount: (accountId: String) -> Unit,
) {
    val checks = state.checks
    val keys = remember(checks) { IntegrityLogic.keys(checks) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item("verdict") { Verdict(report, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item("tiles") { SummaryTiles(report) }

        item("violations-header") { SectionHeader("Structural invariants") }
        if (report.violations.isEmpty()) {
            item("violations-clean") { QuietLine("All clean.") }
        } else {
            items(report.violations, key = { "violation-${it.check}" }) { ViolationRow(it, Modifier.animateItem()) }
        }

        item("checks-header") { SectionHeader("Balance reconciliation") }
        item("checks-toggle") {
            val problems = IntegrityLogic.visibleChecks(report, showAll = false).size
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                SegmentedButton(
                    selected = !state.showAll,
                    onClick = { onShowAll(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text(if (problems > 0) "Problems ($problems)" else "Problems") },
                )
                SegmentedButton(
                    selected = state.showAll,
                    onClick = { onShowAll(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    label = { Text("All periods") },
                )
            }
        }
        if (checks.isEmpty()) {
            item("checks-empty") {
                QuietLine(if (state.showAll) "No balance assertions recorded." else "Every checked period reconciles to the cent.")
            }
        } else {
            itemsIndexed(checks, key = { index, _ -> "check-${keys[index]}" }) { index, check ->
                Column(Modifier.animateItem()) {
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 36.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CheckRow(check, onInspect = { onInspect(check) })
                }
            }
        }

        item("unverified-header") { SectionHeader("Unverified accounts") }
        if (report.unverifiedAccounts.isEmpty()) {
            item("unverified-clean") { QuietLine("All accounts have a balance assertion.") }
        } else {
            items(report.unverifiedAccounts, key = { "unverified-${it.accountId}" }) { account ->
                UnverifiedRow(account, onOpen = { onOpenUnverified(account) }, onOpenAccount = { onOpenAccount(account.accountId) })
            }
        }
    }
}

@Composable
private fun Verdict(report: IntegrityReport, modifier: Modifier = Modifier) {
    val money = FintoThemeTokens.money
    val (container, tint, icon) = if (report.healthy) {
        Triple(money.positiveContainer, money.positive, Icons.Outlined.Verified)
    } else {
        Triple(money.negativeContainer, money.negative, Icons.Outlined.ErrorOutline)
    }
    Surface(color = container, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                IntegrityLogic.verdict(report),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SummaryTiles(report: IntegrityReport) {
    val summary = report.summary
    val bad = FintoThemeTokens.money.negative
    val warn = MaterialTheme.colorScheme.tertiary
    val count = NumberFormat.getIntegerInstance()
    StatGrid(Modifier.padding(top = 8.dp)) {
        StatTile("Checks run", count.format(summary.checksRun), Modifier.weight(1f))
        StatTile(
            "Discrepancies",
            count.format(summary.discrepancyCount),
            Modifier.weight(1f),
            valueColor = if (summary.discrepancyCount > 0) bad else Color.Unspecified,
        )
        StatTile(
            "Violations",
            count.format(summary.violationCount),
            Modifier.weight(1f),
            valueColor = if (summary.violationCount > 0) bad else Color.Unspecified,
        )
        StatTile(
            "Unverified accounts",
            count.format(summary.unverifiedAccountCount),
            Modifier.weight(1f),
            valueColor = if (summary.unverifiedAccountCount > 0) warn else Color.Unspecified,
        )
    }
}

@Composable
private fun QuietLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ViolationRow(violation: Violation, modifier: Modifier = Modifier) {
    Surface(
        color = FintoThemeTokens.money.negativeContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) {}) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    violation.check,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("×${violation.count}", style = MaterialTheme.typography.labelLarge.tabular())
            }
            if (violation.description.isNotBlank()) {
                Text(violation.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CheckRow(check: BalanceCheck, onInspect: () -> Unit) {
    val problem = check.status == IntegrityLogic.DISCREPANCY
    val money = FintoThemeTokens.money
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Inspect rows", onClick = onInspect)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (problem) money.negative else money.positive, Modifier.padding(end = 12.dp))
                Text(
                    check.accountName ?: check.accountId,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${DisplayDates.short(check.periodStart)} → ${DisplayDates.short(check.periodEnd)}",
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Figure("Statement", check.expectedDelta, Modifier.weight(1f))
                Figure("Ledger", check.actualDelta, Modifier.weight(1f))
                Figure(
                    "Difference",
                    check.discrepancy,
                    Modifier.weight(1f),
                    color = if (problem) money.negative else MaterialTheme.colorScheme.onSurfaceVariant,
                    signed = true,
                )
            }
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun Figure(label: String, money: Money?, modifier: Modifier = Modifier, color: Color = Color.Unspecified, signed: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (money != null) {
            MoneyText(money, style = MaterialTheme.typography.labelLarge, color = color, signed = signed)
        } else {
            Text("—", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun UnverifiedRow(account: UnverifiedAccount, onOpen: () -> Unit, onOpenAccount: () -> Unit) {
    val count = NumberFormat.getIntegerInstance()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Show transactions", onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp).semantics(mergeDescendants = true) {}) {
            Text(account.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${count.format(account.txnCount)} ${IntegrityLogic.plural(account.txnCount, "transaction", "transactions")}",
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenAccount) {
            Icon(
                Icons.Outlined.AccountBalance,
                contentDescription = "Open ${account.displayName}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IntegritySkeleton() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBlock(Modifier.fillMaxWidth(), height = 60.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(Modifier.weight(1f), height = 64.dp)
            SkeletonBlock(Modifier.weight(1f), height = 64.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(Modifier.weight(1f), height = 64.dp)
            SkeletonBlock(Modifier.weight(1f), height = 64.dp)
        }
        Spacer(Modifier.height(12.dp))
        repeat(4) { SkeletonBlock(Modifier.fillMaxWidth(), height = 44.dp) }
    }
}
