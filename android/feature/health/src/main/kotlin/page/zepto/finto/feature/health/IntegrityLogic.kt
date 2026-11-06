package page.zepto.finto.feature.health

import page.zepto.finto.core.model.BalanceCheck
import page.zepto.finto.core.model.IntegrityReport
import page.zepto.finto.core.model.LedgerFilter

/** Wording and filters for the integrity report, out of Compose so they can be tested. */
object IntegrityLogic {

    const val OK = "ok"
    const val DISCREPANCY = "discrepancy"
    private const val INSUFFICIENT = "insufficient_data"

    fun passed(report: IntegrityReport): Int = report.balanceChecks.count { it.status == OK }

    /** The one line that answers "is the ledger right?". */
    fun verdict(report: IntegrityReport): String {
        if (report.healthy) {
            val passed = passed(report)
            return "Ledger reconciles. $passed balance ${plural(passed, "check", "checks")} passed, no structural violations."
        }
        val discrepancies = report.summary.discrepancyCount
        val violations = report.summary.violationCount
        return "$discrepancies balance ${plural(discrepancies, "discrepancy", "discrepancies")}, " +
            "$violations structural ${plural(violations, "violation", "violations")}."
    }

    /** Checks that compared something: all of them, or only the ones that do not reconcile. */
    fun visibleChecks(report: IntegrityReport?, showAll: Boolean): List<BalanceCheck> {
        val real = report?.balanceChecks.orEmpty().filter { it.status != INSUFFICIENT }
        return if (showAll) real else real.filter { it.status == DISCREPANCY }
    }

    /**
     * The rows a check covers. Transfers and duplicates are included because
     * the balance check counts them too.
     */
    fun inspectFilter(check: BalanceCheck): LedgerFilter = LedgerFilter(
        accounts = listOf(check.accountId),
        from = check.periodStart.takeIf { it.isNotBlank() },
        to = check.periodEnd.takeIf { it.isNotBlank() },
        includeTransfers = true,
        includeDuplicates = true,
    )

    fun accountFilter(accountId: String): LedgerFilter = LedgerFilter(accounts = listOf(accountId), includeTransfers = true)

    /** Stable list keys; an account can have two checks over one period in two currencies. */
    fun keys(checks: List<BalanceCheck>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return checks.map { check ->
            val base = "${check.accountId}|${check.periodStart}|${check.periodEnd}|${check.expectedDelta?.currency.orEmpty()}"
            val n = seen.merge(base, 1, Int::plus)!!
            if (n == 1) base else "$base#$n"
        }
    }

    fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many
}
