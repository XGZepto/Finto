package page.zepto.finto.core.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/**
 * The one filter every ledger read speaks, so a tapped summary figure opens the
 * blotter on exactly the rows that make it up.
 */
@Serializable
data class LedgerFilter(
    val from: String? = null,
    val to: String? = null,
    val months: List<String> = emptyList(),
    val accounts: List<String> = emptyList(),
    val cards: List<String> = emptyList(),
    val cardholders: List<String> = emptyList(),
    val institutions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val kinds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Structured statement facts, each `key` or `key=value`. */
    val detail: List<String> = emptyList(),
    /** Rows imported from these statement files. */
    val statements: List<String> = emptyList(),
    val currency: String? = null,
    val query: String? = null,
    /** Drop card payments, FX conversions and transfers: money changing pockets. */
    val spendingOnly: Boolean = false,
    val includeTransfers: Boolean = false,
    val includeDuplicates: Boolean = false,
    val uncategorisedOnly: Boolean = false,
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
) {
    /** Single-valued parameters, as the API names them; unset ones are left out. */
    fun scalarParams(): Map<String, String> = buildMap {
        from?.let { put("from", it) }
        to?.let { put("to", it) }
        currency?.let { put("currency", it) }
        query?.takeIf { it.isNotBlank() }?.let { put("q", it.trim()) }
        if (spendingOnly) put("spendingOnly", "true")
        if (includeTransfers) put("includeTransfers", "true")
        if (includeDuplicates) put("includeDuplicates", "true")
        if (uncategorisedOnly) put("uncategorisedOnly", "true")
        minAmount?.let { put("minAmount", it.toString()) }
        maxAmount?.let { put("maxAmount", it.toString()) }
    }

    /** True when nothing narrows the ledger. */
    val isEmpty: Boolean get() = this == LedgerFilter()
}

/**
 * Turns a tapped figure into the filter for the rows behind it, the way the web
 * app's `drillInto` does: entity dimensions become their list filter, time
 * buckets become a date range, and merchant and subcategory (which have no
 * field of their own) become a text search.
 */
object Drill {
    fun into(dimension: String, bucket: String, scope: LedgerFilter = LedgerFilter()): LedgerFilter = when (dimension) {
        "account" -> scope.copy(accounts = (scope.accounts + bucket).distinct())
        "institution" -> scope.copy(institutions = (scope.institutions + bucket).distinct())
        "category" -> scope.copy(categories = (scope.categories + bucket).distinct())
        "card" -> scope.copy(cards = (scope.cards + bucket).distinct())
        "cardholder" -> scope.copy(cardholders = (scope.cardholders + bucket).distinct())
        "kind" -> scope.copy(kinds = (scope.kinds + bucket).distinct())
        "tag" -> scope.copy(tags = (scope.tags + bucket).distinct())
        "currency" -> scope.copy(currency = bucket)
        "month" -> monthRange(bucket)?.let { (from, to) -> scope.copy(from = from, to = to, months = emptyList()) } ?: scope
        "day" -> scope.copy(from = bucket, to = bucket, months = emptyList())
        else -> scope.copy(query = bucket)
    }

    /** First and last day of a `YYYY-MM` bucket. */
    fun monthRange(bucket: String): Pair<String, String>? = runCatching {
        val month = YearMonth.parse(bucket)
        month.atDay(1).toString() to month.atEndOfMonth().toString()
    }.getOrNull()

    /** A month bucket clamped so its range never runs past [today]. */
    fun monthToDate(bucket: String, today: LocalDate = LocalDate.now()): Pair<String, String>? =
        monthRange(bucket)?.let { (from, to) -> from to minOf(LocalDate.parse(to), today).toString() }
}
