package page.zepto.finto.core.model

import kotlinx.serialization.Serializable

/**
 * An MPF valuation: units held, not cash. Contributions that left a bank
 * account are ordinary transactions; this moves with the market.
 */
@Serializable
data class InvestmentSnapshot(
    val id: String,
    val asOfDate: String,
    val scheme: String,
    val total: Money,
    val source: String = "",
    val notes: String? = null,
)

@Serializable
data class InvestmentsResponse(val snapshots: List<InvestmentSnapshot> = emptyList())

@Serializable
data class InvestmentDetail(
    val id: String,
    val asOfDate: String,
    val scheme: String,
    val total: Money,
    val subaccounts: List<InvestmentSubaccount> = emptyList(),
    val holdings: List<Holding> = emptyList(),
)

@Serializable
data class InvestmentSubaccount(
    val accountId: String,
    val memberNo: String? = null,
    val balance: Money,
    /** A fraction as a decimal string, e.g. `0.4210`. */
    val allocation: String? = null,
)

@Serializable
data class Holding(
    val instrument: String,
    val units: String? = null,
    val unitPrice: String? = null,
    val marketValue: Money,
    val allocation: String? = null,
)

@Serializable
data class InvestmentHistory(
    val scheme: String? = null,
    val accountId: String? = null,
    val points: List<ValuePoint> = emptyList(),
)

@Serializable
data class ValuePoint(val asOfDate: String, val value: Money)

@Serializable
data class InvestmentActivity(
    val id: String,
    val accountId: String,
    val memberNo: String? = null,
    val activityDate: String,
    val contributionType: String = "",
    val activityType: String = "",
    val amount: Money,
)

@Serializable
data class ActivitiesResponse(val activities: List<InvestmentActivity> = emptyList())

@Serializable
data class MpfBundlePreview(
    val bundleSha256: String,
    val documents: List<MpfDocument> = emptyList(),
    val snapshot: MpfSnapshotPreview,
    val activities: MpfActivityCount = MpfActivityCount(),
)

@Serializable
data class MpfDocument(
    val filename: String,
    val classification: String = "",
    /** `deterministic` or `llm`. */
    val parser: String? = null,
    val model: String? = null,
)

@Serializable
data class MpfSnapshotPreview(
    val asOfDate: String,
    val reportedDate: String? = null,
    val total: Money,
    val subaccounts: List<InvestmentSubaccount> = emptyList(),
    /** Number of fund holdings read. */
    val holdings: Int = 0,
)

@Serializable
data class MpfActivityCount(
    val count: Int = 0,
    /** Activities read per member account. */
    val byAccount: Map<String, Int> = emptyMap(),
)

@Serializable
data class MpfImportResult(
    val bundleSha256: String = "",
    val snapshot: MpfSnapshotStatus,
    val activities: MpfActivityResult = MpfActivityResult(),
)

@Serializable
data class MpfSnapshotStatus(val status: String, val id: String? = null)

@Serializable
data class MpfActivityResult(val inserted: Int = 0, val skipped: Int = 0)
