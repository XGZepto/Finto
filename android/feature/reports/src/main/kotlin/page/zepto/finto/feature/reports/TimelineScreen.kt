package page.zepto.finto.feature.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StackedBarChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.SkeletonBlock
import page.zepto.finto.core.model.Composition
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.OpenLedger

@Composable
fun TimelineRoute(
    onBack: () -> Unit,
    onOpenLedger: OpenLedger,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TimelineScreen(
        state = state,
        onBack = onBack,
        onDimensionChange = viewModel::setDimension,
        onScaleChange = viewModel::setScale,
        onRefresh = viewModel::refresh,
        onErrorShown = viewModel::errorShown,
        onOpenLedger = onOpenLedger,
    )
}

@Composable
fun TimelineScreen(
    state: TimelineUiState,
    onBack: () -> Unit,
    onDimensionChange: (String) -> Unit,
    onScaleChange: (TimelineScale) -> Unit,
    onRefresh: () -> Unit,
    onErrorShown: () -> Unit,
    onOpenLedger: OpenLedger,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val composition = state.composition
    LaunchedEffect(state.error, composition != null) {
        val error = state.error
        if (error != null && composition != null) {
            snackbar.showSnackbar(error)
            onErrorShown()
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { FintoTopBar("Timeline", onBack = onBack, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "controls") { Controls(state, onDimensionChange, onScaleChange) }
                item(key = "composition") {
                    val modifier = Modifier.animateItem()
                    when {
                        composition != null && (composition.months.isEmpty() || composition.series.isEmpty()) ->
                            MessageState(Icons.Outlined.StackedBarChart, "No spending to chart", "", modifier)
                        composition != null -> CompositionCard(state, composition, onOpenLedger, modifier)
                        state.error != null && !state.loading -> ErrorState(state.error, modifier, onRetry = onRefresh)
                        else -> ChartSkeleton(modifier)
                    }
                }
                state.coverage?.takeIf { it.accounts.isNotEmpty() }?.let { coverage ->
                    item(key = "coverage") {
                        Column(Modifier.animateItem()) {
                            SectionHeader("Data by month")
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            ) {
                                CoverageGrid(coverage, Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(state: TimelineUiState, onDimensionChange: (String) -> Unit, onScaleChange: (TimelineScale) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OptionChip(
            label = "By ${state.dimension}",
            options = TimelineMath.DIMENSIONS,
            selected = state.dimension,
            optionLabel = { it.replaceFirstChar(Char::titlecase) },
            onSelect = onDimensionChange,
            description = "Dimension",
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        SingleChoiceSegmentedButtonRow {
            TimelineScale.entries.forEachIndexed { index, scale ->
                SegmentedButton(
                    selected = state.scale == scale,
                    onClick = { onScaleChange(scale) },
                    shape = SegmentedButtonDefaults.itemShape(index, TimelineScale.entries.size),
                    icon = {},
                    label = { Text(scale.label) },
                )
            }
        }
    }
}

@Composable
private fun CompositionCard(
    state: TimelineUiState,
    composition: Composition,
    onOpenLedger: OpenLedger,
    modifier: Modifier = Modifier,
) {
    val names = state.accountNames
    val other = MaterialTheme.colorScheme.outline
    val colorOf: (String, Int) -> Color = { bucket, series -> if (bucket == TimelineMath.OTHER) other else bandColor(series) }
    val label: (String) -> String = { bucket -> bucketLabel(composition.dimension, bucket, names) }
    val currency = composition.currency
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (composition.unconvertibleCurrencies.isNotEmpty()) {
                Text(
                    "${composition.unconvertibleCurrencies.joinToString(", ")} omitted — no $currency rate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CompositionChart(
                columns = state.columns,
                colorOf = { colorOf(it.bucket, it.series) },
                describe = { column ->
                    val parts = column.segments.filter { it.amount > 0 }.joinToString("; ") { segment ->
                        "${label(segment.bucket)} ${MoneyFormat.formatWhole(Money(segment.amount, currency))}"
                    }
                    "${monthLabel(column.month)}: ${MoneyFormat.formatWhole(Money(column.total, currency))}. $parts"
                },
                actionLabel = { "Open ${label(it.bucket)}" },
                onBandClick = { column, segment ->
                    onOpenLedger(
                        TimelineMath.drill(composition.dimension, segment.bucket, column.month),
                        "${label(segment.bucket)} · ${monthLabel(column.month)}",
                    )
                },
            )
            Legend(composition, colorOf, label)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(composition: Composition, colorOf: (String, Int) -> Color, label: (String) -> String) {
    FlowRow(
        Modifier.fillMaxWidth(),
        maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        composition.series.forEachIndexed { index, series ->
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Swatch(colorOf(series.bucket, index))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(label(series.bucket), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MoneyText(
                        Money(series.total, composition.currency),
                        whole = true,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Keeps the last of an odd count in its column rather than across the row.
        if (composition.series.size % 2 == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ChartSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBlock(Modifier.fillMaxWidth(), height = 240.dp)
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkeletonBlock(Modifier.weight(1f), height = 32.dp)
                SkeletonBlock(Modifier.weight(1f), height = 32.dp)
            }
        }
    }
}
