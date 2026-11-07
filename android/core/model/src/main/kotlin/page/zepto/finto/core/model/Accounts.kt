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
    val balanceGroup: String? = null,
    val maskedNumber: String? = null,
    val openedOn: String? = null,
    val closedOn: String? = null,
    val watchStatements: Boolean = true,
) {
    val isOpen: Boolean get() = closedOn == null
}

@Serializable
data class Card(
    val id: String,
    val accountId: String,
    val cardholderName: String? = null,
    val last4: String? = null,
    val isSupplementary: Boolean = false,
    val issuedOn: String? = null,
    val closedOn: String? = null,
    val replacesCardId: String? = null,
    val lineageRoot: String? = null,
)

@Serializable
data class CardsResponse(val cards: List<Card> = emptyList())

@Serializable
data class AccountsResponse(val accounts: List<Account> = emptyList())

@Serializable
data class Institution(
    val id: String,
    val displayName: String,
    val country: String? = null,
)

@Serializable
data class InstitutionsResponse(val institutions: List<Institution> = emptyList())

/** The body of `POST /api/accounts`: a new account, and its card when it has one. */
@Serializable
data class NewAccount(
    val id: String,
    val institutionId: String,
    val displayName: String,
    val accountType: String,
    val primaryCurrency: String,
    val last4: String? = null,
    val cardholderName: String? = null,
)

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
    /** Net worth on the last day every account's statements cover; null when that is today. */
    val complete: SettledPositions? = null,
)

/** Figures every statement has confirmed, as of [asOf]. */
@Serializable
data class SettledPositions(
    val asOf: String,
    /** Accounts whose statements stop on that day. */
    val limitedBy: List<String> = emptyList(),
    val netWorth: Money,
    val byType: List<TypeBalance> = emptyList(),
)

@Serializable
data class NetWorthSeries(
    val to: String = "",
    val points: List<NetWorthPoint> = emptyList(),
)

/** Month-end net worth; [complete] when every statement reaches that day. */
@Serializable
data class NetWorthPoint(
    val bucket: String,
    val asOf: String,
    val balance: Money,
    val complete: Boolean = true,
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
