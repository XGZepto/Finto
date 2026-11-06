package page.zepto.finto.feature.recurring

import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.Txn
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max

/** How often a commitment is charged, read from the median gap between its charges. */
enum class Cadence(val label: String) {
    Weekly("Weekly"),
    Monthly("Monthly"),
    Quarterly("Quarterly"),
    Yearly("Yearly"),
    Irregular("Irregular"),
}

/** The Kind filter: both lists, or one of them. */
enum class RecurringKind(val label: String) {
    All("All"),
    Subscriptions("Subscriptions"),
    Instalments("Instalments"),
}

/**
 * Charges to one merchant, read for their cadence.
 *
 * Amounts are the booked ones and negative (money out), as on the web.
 */
data class Commitment(
    /** The normalised merchant: lower-cased and trimmed. */
    val key: String,
    val merchant: String,
    val category: String?,
    val cadence: Cadence,
    /** Typical charge, the median, so one odd month cannot move it. */
    val amount: Money,
    /** What the cadence costs per month; null when irregular. */
    val monthly: Money?,
    val charges: List<Txn>,
    val lastCharge: String,
    val nextDue: String?,
    /** No charge arrived when one was due: listed, but kept out of the committed total. */
    val inactive: Boolean,
    val totalPaid: Money,
) {
    val count: Int get() = charges.size

    /** Total paid spread over the charges. */
    val average: Money
        get() = Money(-Math.round(abs(totalPaid.amount).toDouble() / max(1, count)), totalPaid.currency)

    val firstSeen: String get() = charges.minOf { it.date }
}

data class RecurringFilter(
    val search: String = "",
    val kind: RecurringKind = RecurringKind.All,
    /** Null is "Any". */
    val cadence: Cadence? = null,
) {
    val isActive: Boolean get() = this != RecurringFilter()
}

/** What the screen lists for a filter, and the committed total it adds up to. */
data class RecurringView(
    val activeSubscriptions: List<Commitment> = emptyList(),
    val inactiveSubscriptions: List<Commitment> = emptyList(),
    val plans: List<InstallmentPlan> = emptyList(),
    /** Monthly cost of active subscriptions with a known cadence plus running instalments; negative. */
    val monthlyTotal: Money = Money(0, "HKD"),
    val irregularCount: Int = 0,
) {
    val isEmpty: Boolean get() = activeSubscriptions.isEmpty() && inactiveSubscriptions.isEmpty() && plans.isEmpty()
}

/**
 * The web app's recurring arithmetic, unchanged.
 *
 * A commitment is recognised by the interval between its charges, not by how
 * many there are: a supermarket visited forty times is frequent, not
 * recurring. A charge set whose intervals do not cluster is irregular and
 * stays out of the monthly total.
 */
object Recurring {

    /** The tags whose charges count as recurring. The tag filter is conjunctive, so each is read on its own. */
    val TAGS = listOf("subscription", "recurring")

    private data class Band(val cadence: Cadence, val min: Int, val max: Int, val perMonth: Double)

    private val BANDS = listOf(
        Band(Cadence.Weekly, 5, 9, 30.0 / 7),
        Band(Cadence.Monthly, 24, 38, 1.0),
        Band(Cadence.Quarterly, 80, 100, 1.0 / 3),
        Band(Cadence.Yearly, 340, 400, 1.0 / 12),
    )

    /** Rows from each tag read, once each: a charge carrying both tags is not counted twice. */
    fun merge(parts: List<List<Txn>>): List<Txn> {
        val merged = LinkedHashMap<String, Txn>()
        for (part in parts) for (row in part) merged[row.id] = row
        return merged.values.toList()
    }

    /** The middle value; the rounded mean of the middle two for an even count. Zero when empty. */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else Math.round((sorted[mid - 1] + sorted[mid]) / 2.0)
    }

    /** The cadence for a median gap of [days], given at least two charge dates. */
    fun cadenceFor(days: Long, dates: Int): Cadence =
        band(days, dates)?.cadence ?: Cadence.Irregular

    private fun band(days: Long, dates: Int): Band? =
        if (dates < 2) null else BANDS.firstOrNull { days >= it.min && days <= it.max }

    /** Charges grouped per merchant, each read for its cadence, most expensive per month first. */
    fun commitments(charges: List<Txn>, today: LocalDate): List<Commitment> {
        val groups = LinkedHashMap<String, MutableList<Txn>>()
        for (txn in charges) {
            val key = nameOf(txn).trim().lowercase()
            if (key.isEmpty()) continue
            groups.getOrPut(key) { mutableListOf() } += txn
        }
        return groups.map { (key, rows) -> commitment(key, rows, today) }
            .sortedWith(
                compareByDescending<Commitment> { abs(it.monthly?.amount ?: 0) }
                    .thenByDescending { abs(it.amount.amount) },
            )
    }

    private fun commitment(key: String, rows: List<Txn>, today: LocalDate): Commitment {
        val dates = rows.map { it.date.take(10) }.distinct().sorted()
        val amounts = rows.map { abs(it.booked.amount) }
        val currency = rows.first().booked.currency

        val gaps = dates.zipWithNext { a, b -> daysBetween(a, b) }
        val interval = median(gaps)
        val band = band(interval, dates.size)
        val typical = median(amounts)
        val lastCharge = dates.last()
        val nextDue = band?.let { LocalDate.parse(lastCharge).plusDays(interval) }

        return Commitment(
            key = key,
            merchant = rows.first().let { first -> nonEmpty(first.merchant) ?: nonEmpty(first.description) ?: key },
            category = rows.first().category,
            cadence = band?.cadence ?: Cadence.Irregular,
            amount = Money(-typical, currency),
            monthly = band?.let { Money(-Math.round(typical * it.perMonth), currency) },
            charges = rows,
            lastCharge = lastCharge,
            nextDue = nextDue?.toString(),
            inactive = nextDue != null && nextDue < today && ChronoUnit.DAYS.between(nextDue, today) > interval,
            totalPaid = Money(-amounts.sum(), currency),
        )
    }

    /** An instalment plan's monthly charge: the principal spread across the term, negative. */
    fun perMonth(plan: InstallmentPlan): Money =
        Money(-Math.round(abs(plan.principal.amount).toDouble() / max(1, plan.termMonths)), plan.principal.currency)

    /** What [filter] leaves of [commitments] and [plans], and the monthly total in [currency]. */
    fun view(commitments: List<Commitment>, plans: List<InstallmentPlan>, filter: RecurringFilter, currency: String): RecurringView {
        val query = filter.search.trim().lowercase()
        fun matches(name: String) = query.isEmpty() || name.lowercase().contains(query)

        val subscriptions = if (filter.kind == RecurringKind.Instalments) {
            emptyList()
        } else {
            commitments.filter { matches(it.merchant) && (filter.cadence == null || it.cadence == filter.cadence) }
        }
        val visiblePlans = when {
            filter.kind == RecurringKind.Subscriptions -> emptyList()
            // Instalments are charged monthly, so any other cadence leaves none.
            filter.cadence != null && filter.cadence != Cadence.Monthly -> emptyList()
            else -> plans.filter { matches(nonEmpty(it.merchant) ?: nonEmpty(it.description).orEmpty()) }
        }
        val (inactive, active) = subscriptions.partition { it.inactive }
        val subscriptionsTotal = active.sumOf { it.monthly?.amount ?: 0 }
        val plansTotal = visiblePlans.sumOf { perMonth(it).amount }
        return RecurringView(
            activeSubscriptions = active,
            inactiveSubscriptions = inactive,
            plans = visiblePlans,
            monthlyTotal = Money(subscriptionsTotal + plansTotal, currency),
            irregularCount = subscriptions.count { it.cadence == Cadence.Irregular },
        )
    }

    /** The web's `merchant || description`: an empty string falls through, as JavaScript's `||` does. */
    private fun nameOf(txn: Txn): String = nonEmpty(txn.merchant) ?: nonEmpty(txn.description).orEmpty()

    private fun nonEmpty(value: String?): String? = value?.takeIf { it.isNotEmpty() }

    private fun daysBetween(a: String, b: String): Long = abs(ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b)))
}
