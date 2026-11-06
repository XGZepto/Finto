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
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.NetWorthPoint
import page.zepto.finto.core.model.SummaryRow
import page.zepto.finto.feature.accounts.AccountDetailActions
import page.zepto.finto.feature.accounts.AccountDetailScreen
import page.zepto.finto.feature.accounts.AccountDetailUiState
import page.zepto.finto.feature.accounts.AccountFlow
import page.zepto.finto.feature.accounts.AccountFlowRow
import page.zepto.finto.feature.accounts.AccountRow
import page.zepto.finto.feature.accounts.AccountsLogic
import page.zepto.finto.feature.accounts.CardLineage
import page.zepto.finto.feature.accounts.FlowNode
import page.zepto.finto.feature.accounts.InternalTransfer
import page.zepto.finto.feature.accounts.SettledWorth
import page.zepto.finto.feature.accounts.AccountsScreen
import page.zepto.finto.feature.accounts.AccountsUiState
import page.zepto.finto.feature.auth.LoginScreen
import page.zepto.finto.feature.auth.LoginUiState
import page.zepto.finto.feature.imports.ImportScreen
import page.zepto.finto.feature.imports.ImportStep
import page.zepto.finto.feature.imports.ImportUiState
import page.zepto.finto.feature.imports.ReviewLoad
import page.zepto.finto.core.model.ImportedFile
import page.zepto.finto.core.model.ImportResult
import page.zepto.finto.core.model.ImportReview
import page.zepto.finto.core.model.ReconcileSummary
import page.zepto.finto.core.model.ReviewCategories
import page.zepto.finto.core.model.ReviewLinks
import page.zepto.finto.core.model.ReviewModel
import page.zepto.finto.core.model.ReviewSummary
import page.zepto.finto.core.model.ReviewedStatement
import page.zepto.finto.core.model.UncategorisedGroup
import page.zepto.finto.core.model.UnlinkedRow
import page.zepto.finto.feature.settings.SettingsScreen
import page.zepto.finto.feature.settings.SettingsUiState
import page.zepto.finto.feature.summary.Hero
import page.zepto.finto.feature.summary.SummaryScreen
import page.zepto.finto.feature.summary.SummaryUiState
import page.zepto.finto.feature.transactions.ActivityItem
import page.zepto.finto.feature.transactions.ActivityScreen
import page.zepto.finto.feature.transactions.ActivityUiState
import page.zepto.finto.feature.transactions.DetailState
import page.zepto.finto.feature.transactions.EditorState
import page.zepto.finto.core.model.CategorySuggestion
import page.zepto.finto.feature.transactions.TransactionDetail
import page.zepto.finto.ui.MoreScreen
import page.zepto.finto.core.model.ApiKey
import page.zepto.finto.core.model.Translations
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

    private fun capture(name: String, dark: Boolean = false, tab: TopLevelDestination? = null, content: @Composable () -> Unit) =
        compose.captureScreen(name, dark, tab, content = content)

    @Test
    fun more() = capture("more", tab = TopLevelDestination.More) { MoreScreen(onOpen = {}) }

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
    fun categoryPicker() = capture("category-picker") {
        TransactionDetail(
            DetailState.Loaded(Sample.coffee, Sample.coffee),
            editor = EditorState(
                categories = listOf("dining", "groceries", "transport", "shopping", "travel", "health", "bills", "entertainment", "fees", "income", "gifts"),
                suggestion = CategorySuggestion("dining", "coffee", "Blue Bottle Coffee", confidence = 0.92),
            ),
            startInPicker = true,
        )
    }

    @Test
    fun accounts() = capture("accounts", tab = TopLevelDestination.Accounts) {
        AccountsScreen(state = Sample.accounts, onRefresh = {}, onOpenAccount = {})
    }

    @Test
    fun accountDetail() = capture("account-detail", tab = TopLevelDestination.Accounts) {
        AccountDetailScreen(state = Sample.accountDetail, actions = AccountDetailActions())
    }

    @Test
    fun accountGroup() = capture("account-group", dark = true, tab = TopLevelDestination.Accounts) {
        val group = Sample.accounts.groups.first { !it.single }
        AccountDetailScreen(
            state = Sample.accountDetail.copy(
                title = group.name, subtitle = "HSBC · 2 subaccounts · 1 cards", scope = group.children.map { it.account.id },
                account = null, row = null, group = group, lineages = emptyList(), flow = AccountFlow(emptyList(), emptyList()),
            ),
            actions = AccountDetailActions(),
        )
    }

    @Test
    fun importPreview() = capture("import-preview", tab = TopLevelDestination.More) {
        ImportScreen(
            state = ImportUiState(step = ImportStep.Ready(Sample.staged, Sample.preview)),
            onPick = {}, onConfirm = {}, onReset = {}, onOpenActivity = {},
        )
    }

    @Test
    fun importHome() = capture("import-home", tab = TopLevelDestination.More) {
        ImportScreen(
            state = ImportUiState(
                historyLoading = false,
                history = listOf(
                    ImportedFile("sf-2", "/in/2026-11__Mox_Credit_Nov2026.pdf", accountId = "mox_credit", importedAt = "2026-11-05T10:00:00Z", txnCount = 9, reviewStatus = "attention"),
                    ImportedFile("sf-1", "/in/2026-11__Mox_Bank_Oct2026.pdf", accountId = "mox_bank", importedAt = "2026-11-05T09:58:00Z", txnCount = 43, reviewStatus = "clean"),
                    ImportedFile("sf-0", "/in/2026-10__HSBC_One_Sep.pdf", accountId = "hsbc_one", importedAt = "2026-10-03T08:00:00Z", txnCount = 61),
                ),
                historyReviewId = "sf-2",
                historyReview = ReviewLoad.Done(Sample.review),
            ),
            onPick = {}, onConfirm = {}, onReset = {}, onOpenActivity = {},
        )
    }

    @Test
    fun importDone() = capture("import-done", dark = true, tab = TopLevelDestination.More) {
        ImportScreen(
            state = ImportUiState(
                step = ImportStep.Done(
                    Sample.preview,
                    ImportResult("imported", txns = 9, statementFileId = "sf-2", periodStart = "2026-10-06", periodEnd = "2026-11-05"),
                    ReconcileSummary(transactions = 1_204, transfersLinked = 2, transferCandidates = 1, refundsLinked = 1),
                ),
                review = ReviewLoad.Done(Sample.review),
            ),
            onPick = {}, onConfirm = {}, onReset = {}, onOpenActivity = {},
        )
    }

    @Test
    fun settings() = capture("settings", dark = true, tab = TopLevelDestination.More) {
        SettingsScreen(
            state = SettingsUiState(
                username = "owner", email = "owner@example.com", server = "finto.zepto.page", currency = "HKD",
                apiKeysLoading = false,
                apiKeys = listOf(ApiKey("k1", "Agent access", "fk_7Qx2", createdAt = "2026-10-01T09:00:00Z", lastUsedAt = "2026-11-05T22:14:00Z")),
            ),
            appVersion = "0.1.0",
            onCurrencyChange = {}, onThemeModeChange = {}, onDynamicColorChange = {}, onSignOut = {},
        )
    }

    @Test
    fun moreInTraditionalChinese() =
        compose.captureScreen("more-zh-hant", tab = TopLevelDestination.More, language = Translations.TRADITIONAL_CHINESE) { MoreScreen(onOpen = {}) }

    @Composable
    private fun Summary() = SummaryScreen(state = Sample.summary, onRangeChange = {}, onRefresh = {}, onOpenBucket = {})

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
        stale = listOf(FreshnessAccount("hsbc_hk_current", "HSBC Current", expectedOn = "2026-10-30", status = "stale", daysOverdue = 6)),
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

    private fun account(id: String, name: String, type: String, institution: String, group: String? = null, closed: String? = null) =
        Account(id, institution, name, type, "HKD", balanceGroup = group, maskedNumber = "••${id.length}04${id.length}", closedOn = closed)

    private val rows = listOf(
        AccountRow(account("hsbc_one_hkd", "One HKD", "checking", "hsbc", group = "hsbc_one"), listOf(position("hsbc_one_hkd", "One HKD", "checking", 12_402_100)), 1),
        AccountRow(
            account("hsbc_one_usd", "One USD", "multi_currency", "hsbc", group = "hsbc_one"),
            listOf(position("hsbc_one_usd", "One USD", "multi_currency", 412_000, currency = "USD", converted = 3_205_000)),
            0,
        ),
        AccountRow(account("hsbc_sav", "HSBC Savings", "savings", "hsbc"), listOf(position("hsbc_sav", "HSBC Savings", "savings", 30_120_000)), 0),
        AccountRow(account("amex", "AMEX Platinum", "credit_card", "amex"), listOf(position("amex", "AMEX Platinum", "credit_card", -2_155_000)), 2),
    )

    val accounts = AccountsUiState(
        loading = false,
        currency = "HKD",
        netWorth = hkd(43_572_100),
        settled = SettledWorth(today.minusDays(6).toString(), hkd(41_820_400), listOf("AMEX Platinum")),
        assets = hkd(43_975_400),
        liabilities = hkd(-2_155_000),
        series = (0 until 12).map { index ->
            val month = YearMonth.from(today).minusMonths(11L - index)
            NetWorthPoint(month.toString(), month.atEndOfMonth().toString(), hkd(36_000_000 + index * 480_000L + (index % 3) * 210_000L), complete = index < 11)
        },
        groups = AccountsLogic.groups(rows),
        flows = listOf(
            AccountFlowRow("hsbc_one_hkd", "One HKD", hkd(8_400_000), hkd(-6_120_000)),
            AccountFlowRow("amex", "AMEX Platinum", hkd(2_100_000), hkd(-2_480_500)),
        ),
        transfers = listOf(InternalTransfer("hsbc_one_hkd", "One HKD", "amex", "AMEX Platinum", hkd(2_100_000), 6)),
    )

    val accountDetail = AccountDetailUiState(
        loading = false,
        currency = "HKD",
        title = "AMEX Platinum",
        subtitle = "AMEX · Credit Card · ••5004",
        scope = listOf("amex"),
        account = rows[3].account,
        row = rows[3],
        transactionCount = 214,
        cardholders = 2,
        topCategories = listOf(
            row("dining", 924_000, 38), row("groceries", 431_000, 24), row("travel", 380_000, 4), row("shopping", 214_500, 11),
        ),
        months = (0 until 6).map { index -> row(YearMonth.from(today).minusMonths(5L - index).toString(), 180_000L + index * 41_000L, 30) },
        byHolder = listOf(row("You", 1_710_000, 150), row("Sam", 380_500, 64)),
        byKind = listOf(row("purchase", 2_090_500, 196), row("cc_payment", 0, 6), row("fee", 12_000, 2)),
        lineages = listOf(CardLineage("You", false, listOf("1001", "1009"), true), CardLineage("Sam", true, listOf("2002"), false)),
        flow = AccountFlow(
            incoming = listOf(FlowNode("int-in-hsbc_one_hkd", "One HKD", "hsbc_one_hkd", 2_100_000, 6), FlowNode("ext-in-refunds", "Refunds", null, 42_000, 2)),
            outgoing = listOf(FlowNode("ext-out-dining", "Dining", null, 924_000, 38), FlowNode("ext-out-groceries", "Groceries", null, 431_000, 24)),
        ),
        transfers = listOf(InternalTransfer("hsbc_one_hkd", "One HKD", "amex", "AMEX Platinum", hkd(2_100_000), 6)),
        recent = activity.mapNotNull { (it as? ActivityItem.Row)?.txn }.take(5),
    )

    private fun row(bucket: String, spend: Long, count: Int) = SummaryRow(bucket, "HKD", count, hkd(-spend), hkd(spend), hkd(0))

    val review = ImportReview(
        statement = ReviewedStatement("sf-2", rows = 9),
        categories = ReviewCategories(
            bySource = mapOf("rules" to 5, "model" to 3, "uncategorised" to 1),
            tags = 4,
            merchantsNamed = 6,
            topUncategorised = listOf(UncategorisedGroup("PAYPAL *STEAM GAMES", 1)),
        ),
        links = ReviewLinks(movements = 2, linked = 1, openTransferCandidates = 1, unlinked = listOf(UnlinkedRow("t9", "2026-10-28", "Mox Credit", "AUTOPAY FROM MOX BANK", "+HK$4,200.00"))),
        model = ReviewModel(available = true),
        summary = ReviewSummary("attention", "One payment has no other leg", listOf("The October repayment from Mox Bank is not in the bank statement yet."), model = "claude"),
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
