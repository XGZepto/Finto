package page.zepto.finto.feature.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.Bar
import page.zepto.finto.core.designsystem.component.BarChart
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.ShareBar
import page.zepto.finto.core.designsystem.component.SkeletonBlock
import page.zepto.finto.core.designsystem.component.StatGrid
import page.zepto.finto.core.designsystem.component.StatTile
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.FlowTotals
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.OpenLedger
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun ReportsRoute(
    onOpenLedger: OpenLedger,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReportsScreen(
        state = state,
        onPeriodChange = viewModel::setPeriod,
        onAccountChange = viewModel::setAccount,
        onSplitChange = viewModel::setSplit,
        onToggleMonth = viewModel::toggleMonth,
        onClearMonths = viewModel::clearMonths,
        onRefresh = viewModel::refresh,
        onErrorShown = viewModel::errorShown,
        onOpenLedger = onOpenLedger,
    )
}

@Composable
fun ReportsScreen(
    state: ReportsUiState,
    onPeriodChange: (ReportPeriod) -> Unit,
    onAccountChange: (String?) -> Unit,
    onSplitChange: (String) -> Unit,
    onToggleMonth: (String) -> Unit,
    onClearMonths: () -> Unit,
    onRefresh: () -> Unit,
    onErrorShown: () -> Unit,
    onOpenLedger: OpenLedger,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val report = state.report
    LaunchedEffect(state.error, report != null) {
        val error = state.error
        if (error != null && report != null) {
            snackbar.showSnackbar(error)
            onErrorShown()
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { FintoTopBar("Reports", subtitle = subtitle(state), scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "controls") { Controls(state, onPeriodChange, onAccountChange, onSplitChange) }
                when {
                    report != null -> reportItems(state, report, onToggleMonth, onClearMonths, onOpenLedger)
                    state.error != null && !state.loading -> item(key = "error") {
                        ErrorState(state.error, Modifier.animateItem(), onRetry = onRefresh)
                    }
                    else -> item(key = "skeleton") { ReportSkeleton(Modifier.animateItem()) }
                }
            }
            // A new filter is loading over the old figures.
            if (state.loading && !state.refreshing && report != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

private fun subtitle(state: ReportsUiState): String? {
    val selected = state.selectedMonths
    return when {
        selected.size == 1 -> monthLabel(selected.single())
        selected.size > 1 -> "${selected.size} months selected"
        else -> state.window?.let(::windowLabel)
    }
}

/** `6 Aug – 6 Nov 2026`; the start's year only when it differs. */
internal fun windowLabel(window: DateWindow): String {
    val sameYear = window.from.year == window.to.year
    val start = window.from.format(DateTimeFormatter.ofPattern(if (sameYear) "d MMM" else "d MMM yyyy"))
    return "$start – ${window.to.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
}

@Composable
private fun Controls(
    state: ReportsUiState,
    onPeriodChange: (ReportPeriod) -> Unit,
    onAccountChange: (String?) -> Unit,
    onSplitChange: (String) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ReportPeriod.entries.forEachIndexed { index, period ->
                SegmentedButton(
                    selected = state.period == period,
                    onClick = { onPeriodChange(period) },
                    shape = SegmentedButtonDefaults.itemShape(index, ReportPeriod.entries.size),
                    icon = {},
                    label = { Text(period.label) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val accountOptions: List<Account?> = listOf<Account?>(null) + state.accounts
            OptionChip(
                label = state.account?.displayName ?: "All accounts",
                options = accountOptions,
                selected = state.account,
                optionLabel = { it?.displayName ?: "All accounts" },
                onSelect = { onAccountChange(it?.id) },
                description = "Accounts",
            )
            OptionChip(
                label = "By ${state.split}",
                options = ReportMath.SPLITS,
                selected = state.split,
                optionLabel = { it.replaceFirstChar(Char::titlecase) },
                onSelect = onSplitChange,
                description = "Split by",
            )
        }
    }
}

private fun LazyListScope.reportItems(
    state: ReportsUiState,
    report: Report,
    onToggleMonth: (String) -> Unit,
    onClearMonths: () -> Unit,
    onOpenLedger: OpenLedger,
) {
    val currency = state.currency
    report.totals?.let { totals ->
        item(key = "totals") { Totals(totals, report, state.comparisonLabel, Modifier.animateItem()) }
    }
    if (report.bands.isEmpty()) {
        item(key = "empty") {
            MessageState(Icons.Outlined.Inventory2, "Nothing to report for this filter", "", Modifier.animateItem())
        }
        return
    }
    if (report.months.size > 1) {
        item(key = "months") {
            Column(Modifier.animateItem()) {
                SectionHeader(
                    "Spending over time",
                    trailing = {
                        if (state.selectedMonths.isNotEmpty()) {
                            SelectionChip(state.selectedMonths, onClearMonths, Modifier.padding(end = 8.dp))
                        }
                    },
                )
                MonthChart(report, currency, state.selectedMonths, onToggleMonth)
            }
        }
    }
    item(key = "where-header") {
        SectionHeader(
            "Where the money went",
            modifier = Modifier.animateItem(),
            trailing = {
                Text(
                    "by ${report.split} · top ${report.topBands.size} of ${report.bands.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
        )
    }
    val names = state.accounts.associate { it.id to it.displayName }
    val open: (Band) -> Unit = { band ->
        onOpenLedger(report.drill(band.bucket), bucketLabel(report.split, band.bucket, names))
    }
    item(key = "strip") { ShareStrip(report.topBands, report.split, open, Modifier.animateItem()) }
    itemsIndexed(report.topBands, key = { _, band -> "band-${band.bucket}" }) { index, band ->
        BandRow(band, bucketLabel(report.split, band.bucket, names), bandColor(index), { open(band) }, Modifier.animateItem())
    }
    if (report.unconvertible.isNotEmpty()) {
        item(key = "unconvertible") {
            Text(
                "Not counted: ${report.unconvertible.joinToString()} has no rate into $currency.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Totals(totals: FlowTotals, report: Report, comparison: String, modifier: Modifier = Modifier) {
    val money = FintoThemeTokens.money
    val income = totals.income.abs()
    val spend = totals.spend.abs()
    val net = totals.net
    StatGrid(modifier.padding(top = 12.dp)) {
        StatTile(
            label = "In",
            value = MoneyFormat.formatWhole(income),
            valueColor = if (income.amount > 0) money.positive else Color.Unspecified,
            modifier = Modifier.weight(1f).fillMaxRowHeight(),
        )
        StatTile(
            label = "Out",
            value = MoneyFormat.formatWhole(spend),
            supporting = report.delta?.let { delta ->
                val arrow = if (delta.amount.amount > 0) "↑" else "↓"
                "$arrow ${"%.1f".format(abs(delta.percent))}% $comparison"
            },
            modifier = Modifier.weight(1f).fillMaxRowHeight(),
        )
        StatTile(
            label = "Net",
            value = signedWhole(net),
            valueColor = when {
                net.amount > 0 -> money.positive
                net.amount < 0 -> money.negative
                else -> Color.Unspecified
            },
            modifier = Modifier.weight(1f).fillMaxRowHeight(),
        )
        StatTile(
            label = "Saved",
            value = report.savingsRate?.let { "${"%.1f".format(it)}%" } ?: "—",
            modifier = Modifier.weight(1f).fillMaxRowHeight(),
        )
    }
}

private fun signedWhole(money: Money): String =
    MoneyFormat.formatWhole(money).let { if (money.amount > 0) "+$it" else it }

@Composable
private fun SelectionChip(selected: List<String>, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (selected.size == 1) monthLabel(selected.single()) else "${selected.size} months"
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Clear selection", Modifier.size(InputChipDefaults.IconSize)) },
        modifier = modifier,
    )
}

@Composable
private fun MonthChart(report: Report, currency: String, selected: List<String>, onToggleMonth: (String) -> Unit) {
    val bars = remember(report.months, currency) {
        report.months.map { month ->
            val amount = MoneyFormat.formatWhole(Money(month.spend, currency))
            Bar(
                key = month.month,
                label = DisplayDates.monthAbbreviation(month.month),
                value = month.spend,
                description = "${monthLabel(month.month)}: $amount",
            )
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BarChart(
                bars = bars,
                selected = selected.toSet(),
                reference = report.mean,
                onBarClick = { onToggleMonth(it.key) },
            )
            report.mean?.let { mean ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Average of complete months ${MoneyFormat.formatWhole(Money(mean, currency))}"
                }) {
                    DashSwatch(MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("Avg", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    MoneyText(Money(mean, currency), whole = true, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DashSwatch(color: Color) {
    Canvas(Modifier.width(18.dp).height(2.dp)) {
        drawLine(
            color,
            Offset(0f, size.height / 2),
            Offset(size.width, size.height / 2),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
    }
}

/** The bands side by side in proportion: the overview the rows below detail. */
@Composable
private fun ShareStrip(bands: List<Band>, split: String, onOpen: (Band) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .clearAndSetSemantics { contentDescription = "Share of spending by $split" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        bands.forEachIndexed { index, band ->
            Box(
                Modifier
                    .weight(band.share.toFloat().coerceAtLeast(0.001f))
                    .widthIn(min = 3.dp)
                    .fillMaxHeight()
                    .background(bandColor(index))
                    .clickable { onOpen(band) },
            )
        }
    }
}

@Composable
private fun BandRow(band: Band, label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Show transactions", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            MoneyText(band.amount, whole = true, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.padding(start = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            ShareBar(band.fraction.toFloat(), color, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(
                "${"%.1f".format(band.share * 100)}%",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 40.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun ReportSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(Modifier.weight(1f), height = 68.dp)
                SkeletonBlock(Modifier.weight(1f), height = 68.dp)
            }
        }
        Spacer(Modifier.height(16.dp))
        SkeletonBlock(Modifier.fillMaxWidth(), height = 196.dp)
        Spacer(Modifier.height(16.dp))
        SkeletonBlock(Modifier.fillMaxWidth(), height = 12.dp)
        repeat(5) { SkeletonBlock(Modifier.fillMaxWidth(), height = 40.dp) }
    }
}
