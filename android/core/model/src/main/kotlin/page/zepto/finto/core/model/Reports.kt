package page.zepto.finto.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Spend by a dimension over months, converted to one currency, top buckets plus `other`. */
@Serializable
data class Composition(
    val dimension: String,
    val currency: String,
    val months: List<String> = emptyList(),
    val series: List<CompositionSeries> = emptyList(),
    val unconvertibleCurrencies: List<String> = emptyList(),
)

@Serializable
data class CompositionSeries(
    val bucket: String,
    val total: Long = 0,
    /** Positive converted spend per month, aligned with [Composition.months]. */
    val values: List<Long> = emptyList(),
)

/** Per account, month by month: what data backs it. */
@Serializable
data class Coverage(
    val months: List<String> = emptyList(),
    val accounts: List<CoverageAccount> = emptyList(),
)

@Serializable
data class CoverageAccount(
    val accountId: String,
    val accountName: String,
    /** `statement`, `export`, `none` or `pre` (before the account's first row). */
    val cells: List<String> = emptyList(),
    val statementMonths: Int = 0,
    val exportMonths: Int = 0,
    val gapMonths: Int = 0,
)

/** Money moving between your own accounts, and across the boundary. */
@Serializable
data class Flows(
    val internal: List<InternalFlow> = emptyList(),
    val normalised: NormalisedFlows? = null,
)

@Serializable
data class InternalFlow(
    val fromAccount: String,
    val toAccount: String,
    val moves: Int = 0,
    val amount: Money,
)

@Serializable
data class NormalisedFlows(
    val currency: String,
    val internal: List<InternalFlow> = emptyList(),
    val externalNodes: List<ExternalNode> = emptyList(),
    val unconvertibleCurrencies: List<String> = emptyList(),
)

@Serializable
data class ExternalNode(
    val accountId: String,
    val bucket: String,
    val moves: Int = 0,
    @SerialName("in") val inflow: Money,
    val out: Money,
)
