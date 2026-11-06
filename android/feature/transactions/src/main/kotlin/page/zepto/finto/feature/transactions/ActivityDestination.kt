package page.zepto.finto.feature.transactions

import kotlinx.serialization.Serializable
import page.zepto.finto.core.model.FintoJson
import page.zepto.finto.core.model.LedgerFilter

/**
 * The blotter, optionally scoped to the filter behind a tapped figure (a
 * category on Summary, a bucket in Reports, an account), so it lists exactly
 * the rows that make that figure up.
 */
@Serializable
data class ActivityDestination(
    /** A [LedgerFilter] as JSON; null for the whole ledger. */
    val filter: String? = null,
    val title: String? = null,
) {
    val scope: LedgerFilter
        get() = filter?.let { runCatching { FintoJson.decodeFromString(LedgerFilter.serializer(), it) }.getOrNull() } ?: LedgerFilter()

    val isScoped: Boolean get() = filter != null

    companion object {
        fun of(filter: LedgerFilter, title: String? = null) =
            ActivityDestination(FintoJson.encodeToString(LedgerFilter.serializer(), filter), title)
    }
}
