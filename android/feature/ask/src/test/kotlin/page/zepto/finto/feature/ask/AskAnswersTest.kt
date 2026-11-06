package page.zepto.finto.feature.ask

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import page.zepto.finto.core.model.AskStep
import page.zepto.finto.core.model.FintoJson
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.QueryResult
import page.zepto.finto.core.model.SummaryRow
import page.zepto.finto.core.model.TotalRow
import java.util.Locale

class AskAnswersTest {

    @Before
    fun locale() = Locale.setDefault(Locale.UK)

    private fun row(bucket: String, spend: Long, currency: String = "HKD") = SummaryRow(
        bucket = bucket, currency = currency, txnCount = 1,
        net = Money(-spend, currency), spend = Money(spend, currency), income = Money(0, currency),
    )

    private fun filter(json: String) = FintoJson.parseToJsonElement(json) as JsonObject

    @Test
    fun laterPiecesWinFieldByField() {
        val totals = listOf(TotalRow("HKD", 3, Money(-10, "HKD"), Money(10, "HKD"), Money(0, "HKD")))
        val first = AskAnswers.merge(QueryResult(), QueryResult(totals = totals, filter = filter("""{"from":"2026-10-01"}""")))
        val second = AskAnswers.merge(first, QueryResult(rows = listOf(row("dining", 5)), groupBy = "category"))
        assertEquals(totals, second.totals)
        assertEquals("category", second.groupBy)
        assertEquals(1, second.rows?.size)
        assertEquals(filter("""{"from":"2026-10-01"}"""), second.filter)
        val cached = AskAnswers.merge(second, QueryResult(cached = true, answer = "Done."))
        assertTrue(cached.cached)
        assertEquals("Done.", cached.answer)
        assertEquals(totals, cached.totals)
    }

    @Test
    fun stepsUpdateInPlaceByIdAndNewOnesJoinTheEnd() {
        val running = AskStep(id = "a", label = "Reading", state = "running")
        val steps = AskAnswers.upsert(AskAnswers.upsert(emptyList(), running), AskStep(id = "b", label = "Summing"))
        val done = AskAnswers.upsert(steps, running.copy(state = "done"))
        assertEquals(listOf("a", "b"), done.map { it.id })
        assertEquals("done", done.first().state)
    }

    @Test
    fun monthlyRowsAreASeriesOnlyWhenEveryBucketIsAMonth() {
        assertTrue(AskAnswers.isSeries(listOf(row("2026-09", 1), row("2026-10", 2))))
        assertFalse(AskAnswers.isSeries(listOf(row("2026-10", 1))))
        assertFalse(AskAnswers.isSeries(listOf(row("2026-10", 1), row("dining", 2))))
        assertFalse(AskAnswers.isSeries(null))
    }

    @Test
    fun seriesArePerCurrencyBiggestFirstAndSkipZero() {
        val series = AskAnswers.series(
            listOf(
                row("2026-10", 500), row("2026-09", 300), row("2026-09", 200),
                row("2026-09", 90, "USD"), row("2026-10", 10, "USD"),
                row("2026-10", 0, "JPY"),
            ),
        )
        assertEquals(listOf("HKD", "USD"), series.map { it.currency })
        assertEquals(listOf("2026-09" to 500L, "2026-10" to 500L), series.first().months)
        assertEquals(1000L, series.first().total)
    }

    @Test
    fun rankedRowsLeadWithTheBiggestAndCapAtEight() {
        val rows = (1..10).map { row("b$it", it * 10L) }
        val top = AskAnswers.rankedRows(rows, expanded = false)
        assertEquals(8, top.size)
        assertEquals("b10", top.first().bucket)
        assertEquals(10, AskAnswers.rankedRows(rows, expanded = true).size)
    }

    @Test
    fun theServersFilterBecomesTheBlottersFilter() {
        val parsed = AskAnswers.ledgerFilter(
            filter(
                """{"from":"2026-01-01","to":"2026-10-31","categories":["groceries"],"accounts":["amex"],"kinds":["purchase"],
                   "currency":"HKD","q":"citysuper","minAmount":-500000,"maxAmount":-100000,"includeTransfers":true,
                   "includeDuplicates":false,"uncategorisedOnly":true,"installmentsOnly":true}""",
            ),
        )
        assertEquals(
            LedgerFilter(
                from = "2026-01-01", to = "2026-10-31", categories = listOf("groceries"), accounts = listOf("amex"), kinds = listOf("purchase"),
                currency = "HKD", query = "citysuper", minAmount = -500_000, maxAmount = -100_000,
                includeTransfers = true, uncategorisedOnly = true,
            ),
            parsed,
        )
        assertNull(AskAnswers.ledgerFilter(null))
        assertEquals(LedgerFilter(), AskAnswers.ledgerFilter(JsonObject(emptyMap())))
    }

    @Test
    fun chipsDescribeTheScopeWithNames() {
        val chips = AskAnswers.chips(
            JsonObject(
                mapOf(
                    "from" to JsonPrimitive("2025-10-01"),
                    "to" to JsonPrimitive("2025-10-31"),
                    "accounts" to JsonArray(listOf(JsonPrimitive("amex"))),
                    "categories" to JsonArray(listOf(JsonPrimitive("eating_out"))),
                    "q" to JsonPrimitive("sushi"),
                    "maxAmount" to JsonPrimitive(-100_000),
                    "currency" to JsonPrimitive("HKD"),
                    "includeTransfers" to JsonPrimitive(true),
                    "installmentsOnly" to JsonPrimitive(true),
                ),
            ),
            groupBy = "month",
            accountNames = mapOf("amex" to "AMEX Platinum"),
        ).map { it.label }
        assertEquals(
            listOf(
                "1 Oct 2025 → 31 Oct 2025", "AMEX Platinum", "Eating out", "HKD", "“sushi”", "max -1,000.00",
                "incl. transfers", "instalments only", "by month",
            ),
            chips,
        )
    }

    @Test
    fun noFilterIsTheWholeLedger() {
        assertEquals(listOf("whole ledger", "by category"), AskAnswers.chips(JsonObject(emptyMap()), "category").map { it.label })
    }

    @Test
    fun timingSaysFromEarlierForACachedAnswer() {
        assertEquals("from earlier", AskAnswers.timing(0.2, cached = true))
        assertEquals("4.2s", AskAnswers.timing(4.21, cached = false))
        assertNull(AskAnswers.timing(null, cached = false))
    }

    @Test
    fun bareFiguresFollowTheCurrencysDecimals() {
        assertEquals("36,746.35", AskAnswers.bare(Money(3_674_635, "HKD")))
        assertEquals("1,200", AskAnswers.bare(Money(1_200, "JPY")))
    }

    @Test
    fun bucketsReadAsNames() {
        assertEquals("Eating out", AskAnswers.bucketLabel("eating_out", "category", emptyMap()))
        assertEquals("AMEX Platinum", AskAnswers.bucketLabel("amex", "account", mapOf("amex" to "AMEX Platinum")))
        assertEquals("October 2026", AskAnswers.bucketLabel("2026-10", "month", emptyMap()))
        assertEquals("McDonald's", AskAnswers.bucketLabel("McDonald's", "merchant", emptyMap()))
        assertTrue(AskAnswers.canDrill("merchant"))
        assertFalse(AskAnswers.canDrill("quarter"))
    }
}
