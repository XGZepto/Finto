package page.zepto.finto.feature.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import page.zepto.finto.core.designsystem.component.ErrorState
import page.zepto.finto.core.designsystem.component.LoadingState
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.ShareBar
import page.zepto.finto.core.designsystem.theme.SeriesColors
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.RankedBucket
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SummaryRoute(
    onOpenCategory: (category: String, label: String, from: String) -> Unit,
    viewModel: SummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SummaryScreen(
        state = state,
        onRangeChange = viewModel::setRange,
        onRefresh = viewModel::refresh,
        onOpenCategory = { bucket -> onOpenCategory(bucket, Labels.humanize(bucket), state.windowStart) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    state: SummaryUiState,
    onRangeChange: (SummaryRange) -> Unit,
    onRefresh: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("Summary") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.error != null && state.hero == null && state.ranked.isEmpty() ->
                ErrorState(state.error, Modifier.padding(padding), onRetry = onRefresh)
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                SummaryContent(state, onRangeChange, onOpenCategory)
            }
        }
    }
}

@Composable
private fun SummaryContent(
    state: SummaryUiState,
    onRangeChange: (SummaryRange) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "hero") {
            state.hero?.let { HeroCard(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                ?: MessageState(Icons.Outlined.ReceiptLong, "No spending this month", "")
        }
        if (state.stale.isNotEmpty()) {
            item(key = "stale") { StaleCard(state.stale.map { it.displayName }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
        item(key = "range") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                SummaryRange.entries.forEachIndexed { index, range ->
                    SegmentedButton(
                        selected = state.range == range,
                        onClick = { onRangeChange(range) },
                        shape = SegmentedButtonDefaults.itemShape(index, SummaryRange.entries.size),
                        label = { Text(range.label) },
                    )
                }
            }
        }
        if (state.months.isNotEmpty()) {
            item(key = "trend") {
                SectionHeader("Cash flow · ${state.currency}")
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TrendChart(state.months, Modifier.padding(16.dp))
                }
            }
        }
        item(key = "where-header") {
            SectionHeader(
                text = "Where it went",
                trailing = {
                    if (state.ranked.isNotEmpty()) {
                        MoneyText(
                            state.rankedTotal,
                            whole = true,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                },
            )
        }
        if (state.ranked.isEmpty()) {
            item(key = "where-empty") {
                MessageState(Icons.Outlined.Inventory2, "No spending in this period", "")
            }
        }
        itemsIndexed(state.ranked.take(10), key = { _, bucket -> "bucket-${bucket.bucket}" }) { index, bucket ->
            RankedRow(bucket, SeriesColors[minOf(index, SeriesColors.lastIndex)], onClick = { onOpenCategory(bucket.bucket) })
        }
        if (state.unconvertible.isNotEmpty()) {
            item(key = "unconvertible") {
                Text(
                    "Not ranked: ${state.unconvertible.joinToString()} has no rate into ${state.currency}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(hero: Hero, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    // Read through the configuration so a language change recomposes this.
    val locale = LocalConfiguration.current.locales[0]
    val label = if (hero.anchor.year == today.year && hero.anchor.month == today.month) {
        "Spent this month"
    } else {
        "Spent in ${DisplayDates.monthName(hero.anchor)}"
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (hero.lagging && hero.exact) {
                    Text(
                        "through ${hero.anchor.format(DateTimeFormatter.ofPattern("MMM d", locale))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
            }
            MoneyText(
                hero.spent,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                whole = true,
            )
            hero.change?.let { change ->
                val up = change >= 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (up) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${if (up) "+" else "−"}${abs(change).roundToInt()}% vs the same days last month",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (hero.income.amount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 8.dp)) {
                    LabeledFigure("Income") {
                        MoneyText(hero.income, whole = true, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
                    }
                    hero.savingsRate?.let { rate ->
                        LabeledFigure("Saved") {
                            Text(
                                "${rate.roundToInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledFigure(label: String, figure: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
        figure()
    }
}

@Composable
private fun StaleCard(accounts: List<String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (accounts.size == 1) "A statement is overdue" else "${accounts.size} statements are overdue",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                accounts.joinToString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun RankedRow(bucket: RankedBucket, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Show transactions", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Labels.humanize(bucket.bucket),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MoneyText(bucket.spend, whole = true, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShareBar(bucket.share.toFloat(), color, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(
                    "${(bucket.share * 100).roundToInt()}% · ${bucket.rows}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}
