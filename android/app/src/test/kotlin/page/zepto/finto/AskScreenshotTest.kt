package page.zepto.finto

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import page.zepto.finto.core.model.AskStep
import page.zepto.finto.core.model.ChangeFields
import page.zepto.finto.core.model.ChangeProposal
import page.zepto.finto.core.model.ChangeSample
import page.zepto.finto.core.model.ChangeSet
import page.zepto.finto.core.model.CurrencyAmount
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.SpendingChange
import page.zepto.finto.core.model.QueryResult
import page.zepto.finto.core.model.SummaryRow
import page.zepto.finto.core.model.TotalRow
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.TxnPage
import page.zepto.finto.feature.ask.AskScreen
import page.zepto.finto.feature.ask.AskTurn
import page.zepto.finto.feature.ask.AskUiState
import page.zepto.finto.feature.ask.TurnState
import page.zepto.finto.ui.TopLevelDestination

/** Ask: the empty thread, an answer streaming in, finished answers and a failed one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AskScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, state: AskUiState) =
        compose.captureScreen(name, dark, TopLevelDestination.More) { Ask(state) }

    @Test
    fun empty() = capture("ask-empty", state = AskUiState())

    @Test
    fun streaming() = capture("ask-streaming", state = AskUiState(turns = listOf(AskSample.streaming)))

    @Test
    fun finished() = capture("ask-finished", state = AskUiState(turns = listOf(AskSample.monthly), accountNames = AskSample.names))

    /** The whole finished turn on one tall screen: answer, tiles, chart, scope and timing. */
    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun finishedFull() = capture("ask-finished-full", state = AskUiState(turns = listOf(AskSample.monthly), accountNames = AskSample.names))

    @Test
    fun finishedDark() = capture("ask-finished-dark", dark = true, state = AskUiState(turns = listOf(AskSample.monthly), accountNames = AskSample.names))

    @Test
    fun rows() = capture("ask-rows", state = AskUiState(turns = listOf(AskSample.purchases), accountNames = AskSample.names))

    @Test
    fun error() = capture("ask-error", state = AskUiState(turns = listOf(AskSample.dining, AskSample.failed)))

    @Test
    fun proposal() = capture("ask-proposal", state = AskUiState(turns = listOf(AskSample.fix)))

    @Test
    fun proposalApplied() = capture("ask-proposal-applied", dark = true, state = AskUiState(turns = listOf(AskSample.fixed)))

    @Composable
    private fun Ask(state: AskUiState) = AskScreen(
        state = state,
        onBack = {}, onAsk = { false }, onStop = {}, onNewConversation = {}, onToggleRows = {}, onOpenLedger = { _, _ -> },
    )
}

private object AskSample {
    val names = mapOf("amex_hk_main" to "AMEX Platinum", "hsbc_hk_current" to "HSBC Current")

    private val wrong = ChangeFields(kind = "purchase", category = "fees")
    private val right = ChangeFields(kind = "cc_payment", category = "transfers", subcategory = "card_payment")

    private val cardPayments = ChangeProposal(
        id = "c1",
        title = "Mark 13 HSBC card bill payments as card payments",
        set = ChangeSet(kind = "cc_payment", category = "transfers", subcategory = "card_payment"),
        rows = 13,
        totals = listOf(CurrencyAmount("HKD", -11_401_460)),
        spending = listOf(SpendingChange("HKD", -11_401_460)),
        sample = listOf(
            ChangeSample("t1", "2026-11-01", "HSBC CREDIT CARDS", "mox_credit", "Mox Credit", Money(-2_839_194, "HKD"), wrong, right),
            ChangeSample("t2", "2026-10-20", "HSBC CREDIT CARDS", "mox_credit", "Mox Credit", Money(-160_216, "HKD"), wrong, right),
            ChangeSample("t3", "2026-10-02", "HSBC CREDIT CARDS", "mox_credit", "Mox Credit", Money(-1_510_000, "HKD"), wrong, right),
        ),
    )

    val fix = AskTurn(
        id = 7,
        question = "Can you fix these? I think they are card bill payments",
        state = TurnState.Done,
        steps = listOf(
            AskStep(id = "s1", name = "search_transactions", state = "done", label = "Finding rows · “HSBC Credit Cards”"),
            AskStep(id = "s2", name = "propose_changes", state = "done", label = "Preparing a change · Mark 13 HSBC card bill payments as card payments"),
        ),
        text = "These 13 \"HSBC Credit Cards\" rows on Mox Credit are card bill payments. Applying the change takes **HKD 114,014.60** out of spending.",
        data = QueryResult(ok = true, proposals = listOf(cardPayments)),
        seconds = 9.2,
    )

    val fixed = fix.copy(data = QueryResult(ok = true, proposals = listOf(cardPayments.copy(status = "applied", applied = 13, skipped = 0))))

    private fun money(amount: Long, currency: String = "HKD") = Money(amount, currency)

    private fun row(bucket: String, spend: Long, currency: String = "HKD", count: Int = 12) = SummaryRow(
        bucket = bucket, currency = currency, txnCount = count,
        net = money(-spend, currency), spend = money(spend, currency), income = money(0, currency),
    )

    private fun filter(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (key, value) ->
            key to when (value) {
                is List<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        },
    )

    private fun step(id: String, label: String, state: String = "done") = AskStep(id = id, name = "ledger_summary", state = state, label = label)

    private val months = listOf(
        "2026-01" to 312_440L, "2026-02" to 288_015L, "2026-03" to 401_260L, "2026-04" to 356_980L, "2026-05" to 377_120L,
        "2026-06" to 342_650L, "2026-07" to 418_335L, "2026-08" to 391_240L, "2026-09" to 421_085L, "2026-10" to 365_510L,
    )

    private val groceriesTotals = listOf(
        TotalRow("HKD", txnCount = 142, net = money(-3_674_635), spend = money(3_674_635), income = money(0)),
        TotalRow("USD", txnCount = 4, net = money(-6_132, "USD"), spend = money(6_132, "USD"), income = money(1_500, "USD")),
    )

    val streaming = AskTurn(
        id = 2,
        question = "What did I spend at supermarkets this year, by month",
        state = TurnState.Asking,
        steps = listOf(step("s1", "Read categories and accounts"), step("s2", "Summed groceries by month")),
        text = "Supermarkets came to **HKD 36,746.35** this year, about HKD 3,675 a month. September was the busiest month",
        data = QueryResult(totals = groceriesTotals),
    )

    private val monthlyAnswer = """
        Supermarkets came to **HKD 36,746.35** across 142 rows this year, plus a little in USD.

        | Month | HKD | USD |
        |---|---|---|
        | Aug | 3,912.40 | 47.00 |
        | Sep | 4,210.85 | — |
        | Oct | 3,655.10 | 14.32 |

        - Highest: **September**, after the Mid-Autumn shop
        - Lowest: February
    """.trimIndent()

    val monthly = AskTurn(
        id = 3,
        question = "What did I spend at supermarkets this year, by month",
        state = TurnState.Done,
        steps = listOf(step("s1", "Read categories and accounts"), step("s2", "Summed groceries by month")),
        text = monthlyAnswer,
        data = QueryResult(
            groupBy = "month",
            totals = groceriesTotals,
            rows = months.map { (month, spend) -> row(month, spend) } + row("2026-08", 4_700, "USD", 1) + row("2026-10", 1_432, "USD", 2),
            filter = filter("from" to "2026-01-01", "to" to "2026-10-31", "categories" to listOf("groceries")),
        ),
        seconds = 4.2,
    )

    private fun txn(id: String, date: String, description: String, merchant: String?, amount: Long, account: String) = Txn(
        id = id, date = date, accountId = account, accountName = names[account] ?: account, accountCurrency = "HKD",
        institutionId = "amex_hk", description = description, merchant = merchant, booked = money(amount), kind = "purchase",
    )

    val purchases = AskTurn(
        id = 4,
        question = "Biggest purchases in October",
        state = TurnState.Done,
        steps = listOf(step("s1", "Searched October transactions")),
        text = "Your largest purchase in October was **Cathay Pacific** at HKD 8,120.00. The top eight are below.",
        data = QueryResult(
            groupBy = "merchant",
            rows = listOf(
                row("Cathay Pacific", 812_000), row("Apple Store", 249_750), row("Citysuper", 128_040), row("Wellcome", 96_420),
                row("Flat White Café", 31_200), row("MTR", 18_660), row("Adobe", 16_800), row("Netflix", 9_300),
                row("Uniqlo", 8_990), row("7-Eleven", 4_120),
            ),
            transactions = TxnPage(
                total = 3,
                items = listOf(
                    txn("t1", "2026-10-21", "CATHAY PACIFIC AIRWAYS", "Cathay Pacific", -812_000, "amex_hk_main"),
                    txn("t2", "2026-10-12", "APPLE STORE CANTON RD", "Apple Store", -249_750, "amex_hk_main"),
                    txn("t3", "2026-10-14", "CITYSUPER IFC MALL HONG KONG", "Citysuper", -128_040, "amex_hk_main"),
                ),
            ),
            filter = filter("from" to "2026-10-01", "to" to "2026-10-31", "accounts" to listOf("amex_hk_main"), "maxAmount" to -100_000),
            cached = true,
        ),
        seconds = 0.3,
    )

    val dining = AskTurn(
        id = 5,
        question = "How much did I spend on dining last month",
        state = TurnState.Done,
        steps = listOf(step("s1", "Totalled dining for October")),
        text = "You spent **HKD 4,912.30** on dining in October, across 38 rows.",
        data = QueryResult(
            totals = listOf(TotalRow("HKD", txnCount = 38, net = money(-491_230), spend = money(491_230), income = money(0))),
            filter = filter("from" to "2026-10-01", "to" to "2026-10-31", "categories" to listOf("dining")),
        ),
        seconds = 2.8,
    )

    val failed = AskTurn(
        id = 6,
        question = "Uncategorised transactions over 1,000",
        state = TurnState.Error,
        error = "The answer was cut off. Try again.",
        steps = listOf(step("s1", "Searched uncategorised rows", state = "error")),
    )
}
