package page.zepto.finto.feature.reports

import page.zepto.finto.core.model.Composition
import page.zepto.finto.core.model.CoverageAccount
import page.zepto.finto.core.model.Drill
import page.zepto.finto.core.model.LedgerFilter

/** Share divides each band by its month; Amount by the largest month, so columns compare. */
enum class TimelineScale(val label: String) { Share("Share"), Amount("Amount") }

/** One bucket inside one month's column, as fractions of the chart's height. */
data class Segment(
    val bucket: String,
    /** Index into the composition's series, which fixes the band's colour. */
    val series: Int,
    val amount: Long,
    val bottom: Float,
    val height: Float,
)

data class TimelineColumn(val month: String, val total: Long, val segments: List<Segment>)

object TimelineMath {

    val DIMENSIONS = listOf("category", "subcategory", "merchant", "account", "kind", "cardholder")
    const val DEFAULT_DIMENSION = "category"

    /** The residual the server folds everything outside the top buckets into. */
    const val OTHER = "other"

    /** Each month's total across all series, the share denominator. */
    fun monthTotals(composition: Composition): List<Long> =
        composition.months.indices.map { index -> composition.series.sumOf { it.values.getOrElse(index) { 0L } } }

    /**
     * One stacked column per month, bands stacked from the bottom in series
     * order. Share fills every column; Amount scales all of them to the peak
     * month, so the two modes answer different questions off one chart.
     */
    fun columns(composition: Composition, scale: TimelineScale): List<TimelineColumn> {
        val totals = monthTotals(composition)
        val peak = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)
        return composition.months.mapIndexed { index, month ->
            val denominator = when (scale) {
                TimelineScale.Share -> totals[index].takeIf { it > 0 } ?: 1L
                TimelineScale.Amount -> peak
            }
            var offset = 0f
            val segments = composition.series.mapIndexed { series, bucket ->
                val amount = bucket.values.getOrElse(index) { 0L }
                val height = amount.toFloat() / denominator
                Segment(bucket.bucket, series, amount, offset, height).also { offset += height }
            }
            TimelineColumn(month, totals[index], segments)
        }
    }

    /** The band under a point [fromBottom] (0..1 of the chart's height) in [column], if any. */
    fun segmentAt(column: TimelineColumn, fromBottom: Float): Segment? =
        column.segments.firstOrNull { it.height > 0f && fromBottom >= it.bottom && fromBottom <= it.bottom + it.height }

    /** A band is one bucket inside one month, so the drill carries both, in Summary's spending scope. */
    fun drill(dimension: String, bucket: String, month: String): LedgerFilter {
        val (from, to) = Drill.monthRange(month) ?: (null to null)
        return Drill.into(dimension, bucket, LedgerFilter(from = from, to = to, spendingOnly = true))
    }

    /** `12 statement · 6 export · 1 missing`, leaving out what is zero. */
    fun coverageCounts(account: CoverageAccount): String = listOfNotNull(
        account.statementMonths.takeIf { it > 0 }?.let { "$it statement" },
        account.exportMonths.takeIf { it > 0 }?.let { "$it export" },
        account.gapMonths.takeIf { it > 0 }?.let { "$it missing" },
    ).joinToString(" · ")
}
