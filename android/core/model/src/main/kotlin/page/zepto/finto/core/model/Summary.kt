package page.zepto.finto.core.model

import kotlinx.serialization.Serializable

/**
 * One `group_by` aggregation. Rows are per (bucket, currency): HKD and USD are
 * never added together natively, only through the `*_converted` figures.
 */
@Serializable
data class SummaryResponse(
    val groupBy: String,
    val rows: List<SummaryRow> = emptyList(),
    val totals: List<TotalRow> = emptyList(),
    val normalised: SummaryNormalised? = null,
    /** The latest transaction date visible to this user, whatever the date filter. */
    val ledgerThrough: String? = null,
)

@Serializable
data class SummaryRow(
    val bucket: String,
    val currency: String,
    val txnCount: Int,
    val net: Money,
    val spend: Money,
    val income: Money,
    val netConverted: ConvertedMoney? = null,
    val spendConverted: ConvertedMoney? = null,
    val incomeConverted: ConvertedMoney? = null,
)

@Serializable
data class TotalRow(
    val currency: String,
    val txnCount: Int,
    val net: Money,
    val spend: Money,
    val income: Money,
    val uncategorised: Int = 0,
)

@Serializable
data class SummaryNormalised(
    val to: String,
    val total: FlowTotals? = null,
    val unconvertibleCurrencies: List<String> = emptyList(),
)

@Serializable
data class FlowTotals(
    val net: Money,
    val spend: Money,
    val income: Money,
)
