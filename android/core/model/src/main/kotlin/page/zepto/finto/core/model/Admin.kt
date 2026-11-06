package page.zepto.finto.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiKey(
    val id: String,
    val name: String,
    val prefix: String,
    val scopes: List<String> = emptyList(),
    val createdAt: String,
    val lastUsedAt: String? = null,
    /** The full key, present only in the response that created it. */
    val key: String? = null,
)

@Serializable
data class ApiKeysResponse(val keys: List<ApiKey> = emptyList())

@Serializable
data class ApiKeyRequest(val name: String)

@Serializable
data class ImportedFile(
    val id: String,
    val sourcePath: String = "",
    val institutionId: String? = null,
    val accountId: String? = null,
    val fileFormat: String? = null,
    val parserId: String? = null,
    val importedAt: String = "",
    val rowCount: Int = 0,
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val txnCount: Int = 0,
    /** `clean`, `attention`, or null when never reviewed. */
    val reviewStatus: String? = null,
) {
    val fileName: String get() = sourcePath.substringAfterLast('/').substringAfter("__")
}

@Serializable
data class ImportHistory(val files: List<ImportedFile> = emptyList())

/** What the post-import pass found and did for one statement. */
@Serializable
data class ImportReview(
    val statement: ReviewedStatement,
    val categories: ReviewCategories = ReviewCategories(),
    val links: ReviewLinks = ReviewLinks(),
    val balances: List<ReviewBalance> = emptyList(),
    val model: ReviewModel = ReviewModel(),
    val summary: ReviewSummary,
    val reviewedAt: String = "",
)

@Serializable
data class ReviewedStatement(
    val id: String,
    val file: String = "",
    val accounts: List<String> = emptyList(),
    val rows: Int = 0,
)

@Serializable
data class ReviewCategories(
    val bySource: Map<String, Int> = emptyMap(),
    val movementFixed: Int = 0,
    val modelApplied: Int = 0,
    val lowConfidence: Int = 0,
    val merchantsNamed: Int = 0,
    val taggedRows: Int = 0,
    val tags: Int = 0,
    val topUncategorised: List<UncategorisedGroup> = emptyList(),
)

@Serializable
data class UncategorisedGroup(val description: String, val rows: Int)

@Serializable
data class ReviewLinks(
    val movements: Int = 0,
    val linked: Int = 0,
    val unlinked: List<UnlinkedRow> = emptyList(),
    val unlinkedCount: Int = 0,
    val openTransferCandidates: Int = 0,
    val openDuplicateCandidates: Int = 0,
    val mergedDuplicates: Int = 0,
    val refundsLinked: Int = 0,
)

@Serializable
data class UnlinkedRow(
    val id: String,
    val date: String,
    val account: String = "",
    val description: String = "",
    /** Already formatted by the server. */
    val amount: String = "",
)

@Serializable
data class ReviewBalance(
    val accountId: String,
    val accountName: String = "",
    val periodStart: String = "",
    val periodEnd: String = "",
    val discrepancy: Money,
)

@Serializable
data class ReviewModel(val available: Boolean = false)

@Serializable
data class ReviewSummary(
    /** `clean` or `attention`. */
    val status: String,
    val headline: String = "",
    val notes: List<String> = emptyList(),
    val model: String? = null,
)

@Serializable
data class ImportReviewResponse(val review: ImportReview? = null)

/** What reconciliation did after an import or on demand; only non-zero counts are worth showing. */
@Serializable
data class ReconcileSummary(
    val transactions: Int = 0,
    val duplicatesMerged: Int = 0,
    val duplicateCandidates: Int = 0,
    val transfersLinked: Int = 0,
    val transferCandidates: Int = 0,
    val installmentPlans: Int = 0,
    val installmentCandidates: Int = 0,
    val refundsLinked: Int = 0,
)

/** The result of a maintenance action; the server returns a loose object of counts. */
typealias MaintenanceResult = JsonObject
