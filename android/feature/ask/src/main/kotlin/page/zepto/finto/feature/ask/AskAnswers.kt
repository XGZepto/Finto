package page.zepto.finto.feature.ask

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import page.zepto.finto.core.model.AskStep
import page.zepto.finto.core.model.DisplayDates
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MoneyFormat
import page.zepto.finto.core.model.QueryResult
import page.zepto.finto.core.model.SummaryRow
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** One currency's spending per month, for a column chart. */
data class MonthSeries(val currency: String, val months: List<Pair<String, Long>>) {
    val total: Long get() = months.sumOf { it.second }
}

/** A chip describing part of the filter an answer was computed on. */
data class ScopeChip(val key: String, val label: String)

/** How an answer's figures are shaped for display; no Compose here, so all of it is testable. */
object AskAnswers {

    /** A broad query can group into hundreds of buckets; lead with the movers. */
    const val ROW_CAP = 8

    private val month = Regex("""^\d{4}-\d{2}$""")

    /** Figures arrive in pieces as each tool returns; a later piece wins field by field. */
    fun merge(base: QueryResult, next: QueryResult): QueryResult = base.copy(
        ok = next.ok,
        error = next.error ?: base.error,
        question = next.question.ifEmpty { base.question },
        answer = next.answer ?: base.answer,
        model = next.model ?: base.model,
        cached = base.cached || next.cached,
        groupBy = next.groupBy ?: base.groupBy,
        totals = next.totals ?: base.totals,
        rows = next.rows ?: base.rows,
        transactions = next.transactions ?: base.transactions,
        filter = next.filter ?: base.filter,
    )

    /** A step replaces the one with its id, in place, or joins the end. */
    fun upsert(steps: List<AskStep>, step: AskStep): List<AskStep> {
        val at = steps.indexOfFirst { it.id == step.id }
        return if (at == -1) steps + step else steps.toMutableList().apply { set(at, step) }
    }

    /** Monthly buckets draw as columns. */
    fun isSeries(rows: List<SummaryRow>?): Boolean =
        rows != null && rows.size > 1 && rows.all { month.matches(it.bucket) }

    /** Up to three currencies with any spending, the largest first. */
    fun series(rows: List<SummaryRow>): List<MonthSeries> =
        rows.groupBy { it.currency }
            .map { (currency, list) ->
                val months = list.groupBy { it.bucket }
                    .mapValues { (_, same) -> same.sumOf { abs(it.spend.amount) } }
                    .toSortedMap()
                    .map { it.key to it.value }
                MonthSeries(currency, months)
            }
            .filter { it.total > 0 }
            .sortedByDescending { it.total }
            .take(3)

    /** Biggest spend first, capped at [ROW_CAP] unless [expanded]. */
    fun rankedRows(rows: List<SummaryRow>, expanded: Boolean): List<SummaryRow> {
        val sorted = rows.sortedByDescending { abs(it.spend.amount) }
        return if (expanded) sorted else sorted.take(ROW_CAP)
    }

    /** `1,234.50`: a figure without its symbol, where the currency is printed beside it. */
    fun bare(money: Money, locale: Locale = Locale.getDefault()): String {
        val digits = MoneyFormat.fractionDigits(money.currency)
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(MoneyFormat.toMajor(money))
    }

    /** The server's camelCase filter as the blotter's filter. */
    fun ledgerFilter(filter: JsonObject?): LedgerFilter? {
        filter ?: return null
        fun text(key: String) = (filter[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        fun list(key: String): List<String> = when (val value = filter[key]) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> listOfNotNull(value.content.takeIf { value.isString && it.isNotBlank() })
            else -> emptyList()
        }
        fun flag(key: String) = (filter[key] as? JsonPrimitive)?.booleanOrNull == true
        fun number(key: String) = (filter[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }
        return LedgerFilter(
            from = text("from"),
            to = text("to"),
            months = list("months"),
            accounts = list("accounts"),
            cards = list("cards"),
            cardholders = list("cardholders"),
            institutions = list("institutions"),
            categories = list("categories"),
            kinds = list("kinds"),
            tags = list("tags"),
            detail = list("detail"),
            statements = list("statements"),
            currency = text("currency"),
            query = text("q"),
            spendingOnly = flag("spendingOnly"),
            includeTransfers = flag("includeTransfers"),
            includeDuplicates = flag("includeDuplicates"),
            uncategorisedOnly = flag("uncategorisedOnly"),
            minAmount = number("minAmount"),
            maxAmount = number("maxAmount"),
        )
    }

    /** What the answer covered, in the web app's chip wording, with names for account ids. */
    fun chips(filter: JsonObject?, groupBy: String?, accountNames: Map<String, String> = emptyMap()): List<ScopeChip> {
        val chips = mutableListOf<ScopeChip>()
        val parsed = ledgerFilter(filter)
        if (parsed != null) {
            if (parsed.from != null || parsed.to != null) {
                val from = parsed.from?.let { DisplayDates.short(it) } ?: "start"
                val to = parsed.to?.let { DisplayDates.short(it) } ?: "now"
                chips += ScopeChip("date", "$from → $to")
            }
            if (parsed.months.isNotEmpty()) {
                chips += ScopeChip("months", parsed.months.singleOrNull()?.let(DisplayDates::shortMonth) ?: "${parsed.months.size} months")
            }
            if (parsed.accounts.isNotEmpty()) chips += ScopeChip("accounts", parsed.accounts.joinToString { accountNames[it] ?: it })
            if (parsed.categories.isNotEmpty()) chips += ScopeChip("categories", parsed.categories.joinToString { Labels.humanize(it) })
            if (parsed.kinds.isNotEmpty()) chips += ScopeChip("kinds", parsed.kinds.joinToString { Labels.kind(it) })
            listOf("cards" to parsed.cards, "cardholders" to parsed.cardholders, "institutions" to parsed.institutions, "tags" to parsed.tags)
                .filter { it.second.isNotEmpty() }
                .forEach { (key, values) -> chips += ScopeChip(key, "$key: ${values.joinToString()}") }
            if (parsed.detail.isNotEmpty()) chips += ScopeChip("detail", parsed.detail.joinToString())
            if (parsed.statements.isNotEmpty()) {
                chips += ScopeChip("statements", if (parsed.statements.size == 1) "one import" else "${parsed.statements.size} imports")
            }
            parsed.currency?.let { chips += ScopeChip("currency", it) }
            parsed.query?.let { chips += ScopeChip("q", "“$it”") }
            parsed.minAmount?.let { chips += ScopeChip("minAmount", "min ${amount(it, parsed.currency)}") }
            parsed.maxAmount?.let { chips += ScopeChip("maxAmount", "max ${amount(it, parsed.currency)}") }
            if (parsed.includeTransfers) chips += ScopeChip("includeTransfers", "incl. transfers")
            if (parsed.includeDuplicates) chips += ScopeChip("includeDuplicates", "incl. duplicates")
            if (parsed.uncategorisedOnly) chips += ScopeChip("uncategorisedOnly", "uncategorised only")
            if ((filter?.get("installmentsOnly") as? JsonPrimitive)?.booleanOrNull == true) {
                chips += ScopeChip("installmentsOnly", "instalments only")
            }
            if (parsed.spendingOnly) chips += ScopeChip("spendingOnly", "spending only")
        }
        if (chips.isEmpty()) chips += ScopeChip("all", "whole ledger")
        groupBy?.let { chips += ScopeChip("groupBy", "by ${Labels.humanize(it).lowercase(Locale.getDefault())}") }
        return chips
    }

    /** Minor units as a plain figure: in the filter's currency when it has one, else two decimals. */
    private fun amount(minor: Long, currency: String?): String =
        if (currency != null) bare(Money(minor, currency)) else BigDecimal.valueOf(minor, 2).toPlainString()

    /** A row's bucket as a person reads it. */
    fun bucketLabel(bucket: String, groupBy: String?, accountNames: Map<String, String>): String = when (groupBy) {
        "category", "subcategory" -> Labels.humanize(bucket)
        "kind" -> Labels.kind(bucket)
        "account" -> accountNames[bucket] ?: bucket
        "month" -> monthTitle(bucket)
        else -> bucket
    }

    /** Dimensions a row can drill into, as the web's `drillInto` maps them. */
    fun canDrill(groupBy: String?): Boolean = groupBy in setOf(
        "account", "institution", "category", "card", "cardholder", "kind", "tag", "currency", "month", "day", "merchant", "subcategory",
    )

    /** `October 2026` for a `2026-10` bucket. */
    fun monthTitle(bucket: String): String = runCatching {
        YearMonth.parse(bucket).format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    }.getOrDefault(bucket)

    /** `from earlier` for a cached answer, else the seconds it took: `3.4s`. */
    fun timing(seconds: Double?, cached: Boolean): String? = when {
        cached -> "from earlier"
        seconds == null || seconds <= 0.0 -> null
        else -> String.format(Locale.ROOT, "%.1fs", seconds)
    }

    /** The label to show for a step, whatever the server called it. */
    fun stepLabel(step: AskStep): String = step.label.ifBlank { Labels.humanize(step.name.ifBlank { "Working" }) }
}
