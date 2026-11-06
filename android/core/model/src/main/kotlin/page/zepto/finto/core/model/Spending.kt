package page.zepto.finto.core.model

import kotlin.math.abs

/** One bucket of spending, collapsed into the reporting currency and ranked. */
data class RankedBucket(
    val bucket: String,
    val spend: Money,
    val income: Money,
    val rows: Int,
    /** Share of all ranked spending, 0..1. */
    val share: Double,
)

/** A month of money out and in, in the reporting currency. */
data class MonthFlow(
    val month: String,
    val spend: Money,
    val income: Money,
)

object Spending {

    /** Buckets that only record money moving, not what it bought. */
    fun isMovement(bucket: String): Boolean =
        bucket.trim().lowercase() in setOf("transfers", "transfer", "cc_payment", "fx_conversion", "fx")

    /**
     * HKD 1,000 and USD 1,000 cannot be ranked against each other, so buckets
     * are ranked only through their converted figures. A row whose rate is
     * missing is left out of the ranking (and reported by [unconvertible]).
     */
    fun rank(rows: List<SummaryRow>, currency: String): List<RankedBucket> {
        data class Acc(var spend: Long = 0, var income: Long = 0, var rows: Int = 0)
        val buckets = linkedMapOf<String, Acc>()
        for (row in rows) {
            if (isMovement(row.bucket)) continue
            val spend = row.spendConverted?.takeIf { it.ok } ?: continue
            val acc = buckets.getOrPut(row.bucket) { Acc() }
            acc.spend += abs(spend.amount)
            acc.income += abs(row.incomeConverted?.takeIf { it.ok }?.amount ?: 0)
            acc.rows += row.txnCount
        }
        val total = buckets.values.sumOf { it.spend }.coerceAtLeast(1)
        return buckets.entries
            .filter { it.value.spend > 0 }
            .map { (bucket, acc) ->
                RankedBucket(
                    bucket = bucket,
                    spend = Money(acc.spend, currency),
                    income = Money(acc.income, currency),
                    rows = acc.rows,
                    share = acc.spend.toDouble() / total,
                )
            }
            .sortedByDescending { it.spend.amount }
    }

    fun unconvertible(rows: List<SummaryRow>): List<String> =
        rows.filter { it.spendConverted?.ok != true }.map { it.currency }.distinct()

    /** Monthly out and in, summed across currencies through their converted figures. */
    fun monthlyFlows(rows: List<SummaryRow>, currency: String): List<MonthFlow> =
        rows.filter { it.spendConverted?.ok == true && it.incomeConverted?.ok == true }
            .groupBy { it.bucket }
            .map { (month, group) ->
                MonthFlow(
                    month = month,
                    spend = Money(group.sumOf { abs(it.spendConverted!!.amount) }, currency),
                    income = Money(group.sumOf { abs(it.incomeConverted!!.amount) }, currency),
                )
            }
            .sortedBy { it.month }
}
