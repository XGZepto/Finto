package page.zepto.finto.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.model.ApiKey
import page.zepto.finto.core.model.ApiKeyRequest
import page.zepto.finto.core.model.AskEvent
import page.zepto.finto.core.model.AskRequest
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
import page.zepto.finto.core.model.ResolveRequest
import page.zepto.finto.core.model.ReviewQueue
import page.zepto.finto.core.model.ReviewQueueResponse
import page.zepto.finto.core.network.AskStreamParser
import page.zepto.finto.core.network.FintoApi
import page.zepto.finto.core.network.NetworkCache
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Matching suggestions: duplicates, transfers and instalment runs to accept or reject. */
interface ReviewRepository {
    suspend fun queue(queue: ReviewQueue, policy: ReadPolicy = ReadPolicy.Default): ReviewQueueResponse
    suspend fun resolve(queue: ReviewQueue, id: String, accept: Boolean)
}

@Singleton
internal class NetworkReviewRepository @Inject constructor(
    private val api: FintoApi,
    private val cache: NetworkCache,
) : ReviewRepository {
    override suspend fun queue(queue: ReviewQueue, policy: ReadPolicy) = api.reviewQueue(queue.path, policy.header)

    override suspend fun resolve(queue: ReviewQueue, id: String, accept: Boolean) {
        api.resolve(queue.path, id, ResolveRequest(if (accept) "accept" else "reject"))
        cache.clear()
    }
}

interface InvestmentsRepository {
    suspend fun snapshots(policy: ReadPolicy = ReadPolicy.Default): List<InvestmentSnapshot>
    suspend fun snapshot(id: String, policy: ReadPolicy = ReadPolicy.Default): InvestmentDetail
    suspend fun history(scheme: String?, accountId: String?, policy: ReadPolicy = ReadPolicy.Default): InvestmentHistory
    suspend fun activities(accountId: String?, policy: ReadPolicy = ReadPolicy.Default): List<InvestmentActivity>
    suspend fun previewBundle(files: List<StagedFile>): MpfBundlePreview
    suspend fun confirmBundle(files: List<StagedFile>, bundleSha256: String): MpfImportResult
}

@Singleton
internal class NetworkInvestmentsRepository @Inject constructor(
    private val api: FintoApi,
    private val cache: NetworkCache,
) : InvestmentsRepository {
    override suspend fun snapshots(policy: ReadPolicy) = api.investments(policy.header).snapshots
    override suspend fun snapshot(id: String, policy: ReadPolicy) = api.investment(id, policy.header)
    override suspend fun history(scheme: String?, accountId: String?, policy: ReadPolicy) =
        api.investmentHistory(scheme, accountId, policy.header)
    override suspend fun activities(accountId: String?, policy: ReadPolicy) =
        api.investmentActivities(accountId, policy.header).activities

    override suspend fun previewBundle(files: List<StagedFile>) = api.previewMpfBundle(files.map(::part))

    override suspend fun confirmBundle(files: List<StagedFile>, bundleSha256: String) =
        api.confirmMpfBundle(files.map(::part), bundleSha256.toRequestBody()).also { cache.clear() }

    private fun part(file: StagedFile) = MultipartBody.Part.createFormData(
        "files",
        file.name,
        File(file.path).asRequestBody(file.mimeType.toMediaTypeOrNull()),
    )
}

/** Questions to the ledger, answered from its own tools and streamed as they run. */
interface AskRepository {
    fun ask(question: String, history: List<AskTurnContext>): Flow<AskEvent>

    /** Applies a correction Ask proposed, as the signed-in user's own edit. */
    suspend fun applyChange(id: String): ChangeProposal = error("read-only")

    suspend fun undoChange(id: String): ChangeProposal = error("read-only")

    suspend fun dismissChange(id: String): ChangeProposal = error("read-only")
}

@Singleton
internal class NetworkAskRepository @Inject constructor(
    private val api: FintoApi,
    private val cache: NetworkCache,
) : AskRepository {
    // Applying or undoing changes figures every screen shows; drop cached reads.
    override suspend fun applyChange(id: String) = api.applyChange(id).also { cache.clear() }

    override suspend fun undoChange(id: String) = api.undoChange(id).also { cache.clear() }

    override suspend fun dismissChange(id: String) = api.dismissChange(id)

    override fun ask(question: String, history: List<AskTurnContext>): Flow<AskEvent> = flow {
        val request = AskRequest(question, history.takeLast(MAX_HISTORY).map { it.copy(answer = it.answer.take(MAX_ANSWER)) })
        var started = false
        val streamed = flow {
            emitAll(AskStreamParser.events(api.askStream(request)))
        }.catch { error ->
            // Nothing arrived: fall back to the one-shot endpoint, as the web does.
            if (started) throw error
            emit(AskEvent.Done(api.ask(AskRequest(question))))
        }
        streamed.collect { event ->
            started = true
            emit(event)
        }
    }

    private companion object {
        const val MAX_HISTORY = 3
        const val MAX_ANSWER = 800
    }
}

/** Account access, import history and review, and ledger maintenance. */
interface AdminRepository {
    suspend fun apiKeys(): List<ApiKey>
    suspend fun createApiKey(name: String): ApiKey
    suspend fun revokeApiKey(id: String)
    suspend fun importHistory(policy: ReadPolicy = ReadPolicy.Default): ImportHistory
    suspend fun reviewImport(statementFileId: String): ImportReview
    suspend fun lastImportReview(statementFileId: String): ImportReview?
    suspend fun reconcile(): JsonObject
    suspend fun reattribute(): JsonObject
    suspend fun harvestFx(): JsonObject
}

@Singleton
internal class NetworkAdminRepository @Inject constructor(
    private val api: FintoApi,
    private val cache: NetworkCache,
) : AdminRepository {
    override suspend fun apiKeys() = api.apiKeys().keys
    override suspend fun createApiKey(name: String) = api.createApiKey(ApiKeyRequest(name))
    override suspend fun revokeApiKey(id: String) {
        api.revokeApiKey(id)
    }
    override suspend fun importHistory(policy: ReadPolicy) = api.importHistory(policy.header)
    override suspend fun reviewImport(statementFileId: String) =
        api.reviewImport(statementFileId).also { cache.clear() }
    override suspend fun lastImportReview(statementFileId: String) = api.lastImportReview(statementFileId).review
    override suspend fun reconcile() = api.reconcile().also { cache.clear() }
    override suspend fun reattribute() = api.reattribute().also { cache.clear() }
    override suspend fun harvestFx() = api.harvestFx().also { cache.clear() }
}
