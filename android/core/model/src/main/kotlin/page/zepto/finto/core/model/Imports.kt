package page.zepto.finto.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImportCapabilities(
    val extensions: List<String> = emptyList(),
    val formats: List<ImportFormat> = emptyList(),
)

@Serializable
data class ImportFormat(
    val id: String,
    val label: String,
    val fileFormat: String,
    val institutions: List<String> = emptyList(),
    val verified: Boolean = false,
)

/** What a statement would import, before anything is written. */
@Serializable
data class StagePreview(
    val sha256: String,
    val filename: String,
    val sizeBytes: Long = 0,
    val parser: String? = null,
    val parserVersion: String? = null,
    val txnCount: Int? = null,
    val balanceCount: Int? = null,
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val warnings: List<String> = emptyList(),
    val sample: List<SampleRow> = emptyList(),
    val error: String? = null,
    /** In and out per currency, in minor units. */
    val totals: Map<String, InOut> = emptyMap(),
    val quality: ImportQuality? = null,
    val agent: ImportAgent? = null,
    /** The issuer the parser or PDF template belongs to, for filing a new account. */
    val institutionHint: String? = null,
    /** The card numbers printed on the statement, by last four digits. */
    val cards: List<StatementCard> = emptyList(),
    val statementDate: String? = null,
) {
    /** The check that says where the rows land, if the server ran one. */
    val accountCheck: QualityCheck? get() = quality?.checks?.firstOrNull { it.id == "account" }

    /** True when some rows have no account yet: the person has to pick or create one. */
    val needsAccount: Boolean get() = accountCheck?.level == "bad"
}

@Serializable
data class StatementCard(
    val last4: String,
    val holder: String? = null,
    val rows: Int = 0,
)

@Serializable
data class SampleRow(
    val date: String,
    val description: String,
    val amount: Long,
    val currency: String,
)

@Serializable
data class InOut(
    @SerialName("in") val inflow: Long = 0,
    val out: Long = 0,
    val rows: Int = 0,
)

@Serializable
data class ImportQuality(
    /** `ok`, `info`, `warn` or `bad`. */
    val grade: String,
    val checks: List<QualityCheck> = emptyList(),
)

@Serializable
data class QualityCheck(
    val id: String,
    val level: String,
    val title: String,
    val detail: String = "",
)

/** The analysis model's read of a preview, when it was asked and available. */
@Serializable
data class ImportAgent(
    /** `import`, `review` or `hold`. */
    val verdict: String,
    val headline: String,
    val notes: List<String> = emptyList(),
    val model: String? = null,
)

@Serializable
data class ConfirmResponse(
    @SerialName("import") val result: ImportResult,
    val reconcile: ReconcileSummary? = null,
)

@Serializable
data class ImportResult(
    /** `imported`, or why nothing was: `duplicate`, `error`, ... */
    val status: String,
    val txns: Int = 0,
    val balances: Int = 0,
    val statementFileId: String? = null,
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val warnings: List<String> = emptyList(),
    val reason: String? = null,
)
