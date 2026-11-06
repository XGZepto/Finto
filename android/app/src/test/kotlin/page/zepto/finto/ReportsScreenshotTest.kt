package page.zepto.finto

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import page.zepto.finto.core.designsystem.theme.FintoTheme
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.Composition
import page.zepto.finto.core.model.CompositionSeries
import page.zepto.finto.core.model.ConvertedMoney
import page.zepto.finto.core.model.Coverage
import page.zepto.finto.core.model.CoverageAccount
import page.zepto.finto.core.model.FlowTotals
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.SummaryRow
import page.zepto.finto.feature.reports.DateWindow
import page.zepto.finto.feature.reports.MonthSpend
import page.zepto.finto.feature.reports.Report
import page.zepto.finto.feature.reports.ReportMath
import page.zepto.finto.feature.reports.ReportsScreen
import page.zepto.finto.feature.reports.ReportsUiState
import page.zepto.finto.feature.reports.TimelineScale
import page.zepto.finto.feature.reports.TimelineScreen
import page.zepto.finto.feature.reports.TimelineUiState
import page.zepto.finto.ui.TopLevelDestination
import java.time.LocalDate
import java.time.YearMonth

/** Reports and Timeline, rendered from fixed invented data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ReportsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reportsLight() = compose.captureScreen("reports-light", tab = TopLevelDestination.Reports) { Reports(ReportSample.quarter) }

    @Test
    fun reportsDark() = compose.captureScreen("reports-dark", dark = true, tab = TopLevelDestination.Reports) { Reports(ReportSample.quarter) }

    @Test
    fun reportsSelectedMonth() =
        compose.captureScreen("reports-selected-month", tab = TopLevelDestination.Reports) { Reports(ReportSample.october) }

    @Test
    fun reportsEmpty() = compose.captureScreen("reports-empty", tab = TopLevelDestination.Reports) { Reports(ReportSample.empty) }

    @Test
    fun timelineShare() = compose.captureScreen("timeline-share", tab = TopLevelDestination.More) { Timeline(TimelineSample.state) }

    @Test
    fun timelineAmount() = compose.captureScreen("timeline-amount", dark = true, tab = TopLevelDestination.More) {
        Timeline(TimelineSample.state.copy(scale = TimelineScale.Amount))
    }

    /** The coverage grid sits below the chart, so this one scrolls to it before recording. */
    @Test
    fun coverage() = captureScrolledTo("timeline-coverage", "coverage", TopLevelDestination.More) { Timeline(TimelineSample.state) }

    /** The ranked buckets below the chart, down to the note on unconverted currencies. */
    @Test
    fun reportsBreakdown() =
        captureScrolledTo("reports-breakdown", "unconvertible", TopLevelDestination.Reports) { Reports(ReportSample.october) }

    /** As [captureScreen], scrolling the screen's list to [key] before recording. */
    private fun captureScrolledTo(name: String, key: String, tab: TopLevelDestination, content: @Composable () -> Unit) {
        compose.setContent {
            FintoTheme(darkTheme = false, dynamicColor = false) {
                NavigationSuiteScaffold(
                    navigationSuiteItems = {
                        TopLevelDestination.entries.forEach { destination ->
                            item(
                                selected = destination == tab,
                                onClick = {},
                                icon = { Icon(if (destination == tab) destination.selectedIcon else destination.icon, null) },
                                label = { Text(destination.label) },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { content() }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(key)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun Reports(state: ReportsUiState) = ReportsScreen(
        state = state,
        onPeriodChange = {}, onAccountChange = {}, onSplitChange = {}, onToggleMonth = {}, onClearMonths = {},
        onRefresh = {}, onErrorShown = {}, onOpenLedger = { _, _ -> },
    )

    @Composable
    private fun Timeline(state: TimelineUiState) = TimelineScreen(
        state = state,
        onBack = {}, onDimensionChange = {}, onScaleChange = {}, onRefresh = {}, onErrorShown = {}, onOpenLedger = { _, _ -> },
    )
}

/** Three months of an invented household ledger, in HKD minor units. */
private object ReportSample {
    private fun hkd(amount: Long) = Money(amount, "HKD")

    private fun row(bucket: String, spend: Long, income: Long = 0) = SummaryRow(
        bucket = bucket,
        currency = "HKD",
        txnCount = 4,
        net = hkd(income - spend),
        spend = hkd(spend),
        income = hkd(income),
        spendConverted = ConvertedMoney(spend, "HKD", ok = true),
        incomeConverted = ConvertedMoney(income, "HKD", ok = true),
    )

    private fun totals(spend: Long, income: Long) = FlowTotals(net = hkd(income - spend), spend = hkd(spend), income = hkd(income))

    private val accounts = listOf(
        Account("amex_platinum", "amex", "AMEX Platinum", "credit_card", "HKD"),
        Account("hsbc_current", "hsbc", "HSBC Current", "current", "HKD"),
        Account("hsbc_savings", "hsbc", "HSBC Savings", "savings", "HKD"),
        Account("mox", "mox", "Mox", "current", "HKD"),
    )

    private val months = listOf(
        MonthSpend("2026-08", 3_840_000),
        MonthSpend("2026-09", 5_235_000),
        MonthSpend("2026-10", 6_128_000),
        MonthSpend("2026-11", 1_094_900),
    )

    private val categories = listOf(
        "housing" to 5_850_000L, "shopping" to 4_123_000L, "travel" to 2_388_000L, "dining" to 1_243_600L,
        "groceries" to 987_200L, "utilities" to 405_100L, "transport" to 342_000L, "health" to 256_300L,
        "subscriptions" to 197_700L, "insurance" to 182_000L, "education" to 120_000L, "gifts" to 98_000L,
        "personal_care" to 64_000L, "pets" to 41_000L,
    )

    private val base = ReportsUiState(
        loading = false,
        currency = "HKD",
        accounts = accounts,
        window = DateWindow(LocalDate.parse("2026-08-06"), LocalDate.parse("2026-11-06")),
    )

    val quarter = base.copy(
        report = Report(
            split = "category",
            scope = LedgerFilter(from = "2026-08-06", to = "2026-11-06", spendingOnly = true),
            totals = totals(spend = 16_297_900, income = 20_400_000),
            prior = totals(spend = 15_120_000, income = 20_400_000),
            bands = ReportMath.bands(categories.map { (bucket, spend) -> row(bucket, spend) } + row("salary", 0, 20_400_000), "HKD"),
            months = months,
            mean = 5_067_667,
            unconvertible = emptyList(),
        ),
    )

    val october = base.copy(
        selectedMonths = listOf("2026-10"),
        report = Report(
            split = "category",
            scope = LedgerFilter(months = listOf("2026-10"), spendingOnly = true),
            totals = totals(spend = 6_128_000, income = 6_800_000),
            prior = totals(spend = 5_235_000, income = 6_800_000),
            bands = ReportMath.bands(
                listOf(
                    row("housing", 1_950_000), row("travel", 1_688_000), row("shopping", 1_204_500),
                    row("dining", 486_300), row("groceries", 341_200), row("utilities", 135_000),
                    row("transport", 118_000), row("health", 98_000), row("subscriptions", 65_900),
                    row("salary", 0, 6_800_000),
                ),
                "HKD",
            ),
            months = months,
            mean = 5_067_667,
            unconvertible = listOf("XAU"),
        ),
    )

    val empty = base.copy(
        accountId = "hsbc_savings",
        report = Report(
            split = "category",
            scope = LedgerFilter(from = "2026-08-06", to = "2026-11-06", accounts = listOf("hsbc_savings")),
            totals = totals(spend = 0, income = 125_000),
            prior = totals(spend = 0, income = 118_000),
            bands = emptyList(),
            months = emptyList(),
            mean = null,
            unconvertible = emptyList(),
        ),
    )
}

/** Nineteen months of spending by category, and what backs each account month by month. */
private object TimelineSample {
    private val months = (0 until 19).map { YearMonth.of(2025, 5).plusMonths(it.toLong()).toString() }

    private fun series(bucket: String, value: (Int) -> Long): CompositionSeries {
        val values = months.indices.map(value)
        return CompositionSeries(bucket, total = values.sum(), values = values)
    }

    private val composition = Composition(
        dimension = "category",
        currency = "HKD",
        months = months,
        series = listOf(
            series("shopping") { 1_400_000L + (it * 731_000L) % 1_900_000L },
            series("travel") { if (it % 5 == 2) 3_600_000L else 420_000L + (it * 97_000L) % 300_000L },
            series("housing") { 1_950_000L },
            series("dining") { 380_000L + (it * 53_000L) % 260_000L },
            series("groceries") { 240_000L + (it * 31_000L) % 120_000L },
            series("utilities") { 180_000L + (it % 3) * 25_000L },
            series("health") { if (it % 4 == 1) 410_000L else 60_000L },
            series("subscriptions") { 51_400L },
            series("other") { 40_000L + (it * 17_000L) % 90_000L },
        ),
        unconvertibleCurrencies = listOf("XAU"),
    )

    private fun cells(vararg runs: Pair<String, Int>) = runs.flatMap { (cell, count) -> List(count) { cell } }

    private fun account(id: String, name: String, cells: List<String>) = CoverageAccount(
        accountId = id,
        accountName = name,
        cells = cells,
        statementMonths = cells.count { it == "statement" },
        exportMonths = cells.count { it == "export" },
        gapMonths = cells.count { it == "none" },
    )

    private val coverage = Coverage(
        months = months,
        accounts = listOf(
            account("amex_platinum", "AMEX Platinum", cells("statement" to 17, "export" to 2)),
            account("hsbc_current", "HSBC Current", cells("statement" to 9, "none" to 1, "statement" to 8, "export" to 1)),
            account("hsbc_savings", "HSBC Savings", cells("pre" to 5, "statement" to 14)),
            account("mox", "Mox", cells("export" to 18, "none" to 1)),
            account("citi_rewards", "Citi Rewards Card", cells("pre" to 2, "export" to 6, "none" to 2, "statement" to 9)),
        ),
    )

    val state = TimelineUiState(
        loading = false,
        currency = "HKD",
        composition = composition,
        coverage = coverage,
    )
}
