package page.zepto.finto.core.model

import kotlinx.serialization.Serializable

/** An instalment plan. Principal, outstanding and per-instalment are negative (owed). */
@Serializable
data class InstallmentPlan(
    val id: String,
    val accountId: String,
    val cardId: String? = null,
    val merchant: String? = null,
    val description: String = "",
    val principal: Money,
    val termMonths: Int,
    val startDate: String,
    val status: String = "active",
    val confidence: Double = 1.0,
    val isConfirmed: Boolean = true,
    val paidCount: Int = 0,
    val settledEarly: Boolean = false,
    val paid: Money? = null,
    val remainingCount: Int = 0,
    val outstanding: Money,
    val perInstallment: Money,
    val charges: List<InstallmentCharge> = emptyList(),
) {
    val title: String get() = merchant?.takeIf { it.isNotBlank() } ?: description
    val isActive: Boolean get() = status == "active"
}

@Serializable
data class InstallmentCharge(
    val id: String,
    val txnDate: String,
    val descriptionRaw: String = "",
    val amountBooked: Long,
    val currencyBooked: String,
    val installmentSeq: Int? = null,
    val isSettlement: Boolean = false,
) {
    val amount: Money get() = Money(amountBooked, currencyBooked)
}

@Serializable
data class CurrencyAmount(val currency: String, val amount: Long) {
    val money: Money get() = Money(amount, currency)
}

@Serializable
data class InstallmentsResponse(
    val plans: List<InstallmentPlan> = emptyList(),
    val outstandingByCurrency: List<CurrencyAmount> = emptyList(),
    val committedMonthlyByCurrency: List<CurrencyAmount> = emptyList(),
)
