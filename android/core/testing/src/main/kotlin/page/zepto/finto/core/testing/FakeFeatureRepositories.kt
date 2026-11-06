package page.zepto.finto.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.data.repository.AdminRepository
import page.zepto.finto.core.data.repository.AskRepository
import page.zepto.finto.core.data.repository.InvestmentsRepository
import page.zepto.finto.core.data.repository.ReviewRepository
import page.zepto.finto.core.data.repository.StagedFile
import page.zepto.finto.core.model.ApiKey
import page.zepto.finto.core.model.AskEvent
import page.zepto.finto.core.model.AskTurnContext
import page.zepto.finto.core.model.ChangeProposal
import page.zepto.finto.core.model.ImportHistory
import page.zepto.finto.core.model.ImportReview
import page.zepto.finto.core.model.InvestmentActivity
import page.zepto.finto.core.model.InvestmentDetail
import page.zepto.finto.core.model.InvestmentHistory
import page.zepto.finto.core.model.InvestmentSnapshot
import page.zepto.finto.core.model.MpfBundlePreview
import page.zepto.finto.core.model.MpfImportResult
import page.zepto.finto.core.model.MpfSnapshotPreview
import page.zepto.finto.core.model.MpfSnapshotStatus
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.ReviewQueue
import page.zepto.finto.core.model.ReviewQueueResponse

class FakeReviewRepository(
    var queues: Map<ReviewQueue, ReviewQueueResponse> = emptyMap(),
) : ReviewRepository {
    val resolved = mutableListOf<Triple<ReviewQueue, String, Boolean>>()
    var failure: Throwable? = null

    override suspend fun queue(queue: ReviewQueue, policy: ReadPolicy): ReviewQueueResponse {
        failure?.let { throw it }
        return queues[queue] ?: ReviewQueueResponse()
    }

    override suspend fun resolve(queue: ReviewQueue, id: String, accept: Boolean) {
        failure?.let { throw it }
        resolved += Triple(queue, id, accept)
    }
}

class FakeInvestmentsRepository : InvestmentsRepository {
    var snapshots: List<InvestmentSnapshot> = emptyList()
    var details: Map<String, InvestmentDetail> = emptyMap()
    var history: (scheme: String?, accountId: String?) -> InvestmentHistory = { _, _ -> InvestmentHistory() }
    var activities: (accountId: String?) -> List<InvestmentActivity> = { emptyList() }
    var preview: MpfBundlePreview = MpfBundlePreview(
        bundleSha256 = "bundle123",
        snapshot = MpfSnapshotPreview(asOfDate = "2026-09-30", total = Money(0, "HKD")),
    )
    var result: MpfImportResult = MpfImportResult(bundleSha256 = "bundle123", snapshot = MpfSnapshotStatus("imported", "snap-1"))
    var failure: Throwable? = null
    val confirmed = mutableListOf<String>()

    private fun check() = failure?.let { throw it }

    override suspend fun snapshots(policy: ReadPolicy): List<InvestmentSnapshot> = snapshots.also { check() }
    override suspend fun snapshot(id: String, policy: ReadPolicy): InvestmentDetail = details.getValue(id).also { check() }
    override suspend fun history(scheme: String?, accountId: String?, policy: ReadPolicy): InvestmentHistory {
        check()
        return history.invoke(scheme, accountId)
    }
    override suspend fun activities(accountId: String?, policy: ReadPolicy): List<InvestmentActivity> {
        check()
        return activities.invoke(accountId)
    }
    override suspend fun previewBundle(files: List<StagedFile>): MpfBundlePreview = preview.also { check() }
    override suspend fun confirmBundle(files: List<StagedFile>, bundleSha256: String): MpfImportResult {
        check()
        confirmed += bundleSha256
        return result
    }
}

/** Replays [events] for every question; records what was asked. */
class FakeAskRepository(var events: (question: String) -> List<AskEvent> = { emptyList() }) : AskRepository {
    val asked = mutableListOf<Pair<String, List<AskTurnContext>>>()

    /** What each change action answers; by default the proposal moves to the matching status. */
    var changeResult: (action: String, id: String) -> ChangeProposal = { action, id ->
        ChangeProposal(id = id, status = when (action) { "apply" -> "applied"; "undo" -> "undone"; else -> "dismissed" })
    }
    var changeFailure: Throwable? = null
    val changeActions = mutableListOf<Pair<String, String>>()

    private fun change(action: String, id: String): ChangeProposal {
        changeActions += action to id
        changeFailure?.let { throw it }
        return changeResult(action, id)
    }

    override suspend fun applyChange(id: String) = change("apply", id)
    override suspend fun undoChange(id: String) = change("undo", id)
    override suspend fun dismissChange(id: String) = change("dismiss", id)

    override fun ask(question: String, history: List<AskTurnContext>): Flow<AskEvent> {
        asked += question to history
        return events(question).asFlow()
    }
}

class FakeAdminRepository : AdminRepository {
    var keys: MutableList<ApiKey> = mutableListOf()
    var history = ImportHistory()
    var reviews: MutableMap<String, ImportReview> = mutableMapOf()
    var failure: Throwable? = null
    val maintenance = mutableListOf<String>()

    private fun check() = failure?.let { throw it }

    override suspend fun apiKeys(): List<ApiKey> = keys.toList().also { check() }

    override suspend fun createApiKey(name: String): ApiKey {
        check()
        val key = ApiKey(id = "key-${keys.size + 1}", name = name, prefix = "fk_test", createdAt = "2026-11-06T09:00:00Z", key = "fk_test_secret")
        keys += key
        return key
    }

    override suspend fun revokeApiKey(id: String) {
        check()
        keys.removeAll { it.id == id }
    }

    override suspend fun importHistory(policy: ReadPolicy) = history.also { check() }
    override suspend fun reviewImport(statementFileId: String) = reviews.getValue(statementFileId).also { check() }
    override suspend fun lastImportReview(statementFileId: String) = reviews[statementFileId].also { check() }
    override suspend fun reconcile() = done("reconcile")
    override suspend fun reattribute() = done("reattribute")
    override suspend fun harvestFx() = done("harvest")

    private fun done(name: String): JsonObject {
        check()
        maintenance += name
        return JsonObject(mapOf("ok" to JsonPrimitive(true)))
    }
}
