package page.zepto.finto.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every response the app reads decodes from what the server really sends. */
class ApiDecodingTest {

    @Test
    fun user() {
        val user = Fixtures.decode<User>("me")
        assertEquals("demo", user.username)
        assertNull(user.preferences.baseCurrency)
    }

    @Test
    fun transactionsPage() {
        val page = Fixtures.decode<TxnPage>("transactions")
        assertEquals(1270, page.total)
        val first = page.items.first()
        assertEquals("amex_hk_main", first.accountId)
        assertEquals(Money(-16800, "HKD"), first.booked)
        assertEquals("Adobe", first.title)
        assertTrue(first.booked.isOutflow)
    }

    @Test
    fun transactionDetailKeepsStatementFacts() {
        val txn = Fixtures.decode<Txn>("transaction_detail")
        assertTrue(txn.detailStrings.containsKey("reference"))
    }

    @Test
    fun summaryWithConversion() {
        val summary = Fixtures.decode<SummaryResponse>("summary_month")
        assertEquals("month", summary.groupBy)
        assertNotNull(summary.ledgerThrough)
        assertNotNull(summary.normalised?.total)
        val row = summary.rows.first()
        assertTrue(row.spendConverted!!.ok)
    }

    @Test
    fun summaryAnchorReportsLedgerThrough() {
        val summary = Fixtures.decode<SummaryResponse>("summary_anchor")
        assertEquals("2026-11-22", summary.ledgerThrough)
    }

    @Test
    fun positionsAndNetWorth() {
        val positions = Fixtures.decode<PositionsResponse>("positions")
        assertTrue(positions.positions.isNotEmpty())
        assertEquals("HKD", positions.normalised!!.netWorth.currency)
        assertEquals("credit_card", positions.positions.first { it.accountId == "amex_hk_main" }.accountType)
    }

    @Test
    fun accountsFreshnessCapabilities() {
        assertEquals(6, Fixtures.decode<AccountsResponse>("accounts").accounts.size)
        val freshness = Fixtures.decode<StatementFreshness>("freshness")
        assertTrue(freshness.accounts.all { it.status.isNotEmpty() })
        val capabilities = Fixtures.decode<ImportCapabilities>("capabilities")
        assertTrue(capabilities.formats.any { it.fileFormat == "pdf" })
    }

    @Test
    fun confirmResponseReadsTheImportKey() {
        val json = """{"import":{"status":"imported","txns":9,"balances":1,""" +
            """"statement_file_id":"df46","period_start":"2026-10-05","period_end":"2026-11-04"},"reconcile":{}}"""
        val response = FintoJson.decodeFromString<ConfirmResponse>(json)
        assertEquals(9, response.result.txns)
        assertEquals("df46", response.result.statementFileId)
    }

    @Test
    fun previewTotalsUseTheInKey() {
        val json = """{"sha256":"ab","filename":"a.pdf","totals":{"HKD":{"in":100,"out":-50,"rows":2}}}"""
        val preview = FintoJson.decodeFromString<StagePreview>(json)
        assertEquals(100, preview.totals.getValue("HKD").inflow)
    }

    @Test
    fun patchOmitsUnsetFields() {
        val body = FintoJson.encodeToString(PreferencesPatch.serializer(), PreferencesPatch(baseCurrency = "USD"))
        assertEquals("""{"base_currency":"USD"}""", body)
    }
}
