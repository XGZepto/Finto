package page.zepto.finto.feature.summary

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import page.zepto.finto.core.designsystem.component.AnimatedMoneyText
import page.zepto.finto.core.designsystem.component.DropdownChip
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.SkeletonBlock
import page.zepto.finto.core.designsystem.theme.FintoMotion.crossfade
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.FreshnessAccount
import page.zepto.finto.core.model.ReportingCurrencies
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
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.designsystem.component.MoneyText
import page.zepto.finto.core.designsystem.component.SectionHeader
import page.zepto.finto.core.designsystem.component.ShareBar
import page.zepto.finto.core.designsystem.theme.SeriesColors
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Drill
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.OpenLedger
import page.zepto.finto.core.model.RankedBucket
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SummaryRoute(
    onOpenLedger: OpenLedger,
    onOpenImport: () -> Unit,
    viewModel: SummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val period = LedgerFilter(from = state.windowStart, spendingOnly = true)
    SummaryScreen(
        state = state,
        onRangeChange = viewModel::setRange,
        onRefresh = viewModel::refresh,
        onGroupByChange = viewModel::setGroupBy,
        onCurrencyChange = viewModel::setCurrency,
        onOpenBucket = { bucket -> onOpenLedger(Drill.into(state.groupBy, bucket, period), Labels.humanize(bucket)) },
        onOpenMonth = { month ->
            onOpenLedger(Drill.into("month", month, LedgerFilter(spendingOnly = true)), DisplayDates.shortMonth(month))
        },
        onOpenAccount = { account -> onOpenLedger(Drill.into("account", account.accountId, period), account.displayName) },
        onOpenImport = onOpenImport,
    )
}

@Composable
fun SummaryScreen(
    state: SummaryUiState,
    onRangeChange: (SummaryRange) -> Unit,
    onRefresh: () -> Unit,
    onOpenBucket: (String) -> Unit,
    onGroupByChange: (String) -> Unit = {},
    onCurrencyChange: (String) -> Unit = {},
    onOpenMonth: (String) -> Unit = {},
    onOpenAccount: (FreshnessAccount) -> Unit = {},
    onOpenImport: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val hasContent = state.hero != null || state.ranked.isNotEmpty() || state.months.isNotEmpty()
    // A failed refresh keeps the figures on screen and says so here instead.
    LaunchedEffect(state.error, hasContent) {
        if (state.error != null && hasContent) snackbar.showSnackbar(state.error)
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FintoTopBar(
                "Summary",
                scrollBehavior = scrollBehavior,
                actions = {
                    DropdownChip(
                        selected = state.currency,
                        options = ReportingCurrencies,
                        label = { it },
                        onSelect = onCurrencyChange,
                        description = "Reporting currency",
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val phase = when {
            state.loading -> 0
            state.error != null && !hasContent -> 1
            else -> 2
        }
        AnimatedContent(phase, transitionSpec = { crossfade() }, label = "summary", modifier = Modifier.padding(padding)) { shown ->
            when (shown) {
                0 -> SummarySkeleton()
                1 -> ErrorState(state.error.orEmpty(), onRetry = onRefresh)
                else -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    SummaryContent(state, onRangeChange, onGroupByChange, onOpenBucket, onOpenMonth, onOpenAccount, onOpenImport)
                }
            }
        }
    }
}

@Composable
private fun SummaryContent(
    state: SummaryUiState,
    onRangeChange: (SummaryRange) -> Unit,
    onGroupByChange: (String) -> Unit,
    onOpenBucket: (String) -> Unit,
    onOpenMonth: (String) -> Unit,
    onOpenAccount: (FreshnessAccount) -> Unit,
    onOpenImport: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.stale.isNotEmpty()) {
            item(key = "stale") {
                StaleCard(state.stale, onOpenAccount, onOpenImport, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).animateItem())
            }
        }
        item(key = "hero") {
            state.hero?.let { HeroCard(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                ?: MessageState(Icons.Outlined.ReceiptLong, "No spending recorded yet", "")
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
        if (state.months.size > 1) {
            item(key = "trend") {
                Column(Modifier.animateItem()) {
                    SectionHeader("Cash flow · ${state.currency}")
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        TrendChart(state.months, Modifier.padding(16.dp), onMonthClick = onOpenMonth)
                    }
                }
            }
        }
        item(key = "where-header") {
            SectionHeader(
                text = "Where it went",
                trailing = {
                    DropdownChip(
                        selected = state.groupBy,
                        options = SummaryDimensions,
                        label = { Labels.humanize(it) },
                        onSelect = onGroupByChange,
                        description = "Split by",
                    )
                },
            )
        }
        if (state.ranked.isNotEmpty()) {
            item(key = "where-total") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
                    Text(
                        "Total",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    MoneyText(state.rankedTotal, whole = true, style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            item(key = "where-empty") {
                MessageState(Icons.Outlined.Inventory2, "No spending in this period", "", Modifier.animateItem())
            }
        }
        itemsIndexed(state.ranked.take(12), key = { _, bucket -> "bucket-${state.groupBy}-${bucket.bucket}" }) { index, bucket ->
            RankedRow(
                bucket,
                SeriesColors[minOf(index, SeriesColors.lastIndex)],
                onClick = { onOpenBucket(bucket.bucket) },
                modifier = Modifier.animateItem(),
            )
        }
        if (state.unconvertible.isNotEmpty()) {
            item(key = "unconvertible") {
                Text(
                    "No ${state.currency} rate for ${state.unconvertible.joinToString()}; those rows are not in this ranking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SummarySkeleton() {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBlock(Modifier.fillMaxWidth(), height = 168.dp)
        SkeletonBlock(Modifier.fillMaxWidth(), height = 40.dp)
        SkeletonBlock(Modifier.fillMaxWidth(), height = 200.dp)
        repeat(4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.size(10.dp), height = 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.5f), height = 14.dp)
                    SkeletonBlock(Modifier.fillMaxWidth(), height = 6.dp)
                }
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
    val onHero = MaterialTheme.colorScheme.onPrimaryContainer
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = onHero, modifier = Modifier.weight(1f))
                if (hero.lagging && hero.exact) {
                    Text(
                        "through ${hero.anchor.format(DateTimeFormatter.ofPattern("MMM d", locale))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = onHero.copy(alpha = 0.75f),
                    )
                }
            }
            AnimatedMoneyText(hero.spent, style = MaterialTheme.typography.displaySmall, color = onHero)
            val change = hero.change
            when {
                hero.spent.amount == 0L -> HeroNote("No spending recorded yet", onHero)
                change == null -> HeroNote("No comparable activity last month", onHero)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val less = change < 0
                    Icon(
                        if (less) Icons.AutoMirrored.Outlined.TrendingDown else Icons.AutoMirrored.Outlined.TrendingUp,
                        contentDescription = null,
                        tint = onHero,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (change == 0.0) "No change" else "${"%.1f".format(abs(change))}% ${if (less) "less" else "more"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = onHero,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "vs same days the month before",
                        style = MaterialTheme.typography.bodySmall,
                        color = onHero.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 10.dp)) {
                LabeledFigure("In") {
                    MoneyText(hero.income, whole = true, color = onHero, style = MaterialTheme.typography.titleMedium)
                }
                LabeledFigure("Net") {
                    MoneyText(hero.net, whole = true, color = onHero, style = MaterialTheme.typography.titleMedium)
                }
                LabeledFigure("Saved") {
                    Text(
                        hero.savingsRate?.let { "${"%.1f".format(it)}%" } ?: "—",
                        style = MaterialTheme.typography.titleMedium.tabular(),
                        color = onHero,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroNote(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color.copy(alpha = 0.75f))
}

@Composable
private fun LabeledFigure(label: String, figure: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
        figure()
    }
}

@Composable
private fun StaleCard(
    accounts: List<FreshnessAccount>,
    onOpenAccount: (FreshnessAccount) -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onCard = MaterialTheme.colorScheme.onTertiaryContainer
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = onCard, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${accounts.size} statement${if (accounts.size == 1) "" else "s"} overdue",
                    style = MaterialTheme.typography.titleSmall,
                    color = onCard,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onOpenImport) { Text("Import") }
            }
            accounts.take(5).forEach { account ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Show transactions") { onOpenAccount(account) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        account.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = onCard,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    account.expectedOn?.let {
                        Text("expected ${DisplayDates.short(it)}", style = MaterialTheme.typography.labelMedium, color = onCard.copy(alpha = 0.8f))
                    }
                }
            }
            if (accounts.size > 5) {
                Text(
                    "and ${accounts.size - 5} more",
                    style = MaterialTheme.typography.labelMedium,
                    color = onCard.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RankedRow(bucket: RankedBucket, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
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
                    "${"%.1f".format(bucket.share * 100)}% · ${bucket.rows}",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
