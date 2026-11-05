package page.zepto.finto.core.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

/** The day the Summary hero measures "this month" up to. */
data class Anchor(
    val date: LocalDate,
    /** True when the ledger stops before today. */
    val lagging: Boolean,
    /** False when the date is a month-end stand-in rather than a real last row. */
    val exact: Boolean,
)

/**
 * Statements land weeks after the spending they record, so on the 2nd of a
 * month "this month" is usually empty and a comparison with last month reads as
 * a 100% drop. The hero follows the data instead of the wall clock.
 *
 * A port of the web app's `hero-anchor.ts`; the two must agree.
 */
object HeroAnchor {

    fun anchor(ledgerThrough: String?, months: List<SummaryRow>, today: LocalDate): Anchor {
        var latest: LocalDate? = null
        var exact = false
        val through = ledgerThrough?.let { parseDate(it) }
        if (through != null) {
            latest = through
            exact = true
        } else {
            // A month with only an interest credit is not a month with spending in it.
            val last = months
                .filter { it.txnCount > 0 && it.spend.amount != 0L }
                .map { it.bucket }
                .filter { MONTH.matches(it) }
                .maxOrNull()
            if (last != null) latest = YearMonth.parse(last).atEndOfMonth()
        }
        if (latest == null || !latest.isBefore(today)) return Anchor(today, lagging = false, exact = true)
        return Anchor(latest, lagging = true, exact = exact)
    }

    /** Month-to-anchor, against the same span of the month before. */
    fun comparison(anchor: LocalDate): Pair<LedgerFilter, LedgerFilter> {
        val currentStart = anchor.withDayOfMonth(1)
        val previousMonth = YearMonth.from(anchor).minusMonths(1)
        val previousStart = previousMonth.atDay(1)
        val previousEnd = previousMonth.atDay(minOf(anchor.dayOfMonth, previousMonth.lengthOfMonth()))
        return LedgerFilter(from = currentStart.toString(), to = anchor.toString(), spendingOnly = true) to
            LedgerFilter(from = previousStart.toString(), to = previousEnd.toString(), spendingOnly = true)
    }

    /** The first day of the window that covers [months] calendar months ending this month. */
    fun windowStart(months: Int, today: LocalDate): LocalDate =
        YearMonth.from(today).minusMonths((months - 1).toLong()).atDay(1)

    private fun parseDate(value: String): LocalDate? =
        if (DATE.matches(value)) {
            try {
                LocalDate.parse(value)
            } catch (_: DateTimeParseException) {
                null
            }
        } else {
            null
        }

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val MONTH = Regex("""\d{4}-\d{2}""")
}
