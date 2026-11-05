package page.zepto.finto.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val institutionId: String,
    val displayName: String,
    val accountType: String,
    val primaryCurrency: String,
    val settlementCurrencies: List<String> = emptyList(),
    val maskedNumber: String? = null,
    val openedOn: String? = null,
    val closedOn: String? = null,
)

@Serializable
data class AccountsResponse(val accounts: List<Account> = emptyList())

/** A balance per (account, currency), never summed across currencies natively. */
@Serializable
data class Position(
    val accountId: String,
    val accountName: String,
    val institutionId: String,
    val accountType: String,
    val currency: String,
    val txnCount: Int = 0,
    val balance: Money,
    val balanceConverted: ConvertedMoney? = null,
    /** `statement`, `movements` or `investment_snapshot`. */
    val basis: String = "movements",
    val basisDate: String? = null,
    val coveredThrough: String? = null,
    val complete: Boolean = true,
    val firstDate: String? = null,
    val lastDate: String? = null,
)

@Serializable
data class PositionsResponse(
    val positions: List<Position> = emptyList(),
    val normalised: PositionsNormalised? = null,
)

@Serializable
data class PositionsNormalised(
    val to: String,
    val netWorth: Money,
    val byType: List<TypeBalance> = emptyList(),
    val unconvertibleCurrencies: List<String> = emptyList(),
)

@Serializable
data class TypeBalance(val accountType: String, val balance: Money)

@Serializable
data class StatementFreshness(
    val asOf: String,
    val staleCount: Int = 0,
    val accounts: List<FreshnessAccount> = emptyList(),
)

@Serializable
data class FreshnessAccount(
    val accountId: String,
    val displayName: String,
    val statementDate: String? = null,
    val latestActivity: String? = null,
    val expectedOn: String? = null,
    /** `current`, `stale`, `unknown`, `closed` or `ignored`. */
    val status: String,
    val daysOverdue: Int = 0,
)
