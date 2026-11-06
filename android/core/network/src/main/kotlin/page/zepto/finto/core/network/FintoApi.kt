package page.zepto.finto.core.network

import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import page.zepto.finto.core.model.AccountsResponse
import page.zepto.finto.core.model.Account
import page.zepto.finto.core.model.ActivitiesResponse
import page.zepto.finto.core.model.ApiKey
import page.zepto.finto.core.model.ApiKeyRequest
import page.zepto.finto.core.model.ApiKeysResponse
import page.zepto.finto.core.model.AskRequest
import page.zepto.finto.core.model.CardsResponse
import page.zepto.finto.core.model.Composition
import page.zepto.finto.core.model.ConfirmResponse
import page.zepto.finto.core.model.Coverage
import page.zepto.finto.core.model.Facets
import page.zepto.finto.core.model.Flows
import page.zepto.finto.core.model.ImportCapabilities
import page.zepto.finto.core.model.ImportHistory
import page.zepto.finto.core.model.ImportReview
import page.zepto.finto.core.model.ImportReviewResponse
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.InstallmentsResponse
import page.zepto.finto.core.model.IntegrityReport
import page.zepto.finto.core.model.InvestmentDetail
import page.zepto.finto.core.model.InvestmentHistory
import page.zepto.finto.core.model.InvestmentsResponse
import page.zepto.finto.core.model.LoginRequest
import page.zepto.finto.core.model.LoginResponse
import page.zepto.finto.core.model.MpfBundlePreview
import page.zepto.finto.core.model.MpfImportResult
import page.zepto.finto.core.model.NetWorthSeries
import page.zepto.finto.core.model.OkResponse
import page.zepto.finto.core.model.PositionsResponse
import page.zepto.finto.core.model.PreferencesPatch
import page.zepto.finto.core.model.QueryResult
import page.zepto.finto.core.model.ResolveRequest
import page.zepto.finto.core.model.ReviewQueueResponse
import page.zepto.finto.core.model.StagePreview
import page.zepto.finto.core.model.StatementFreshness
import page.zepto.finto.core.model.SuggestionResponse
import page.zepto.finto.core.model.SummaryResponse
import page.zepto.finto.core.model.TagRequest
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.TxnPage
import page.zepto.finto.core.model.User
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Streaming

/**
 * The Finto HTTP API. Paths are relative: [ServerEndpoint] supplies the host,
 * so one client follows the server the user signed in to.
 *
 * Every read takes a `Cache-Control` header: null for ordinary HTTP caching
 * (the server sets `private, max-age`), [CacheControls.CACHE_ONLY] to paint
 * the last copy instantly, [CacheControls.NETWORK] to force a fresh read.
 *
 * Ledger filters travel as [scalars] (a map of single-valued fields) plus one
 * repeated parameter per list field, exactly as the web app sends them.
 */
@Suppress("LongParameterList", "TooManyFunctions")
interface FintoApi {

    // --- Session --------------------------------------------------------------

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("api/auth/logout")
    suspend fun logout(): OkResponse

    @GET("api/auth/me")
    suspend fun me(): User

    @PATCH("api/auth/preferences")
    suspend fun updatePreferences(@Body patch: PreferencesPatch): User

    @GET("api/auth/api-keys")
    suspend fun apiKeys(): ApiKeysResponse

    @POST("api/auth/api-keys")
    suspend fun createApiKey(@Body body: ApiKeyRequest): ApiKey

    @DELETE("api/auth/api-keys/{id}")
    suspend fun revokeApiKey(@Path("id") id: String): OkResponse

    // --- Ledger reads ---------------------------------------------------------

    @GET("api/transactions")
    suspend fun transactions(
        @QueryMap scalars: Map<String, String>,
        @Query("months") months: List<String>?,
        @Query("accounts") accounts: List<String>?,
        @Query("cards") cards: List<String>?,
        @Query("cardholders") cardholders: List<String>?,
        @Query("institutions") institutions: List<String>?,
        @Query("categories") categories: List<String>?,
        @Query("kinds") kinds: List<String>?,
        @Query("tags") tags: List<String>?,
        @Query("detail") detail: List<String>?,
        @Query("statements") statements: List<String>?,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("sort") sort: String = "date",
        @Query("direction") direction: String = "desc",
        @Query("convert_to") convertTo: String? = null,
        @Header("Cache-Control") cacheControl: String? = null,
    ): TxnPage

    @GET("api/transactions/{id}")
    suspend fun transaction(
        @Path("id") id: String,
        @Header("Cache-Control") cacheControl: String? = null,
    ): Txn

    @GET("api/summary")
    suspend fun summary(
        @Query("group_by") groupBy: String,
        @QueryMap scalars: Map<String, String>,
        @Query("months") months: List<String>?,
        @Query("accounts") accounts: List<String>?,
        @Query("cards") cards: List<String>?,
        @Query("cardholders") cardholders: List<String>?,
        @Query("institutions") institutions: List<String>?,
        @Query("categories") categories: List<String>?,
        @Query("kinds") kinds: List<String>?,
        @Query("tags") tags: List<String>?,
        @Query("detail") detail: List<String>?,
        @Query("statements") statements: List<String>?,
        @Query("convert_to") convertTo: String?,
        @Header("Cache-Control") cacheControl: String? = null,
    ): SummaryResponse

    @GET("api/composition")
    suspend fun composition(
        @Query("dimension") dimension: String,
        @Query("convert_to") convertTo: String,
        @Query("spendingOnly") spendingOnly: Boolean = true,
        @Header("Cache-Control") cacheControl: String? = null,
    ): Composition

    @GET("api/coverage")
    suspend fun coverage(@Header("Cache-Control") cacheControl: String? = null): Coverage

    @GET("api/positions")
    suspend fun positions(
        @Query("convert_to") convertTo: String?,
        @Header("Cache-Control") cacheControl: String? = null,
    ): PositionsResponse

    @GET("api/networth-series")
    suspend fun netWorthSeries(
        @Query("convert_to") convertTo: String,
        @Query("months") months: Int = 12,
        @Header("Cache-Control") cacheControl: String? = null,
    ): NetWorthSeries

    @GET("api/flows")
    suspend fun flows(
        @Query("convert_to") convertTo: String,
        @Header("Cache-Control") cacheControl: String? = null,
    ): Flows

    @GET("api/accounts")
    suspend fun accounts(@Header("Cache-Control") cacheControl: String? = null): AccountsResponse

    @PATCH("api/accounts/{id}")
    suspend fun patchAccount(@Path("id") id: String, @Body patch: JsonObject): Account

    @GET("api/cards")
    suspend fun cards(@Header("Cache-Control") cacheControl: String? = null): CardsResponse

    @GET("api/facets")
    suspend fun facets(@Header("Cache-Control") cacheControl: String? = null): Facets

    @GET("api/statement-freshness")
    suspend fun statementFreshness(@Header("Cache-Control") cacheControl: String? = null): StatementFreshness

    @GET("api/integrity")
    suspend fun integrity(@Header("Cache-Control") cacheControl: String? = null): IntegrityReport

    @GET("api/installments")
    suspend fun installments(
        @Query("active_only") activeOnly: Boolean,
        @Header("Cache-Control") cacheControl: String? = null,
    ): InstallmentsResponse

    @GET("api/installments/{id}")
    suspend fun installment(
        @Path("id") id: String,
        @Header("Cache-Control") cacheControl: String? = null,
    ): InstallmentPlan

    // --- Editing --------------------------------------------------------------

    /** A JSON object, so a field can be cleared with an explicit null. */
    @PATCH("api/transactions/{id}")
    suspend fun patchTransaction(@Path("id") id: String, @Body patch: JsonObject): Txn

    @POST("api/transactions/{id}/tags")
    suspend fun addTag(@Path("id") id: String, @Body tag: TagRequest): Txn

    @DELETE("api/transactions/{id}/tags/{tag}")
    suspend fun removeTag(@Path("id") id: String, @Path("tag") tag: String): Txn

    @GET("api/transactions/{id}/category-suggestion")
    suspend fun categorySuggestion(@Path("id") id: String): SuggestionResponse

    @GET("api/review/{queue}")
    suspend fun reviewQueue(
        @Path("queue") queue: String,
        @Header("Cache-Control") cacheControl: String? = null,
    ): ReviewQueueResponse

    @POST("api/review/{queue}/{id}")
    suspend fun resolve(
        @Path("queue") queue: String,
        @Path("id") id: String,
        @Body request: ResolveRequest,
    ): JsonObject

    // --- Investments ----------------------------------------------------------

    @GET("api/investments")
    suspend fun investments(@Header("Cache-Control") cacheControl: String? = null): InvestmentsResponse

    @GET("api/investments/{id}")
    suspend fun investment(
        @Path("id") id: String,
        @Header("Cache-Control") cacheControl: String? = null,
    ): InvestmentDetail

    @GET("api/investments/history")
    suspend fun investmentHistory(
        @Query("scheme") scheme: String?,
        @Query("account_id") accountId: String?,
        @Header("Cache-Control") cacheControl: String? = null,
    ): InvestmentHistory

    @GET("api/investments/activities")
    suspend fun investmentActivities(
        @Query("account_id") accountId: String?,
        @Header("Cache-Control") cacheControl: String? = null,
    ): ActivitiesResponse

    @Multipart
    @POST("api/investments/imports/preview")
    suspend fun previewMpfBundle(@Part files: List<MultipartBody.Part>): MpfBundlePreview

    @Multipart
    @POST("api/investments/imports/confirm")
    suspend fun confirmMpfBundle(
        @Part files: List<MultipartBody.Part>,
        @Part("expected_bundle_sha256") expectedBundleSha256: RequestBody,
    ): MpfImportResult

    // --- Imports and maintenance ---------------------------------------------

    @GET("api/imports/capabilities")
    suspend fun importCapabilities(@Header("Cache-Control") cacheControl: String? = null): ImportCapabilities

    @GET("api/imports/history")
    suspend fun importHistory(@Header("Cache-Control") cacheControl: String? = null): ImportHistory

    @Multipart
    @POST("api/imports/preview")
    suspend fun previewImport(
        @Part file: MultipartBody.Part,
        @Part("review") review: RequestBody?,
    ): StagePreview

    @Multipart
    @POST("api/imports/confirm")
    suspend fun confirmImport(
        @Part file: MultipartBody.Part,
        @Part("expected_sha256") expectedSha256: RequestBody,
    ): ConfirmResponse

    @POST("api/imports/{id}/review")
    suspend fun reviewImport(@Path("id") statementFileId: String, @Body empty: JsonObject = JsonObject(emptyMap())): ImportReview

    @GET("api/imports/{id}/review")
    suspend fun lastImportReview(@Path("id") statementFileId: String): ImportReviewResponse

    @POST("api/reconcile")
    suspend fun reconcile(@Body empty: JsonObject = JsonObject(emptyMap())): JsonObject

    @POST("api/reattribute")
    suspend fun reattribute(@Body empty: JsonObject = JsonObject(emptyMap())): JsonObject

    @POST("api/fx/harvest")
    suspend fun harvestFx(@Body empty: JsonObject = JsonObject(emptyMap())): JsonObject

    // --- Ask ------------------------------------------------------------------

    /** Server-sent events; read line by line with [AskStreamParser]. */
    @Streaming
    @POST("api/query/stream")
    suspend fun askStream(
        @Body request: AskRequest,
        @Header("Accept") accept: String = "text/event-stream",
    ): ResponseBody

    @POST("api/query")
    suspend fun ask(@Body request: AskRequest): QueryResult
}

object CacheControls {
    /** Answer from the HTTP cache however old, or fail with 504; never touch the network. */
    const val CACHE_ONLY = "only-if-cached, max-stale=2592000"

    /** Revalidate with the server even when the cached copy is still fresh. */
    const val NETWORK = "no-cache"
}
