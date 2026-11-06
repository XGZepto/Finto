package page.zepto.finto.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Txn(
    val id: String,
    val date: String,
    val postedDate: String? = null,
    val accountId: String,
    val accountName: String,
    val accountCurrency: String,
    val institutionId: String,
    val cardId: String? = null,
    val cardholderName: String? = null,
    val cardLast4: String? = null,
    val description: String,
    val merchant: String? = null,
    val counterparty: String? = null,
    val booked: Money,
    val native: Money? = null,
    val fxRate: String? = null,
    val fxFee: Money? = null,
    val kind: String,
    val category: String? = null,
    val subcategory: String? = null,
    val status: String = "posted",
    val transferGroupId: String? = null,
    val installmentPlanId: String? = null,
    val installmentSeq: Int? = null,
    val refundOfId: String? = null,
    val reviewState: String = "unreviewed",
    val notes: String? = null,
    val details: Map<String, JsonElement> = emptyMap(),
    val tags: List<String> = emptyList(),
    val provenance: Provenance? = null,
    val transferLegs: List<LinkedRow> = emptyList(),
    val relatedTransactions: List<LinkedRow> = emptyList(),
) {
    /** The name to show: the cleaned-up merchant when there is one. */
    val title: String get() = merchant?.takeIf { it.isNotBlank() } ?: description

    /** Statement facts as display strings, whatever JSON type the parser stored. */
    val detailStrings: Map<String, String>
        get() = details.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.content ?: value.toString()
        }

    val isTransfer: Boolean get() = transferGroupId != null
}

@Serializable
data class Provenance(
    val sourcePath: String? = null,
    val parserId: String? = null,
    val importedAt: String? = null,
)

@Serializable
data class TxnPage(
    val total: Int? = null,
    val limit: Int = 0,
    val offset: Int = 0,
    val items: List<Txn> = emptyList(),
    val totals: List<TotalRow> = emptyList(),
)

/** The other side of a transfer, or a purchase/refund/duplicate this row is tied to. */
@Serializable
data class LinkedRow(
    val id: String,
    val role: String? = null,
    /** `purchase`, `refund`, `canonical` or `duplicate`, for related rows. */
    val relation: String? = null,
    val descriptionRaw: String = "",
    val amountBooked: Long = 0,
    val currencyBooked: String = "",
    val accountId: String = "",
    val txnDate: String = "",
) {
    val amount: Money get() = Money(amountBooked, currencyBooked)
}

@Serializable
data class CategorySuggestion(
    val category: String,
    val subcategory: String? = null,
    val merchant: String? = null,
    val tags: List<String> = emptyList(),
    val confidence: Double = 0.0,
)

@Serializable
data class SuggestionResponse(
    val available: Boolean = false,
    val suggestion: CategorySuggestion? = null,
)

@Serializable
data class TagRequest(val tag: String)

/** Distinct values for filters and editors. */
@Serializable
data class Facets(
    val accounts: List<FacetAccount> = emptyList(),
    val categories: List<String> = emptyList(),
    val kinds: List<String> = emptyList(),
    val currencies: List<String> = emptyList(),
)

@Serializable
data class FacetAccount(
    val id: String,
    val displayName: String,
    val institutionId: String = "",
    val accountType: String = "",
    val primaryCurrency: String = "",
)
