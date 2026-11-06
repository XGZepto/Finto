package page.zepto.finto.core.model

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Turns integer minor units into display text, once, at the edge.
 *
 * The number of decimals comes from the currency (JPY has none, HKD two), so a
 * figure is never rounded through a float.
 */
object MoneyFormat {

    fun fractionDigits(currency: String): Int =
        runCatching { Currency.getInstance(currency).defaultFractionDigits }
            .getOrDefault(2)
            .coerceAtLeast(0)

    fun toMajor(money: Money): BigDecimal =
        BigDecimal.valueOf(money.amount, fractionDigits(money.currency))

    /**
     * `HK$1,234.56`, `-US$12.00`. With [signed], money in gets an explicit `+`
     * so a list of mixed rows reads at a glance.
     */
    fun format(
        money: Money,
        locale: Locale = Locale.getDefault(),
        signed: Boolean = false,
        absolute: Boolean = false,
    ): String {
        val digits = fractionDigits(money.currency)
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            runCatching { currency = Currency.getInstance(money.currency) }
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        val value = if (absolute) toMajor(money).abs() else toMajor(money)
        val text = format.format(value)
        return if (signed && !absolute && money.amount > 0) "+$text" else text
    }

    /** Whole units only, for headline figures where cents are noise: `HK$12,346`. */
    fun formatWhole(money: Money, locale: Locale = Locale.getDefault(), absolute: Boolean = false): String {
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            runCatching { currency = Currency.getInstance(money.currency) }
            minimumFractionDigits = 0
            maximumFractionDigits = 0
        }
        val value = toMajor(money).let { if (absolute) it.abs() else it }
        return format.format(value)
    }
}
