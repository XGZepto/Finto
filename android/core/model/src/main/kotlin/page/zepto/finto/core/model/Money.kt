package page.zepto.finto.core.model

import kotlinx.serialization.Serializable

/**
 * An amount in integer minor units with its ISO currency code.
 *
 * The ledger never stores a fraction of a unit, so neither does the app: a
 * figure becomes a decimal only when it is formatted for display. Negative is
 * money out, positive is money in.
 */
@Serializable
data class Money(
    val amount: Long,
    val currency: String,
) {
    val isOutflow: Boolean get() = amount < 0

    operator fun unaryMinus(): Money = copy(amount = -amount)

    fun abs(): Money = if (amount < 0) copy(amount = -amount) else this

    companion object {
        fun zero(currency: String) = Money(0, currency)
    }
}

/** A figure converted into a reporting currency, or a record that it could not be. */
@Serializable
data class ConvertedMoney(
    val amount: Long,
    val currency: String,
    val rate: String? = null,
    val rateDate: String? = null,
    val converted: Boolean = false,
    val ok: Boolean = false,
) {
    fun asMoney(): Money = Money(amount, currency)
}

/** Currencies the server can report in, in the web app's order. */
val ReportingCurrencies = listOf("USD", "HKD", "GBP", "EUR", "JPY", "CNY", "SGD", "AUD", "CAD")
