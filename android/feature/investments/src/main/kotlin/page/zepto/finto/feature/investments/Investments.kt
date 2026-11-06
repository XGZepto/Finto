package page.zepto.finto.feature.investments

import page.zepto.finto.core.model.Holding
import page.zepto.finto.core.model.Labels
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.ValuePoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/** The change from the previous dated value, dated [since]. */
data class ValueChange(val amount: Money, val percent: Double?, val since: String)

/** The investments page's arithmetic, as the web does it. */
object Investments {

    private val acronyms = setOf("TDVC")

    /** `hsbc_mpf` → `HSBC MPF`, `regular_contribution` → `Regular contribution`. */
    fun friendly(value: String): String =
        Labels.humanize(value).split(' ').joinToString(" ") { word ->
            if (word.uppercase(Locale.ROOT) in acronyms) word.uppercase(Locale.ROOT) else word
        }

    /** The issuer states an allocation as a fraction (`0.4210`); shown as `42.1%`. Null when there is none. */
    fun percent(allocation: String?, locale: Locale = Locale.getDefault()): String? {
        if (allocation.isNullOrBlank()) return null
        val fraction = allocation.trim().toBigDecimalOrNull() ?: return allocation
        val percent = fraction.movePointRight(2).setScale(1, RoundingMode.HALF_UP)
        return DecimalFormat("#,##0.0", DecimalFormatSymbols.getInstance(locale)).format(percent) + "%"
    }

    /** Units as stated, grouped for reading: `12345.6789` → `12,345.6789`. */
    fun units(units: String?, locale: Locale = Locale.getDefault()): String? {
        if (units.isNullOrBlank()) return null
        val value = units.trim().toBigDecimalOrNull() ?: return units
        val format = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale)).apply {
            minimumFractionDigits = value.scale().coerceAtLeast(0)
            maximumFractionDigits = value.scale().coerceAtLeast(0)
        }
        return format.format(value)
    }

    /** History up to the valuation being looked at, so an older snapshot is not compared with a newer one. */
    fun visibleHistory(points: List<ValuePoint>, asOfDate: String?): List<ValuePoint> =
        points.filter { asOfDate == null || it.asOfDate <= asOfDate }

    /** The last point against the one before it; null with fewer than two. */
    fun change(points: List<ValuePoint>): ValueChange? {
        if (points.size < 2) return null
        val current = points[points.size - 1].value
        val before = points[points.size - 2]
        val previous = before.value
        val amount = current.amount - previous.amount
        return ValueChange(
            amount = Money(amount, current.currency),
            percent = previous.amount.takeIf { it != 0L }?.let { amount.toDouble() / abs(it) * 100 },
            since = before.asOfDate,
        )
    }

    /** Bar length for a holding: its market value against the largest one. */
    fun barFraction(holding: Holding, holdings: List<Holding>): Float {
        val peak = holdings.maxOfOrNull { abs(it.marketValue.amount) }?.coerceAtLeast(1) ?: 1
        return (abs(holding.marketValue.amount).toFloat() / peak).coerceIn(0f, 1f)
    }

    /** `+1.2%` / `−0.4%`, to one decimal. */
    fun signedPercent(percent: Double): String {
        val rounded = BigDecimal.valueOf(percent).setScale(1, RoundingMode.HALF_UP)
        return (if (rounded.signum() >= 0) "+" else "") + rounded.toPlainString() + "%"
    }
}
