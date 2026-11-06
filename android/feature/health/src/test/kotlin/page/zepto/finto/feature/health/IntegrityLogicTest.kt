package page.zepto.finto.feature.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import page.zepto.finto.core.model.BalanceCheck
import page.zepto.finto.core.model.FintoJson
import page.zepto.finto.core.model.IntegrityReport
import page.zepto.finto.core.model.IntegritySummary
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money

class IntegrityLogicTest {

    private fun check(account: String, status: String, start: String = "2026-10-01", end: String = "2026-10-31", currency: String = "HKD") =
        BalanceCheck(
            accountId = account, accountName = account.uppercase(), periodStart = start, periodEnd = end,
            expectedDelta = Money(100, currency), actualDelta = Money(100, currency), discrepancy = Money(0, currency), status = status,
        )

    @Test
    fun verdictCountsPassedChecksWhenHealthy() {
        val report = IntegrityReport(
            healthy = true,
            balanceChecks = listOf(check("a", "ok"), check("b", "ok"), BalanceCheck(accountId = "c", status = "insufficient_data")),
        )
        assertEquals("Ledger reconciles. 2 balance checks passed, no structural violations.", IntegrityLogic.verdict(report))
        assertEquals(
            "Ledger reconciles. 1 balance check passed, no structural violations.",
            IntegrityLogic.verdict(IntegrityReport(healthy = true, balanceChecks = listOf(check("a", "ok")))),
        )
    }

    @Test
    fun verdictCountsProblemsWhenNot() {
        val report = IntegrityReport(healthy = false, summary = IntegritySummary(discrepancyCount = 1, violationCount = 3))
        assertEquals("1 balance discrepancy, 3 structural violations.", IntegrityLogic.verdict(report))
        val other = IntegrityReport(healthy = false, summary = IntegritySummary(discrepancyCount = 2, violationCount = 1))
        assertEquals("2 balance discrepancies, 1 structural violation.", IntegrityLogic.verdict(other))
    }

    @Test
    fun checksWithoutDataAreNeverListed() {
        val report = IntegrityReport(
            balanceChecks = listOf(check("a", "ok"), check("b", "discrepancy"), BalanceCheck(accountId = "c", status = "insufficient_data")),
        )
        assertEquals(listOf("a", "b"), IntegrityLogic.visibleChecks(report, showAll = true).map { it.accountId })
        assertEquals(listOf("b"), IntegrityLogic.visibleChecks(report, showAll = false).map { it.accountId })
        assertEquals(emptyList<BalanceCheck>(), IntegrityLogic.visibleChecks(null, showAll = true))
    }

    @Test
    fun inspectScopesTheBlotterToTheAccountAndPeriod() {
        assertEquals(
            LedgerFilter(accounts = listOf("amex"), from = "2026-10-05", to = "2026-11-04", includeTransfers = true, includeDuplicates = true),
            IntegrityLogic.inspectFilter(check("amex", "discrepancy", "2026-10-05", "2026-11-04")),
        )
        assertEquals(LedgerFilter(accounts = listOf("mox"), includeTransfers = true), IntegrityLogic.accountFilter("mox"))
    }

    @Test
    fun keysStayUniqueAcrossCurrencies() {
        val keys = IntegrityLogic.keys(listOf(check("a", "ok"), check("a", "ok", currency = "USD"), check("a", "ok")))
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun insufficientDataChecksDecodeFromTheServerShape() {
        val json = """
            {"healthy": true, "violations": [], "discrepancies": [],
             "balance_checks": [
               {"account_id": "mox", "account_name": "Mox", "status": "insufficient_data",
                "note": "no opening/closing pair and fewer than two running balances"},
               {"period": "2026-10-01 -> 2026-10-31", "period_start": "2026-10-01", "period_end": "2026-10-31",
                "expected_delta": {"amount": 1000, "currency": "HKD"}, "actual_delta": {"amount": 900, "currency": "HKD"},
                "discrepancy": {"amount": -100, "currency": "HKD"}, "currency": "HKD", "status": "discrepancy",
                "account_id": "hsbc", "account_name": "HSBC Current"}
             ],
             "unverified_accounts": [{"account_id": "amex", "display_name": "AMEX Platinum", "txn_count": 1088}],
             "summary": {"checks_run": 2, "discrepancy_count": 1, "violation_count": 0, "unverified_account_count": 1}}
        """.trimIndent()
        val report = FintoJson.decodeFromString(IntegrityReport.serializer(), json)
        assertEquals(2, report.balanceChecks.size)
        assertNull(report.balanceChecks.first().expectedDelta)
        assertEquals(Money(-100, "HKD"), report.balanceChecks.last().discrepancy)
        assertEquals("AMEX Platinum", report.unverifiedAccounts.single().displayName)
        assertEquals(1, report.summary.discrepancyCount)
    }
}
