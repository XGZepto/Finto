package page.zepto.finto

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import page.zepto.finto.core.data.repository.StagedFile
import page.zepto.finto.core.designsystem.theme.FintoTheme
import page.zepto.finto.core.model.ConvertedMoney
import page.zepto.finto.core.model.FreshnessAccount
import page.zepto.finto.core.model.ImportAgent
import page.zepto.finto.core.model.ImportQuality
import page.zepto.finto.core.model.InOut
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MonthFlow
import page.zepto.finto.core.model.Position
import page.zepto.finto.core.model.QualityCheck
import page.zepto.finto.core.model.RankedBucket
import page.zepto.finto.core.model.SampleRow
import page.zepto.finto.core.model.StagePreview
import page.zepto.finto.core.model.Txn
import page.zepto.finto.feature.accounts.AccountGroup
import page.zepto.finto.feature.accounts.AccountsScreen
import page.zepto.finto.feature.accounts.AccountsUiState
import page.zepto.finto.feature.auth.LoginScreen
import page.zepto.finto.feature.auth.LoginUiState
import page.zepto.finto.feature.imports.ImportScreen
import page.zepto.finto.feature.imports.ImportStep
import page.zepto.finto.feature.imports.ImportUiState
import page.zepto.finto.feature.settings.SettingsScreen
import page.zepto.finto.feature.settings.SettingsUiState
import page.zepto.finto.feature.summary.Hero
import page.zepto.finto.feature.summary.SummaryScreen
import page.zepto.finto.feature.summary.SummaryUiState
import page.zepto.finto.feature.transactions.ActivityItem
import page.zepto.finto.feature.transactions.ActivityScreen
import page.zepto.finto.feature.transactions.ActivityUiState
import page.zepto.finto.feature.transactions.DetailState
import page.zepto.finto.feature.transactions.TransactionDetail
import page.zepto.finto.ui.TopLevelDestination
import java.time.LocalDate
import java.time.YearMonth

/**
 * Every screen, rendered from fixed invented data on a JVM. Records with
 * `./gradlew :app:recordRoborazziDebug` and checks with `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, tab: TopLevelDestination? = null, content: @Composable () -> Unit) {
        compose.setContent {
            FintoTheme(darkTheme = dark, dynamicColor = false) {
                if (tab == null) {
                    Surface(Modifier.fillMaxSize()) { content() }
                } else {
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
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun login() = capture("login") {
        LoginScreen(
            state = LoginUiState(server = "finto.zepto.page", identifier = "owner"),
            onServerChange = {}, onIdentifierChange = {}, onPasswordChange = {}, onSubmit = {}, onDemo = {},
            animateEntrance = false,
        )
    }

    @Test
    fun summaryLight() = capture("summary-light", tab = TopLevelDestination.Summary) { Summary() }

    @Test
    fun summaryDark() = capture("summary-dark", dark = true, tab = TopLevelDestination.Summary) { Summary() }

    @Test
    fun activityLight() = capture("activity-light", tab = TopLevelDestination.Activity) { Activity() }

    @Test
    fun activityDark() = capture("activity-dark", dark = true, tab = TopLevelDestination.Activity) { Activity() }

    @Test
    fun transactionDetail() = capture("transaction-detail") {
        TransactionDetail(DetailState.Loaded(Sample.coffee, Sample.coffee))
    }

    @Test
    fun accounts() = capture("accounts", tab = TopLevelDestination.Accounts) {
        AccountsScreen(state = Sample.accounts, onRefresh = {}, onOpenAccount = { _, _ -> })
    }

    @Test
    fun importPreview() = capture("import-preview", tab = TopLevelDestination.Import) {
        ImportScreen(
            state = ImportUiState(step = ImportStep.Ready(Sample.staged, Sample.preview)),
            onPick = {}, onConfirm = {}, onReset = {}, onOpenActivity = {},
        )
    }

    @Test
    fun settings() = capture("settings", dark = true, tab = TopLevelDestination.Settings) {
        SettingsScreen(
            state = SettingsUiState(username = "owner", email = "owner@example.com", server = "finto.zepto.page", currency = "HKD"),
            appVersion = "0.1.0",
            onCurrencyChange = {}, onThemeModeChange = {}, onDynamicColorChange = {}, onSignOut = {},
        )
    }

    @Composable
    private fun Summary() = SummaryScreen(state = Sample.summary, onRangeChange = {}, onRefresh = {}, onOpenCategory = {})

    @Composable
    private fun Activity() {
        val settled = LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true))
        val items = flowOf(PagingData.from(Sample.activity, sourceLoadStates = settled)).collectAsLazyPagingItems()
        ActivityScreen(
            state = ActivityUiState(accounts = emptyList()),
            items = items,
            onBack = {}, onQueryChange = {}, onSpendingOnlyChange = {}, onAccountChange = {}, onOpen = {},
        )
    }
}

/** Invented figures shaped like the demo ledger. */
private object Sample {
    private fun hkd(amount: Long) = Money(amount, "HKD")
    private val today: LocalDate = LocalDate.now()

    val summary = SummaryUiState(
        loading = false,
        currency = "HKD",
        windowStart = "2025-12-01",
        hero = Hero(
            anchor = today, lagging = false, exact = true,
            spent = hkd(1_834_560), previous = hkd(1_620_000), income = hkd(4_200_000),
        ),
        ranked = listOf(
            RankedBucket("dining", hkd(3_812_000), hkd(0), 142, 0.31),
            RankedBucket("groceries", hkd(2_460_000), hkd(0), 96, 0.20),
            RankedBucket("travel", hkd(1_985_000), hkd(0), 18, 0.16),
            RankedBucket("subscriptions", hkd(1_221_000), hkd(0), 48, 0.10),
            RankedBucket("utilities", hkd(980_000), hkd(0), 24, 0.08),
            RankedBucket("transport", hkd(860_000), hkd(0), 130, 0.07),
            RankedBucket("health", hkd(540_000), hkd(0), 9, 0.04),
        ),
        months = (11 downTo 0).map { back ->
            val month = YearMonth.from(today).minusMonths(back.toLong())
            MonthFlow(month.toString(), hkd(1_500_000L + (back * 137_000L) % 900_000L), hkd(if (back % 3 == 0) 4_200_000 else 3_900_000))
        },
        stale = listOf(FreshnessAccount("hsbc_hk_current", "HSBC Current", status = "stale", daysOverdue = 6)),
    )

    private fun txn(id: String, date: LocalDate, description: String, merchant: String?, amount: Long, account: String, category: String?, kind: String = "purchase") =
        Txn(
            id = id, date = date.toString(), accountId = account, accountName = account, accountCurrency = "HKD",
            institutionId = "hsbc_hk", description = description, merchant = merchant, booked = hkd(amount),
            kind = kind, category = category,
        )

    val coffee = txn("t1", today, "FLAT WHITE CAFE CENTRAL HK", "Flat White Café", -4_800, "AMEX Platinum", "dining")
        .copy(cardLast4 = "3007", cardholderName = "ALEX E", subcategory = "cafes", tags = listOf("weekday"),
            details = mapOf("reference" to kotlinx.serialization.json.JsonPrimitive("74521308")))

    val activity: List<ActivityItem> = listOf(
        ActivityItem.Day(today),
        ActivityItem.Row(coffee),
        ActivityItem.Row(txn("t2", today, "WELLCOME QUARRY BAY", "Wellcome", -38_250, "HSBC Current", "groceries")),
        ActivityItem.Row(txn("t3", today, "ADOBE SUBSCRIPTION", "Adobe", -16_800, "AMEX Platinum", "subscriptions")),
        ActivityItem.Day(today.minusDays(1)),
        ActivityItem.Row(txn("t4", today.minusDays(1), "SALARY ACME LTD", null, 4_200_000, "HSBC Current", "income", kind = "income")),
        ActivityItem.Row(txn("t5", today.minusDays(1), "MTR TRAVEL", "MTR", -1_240, "Mox", "transport")),
        ActivityItem.Row(txn("t6", today.minusDays(1), "PAYMENT RECEIVED - THANK YOU", null, 2_522_895, "AMEX Platinum", null, kind = "cc_payment")),
        ActivityItem.Day(today.minusDays(3)),
        ActivityItem.Row(txn("t7", today.minusDays(3), "CATHAY PACIFIC", "Cathay Pacific", -812_000, "AMEX Platinum", "travel")),
        ActivityItem.Row(txn("t8", today.minusDays(3), "HKD Interest", null, 7, "Mox", null, kind = "interest")),
    )

    private fun position(id: String, name: String, type: String, amount: Long, currency: String = "HKD", converted: Long = amount, basis: String = "statement") =
        Position(
            accountId = id, accountName = name, institutionId = "bank", accountType = type, currency = currency, txnCount = 40,
            balance = Money(amount, currency), balanceConverted = ConvertedMoney(converted, "HKD", ok = true),
            basis = basis, basisDate = today.minusDays(5).toString(),
        )

    val accounts = AccountsUiState(
        loading = false,
        currency = "HKD",
        netWorth = hkd(48_212_350),
        groups = listOf(
            AccountGroup("checking", listOf(position("hsbc", "HSBC Current", "checking", 12_402_100)), hkd(12_402_100)),
            AccountGroup("savings", listOf(position("hsbc_sav", "HSBC Savings", "savings", 30_120_000)), hkd(30_120_000)),
            AccountGroup(
                "multi_currency",
                listOf(
                    position("wise", "Wise", "multi_currency", 640_250, basis = "movements"),
                    position("wise", "Wise", "multi_currency", 412_000, currency = "USD", converted = 3_205_000, basis = "movements"),
                ),
                hkd(3_845_250),
            ),
            AccountGroup("credit_card", listOf(position("amex", "AMEX Platinum", "credit_card", -2_155_000)), hkd(-2_155_000)),
        ),
    )

    val staged = StagedFile("Mox_Credit_Nov2026.pdf", 308_565, "application/pdf", "/tmp/x.pdf")

    val preview = StagePreview(
        sha256 = "e706bf",
        filename = "Mox_Credit_Nov2026.pdf",
        sizeBytes = 308_565,
        parser = "mox_credit",
        txnCount = 9,
        periodStart = "2026-10-05",
        periodEnd = "2026-11-04",
        totals = mapOf("HKD" to InOut(inflow = 11_480_612, out = -6_639_669, rows = 9)),
        quality = ImportQuality(
            grade = "ok",
            checks = listOf(
                QualityCheck("new", "ok", "New statement", "No earlier import of this file or its rows."),
                QualityCheck("continuity", "ok", "Continues the previous statement", "Starts the day after 4 Oct."),
                QualityCheck("balance", "info", "One printed balance", "Reconciles against the next statement."),
            ),
        ),
        agent = ImportAgent(
            verdict = "import",
            headline = "Looks right: 9 rows for Mox Credit, Oct 5 – Nov 4",
            notes = listOf("Three card payments will link to the Mox bank account."),
        ),
        sample = listOf(
            SampleRow("2026-10-13", "Mox Credit Payment Mox Credit 繳款", 2_522_895, "HKD"),
            SampleRow("2026-10-13", "American Express Cards", -30_730, "HKD"),
            SampleRow("2026-10-17", "HSBC Credit Cards", -250_362, "HKD"),
        ),
    )
}
