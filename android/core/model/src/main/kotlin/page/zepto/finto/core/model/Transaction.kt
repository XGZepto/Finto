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
