package page.zepto.finto.core.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import page.zepto.finto.core.model.AccountsResponse
import page.zepto.finto.core.model.ConfirmResponse
import page.zepto.finto.core.model.ImportCapabilities
import page.zepto.finto.core.model.LoginRequest
import page.zepto.finto.core.model.LoginResponse
import page.zepto.finto.core.model.OkResponse
import page.zepto.finto.core.model.PositionsResponse
import page.zepto.finto.core.model.PreferencesPatch
import page.zepto.finto.core.model.StagePreview
import page.zepto.finto.core.model.StatementFreshness
import page.zepto.finto.core.model.SummaryResponse
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.TxnPage
import page.zepto.finto.core.model.User
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The slice of the Finto HTTP API the app reads and writes. Paths are relative:
 * [ServerEndpoint] supplies the host, so one client follows the server the
 * user signed in to.
 */
interface FintoApi {

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("api/auth/logout")
    suspend fun logout(): OkResponse

    @GET("api/auth/me")
    suspend fun me(): User

    @PATCH("api/auth/preferences")
    suspend fun updatePreferences(@Body patch: PreferencesPatch): User

    @GET("api/transactions")
    @Suppress("LongParameterList")
    suspend fun transactions(
        @Query("from") from: String?,
        @Query("to") to: String?,
        @Query("months") months: List<String>?,
        @Query("accounts") accounts: List<String>?,
        @Query("categories") categories: List<String>?,
        @Query("q") query: String?,
        @Query("spendingOnly") spendingOnly: Boolean?,
        @Query("includeTransfers") includeTransfers: Boolean?,
        @Query("maxAmount") maxAmount: Long?,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("sort") sort: String = "date",
        @Query("direction") direction: String = "desc",
    ): TxnPage

    @GET("api/transactions/{id}")
    suspend fun transaction(@Path("id") id: String): Txn

    @GET("api/summary")
    @Suppress("LongParameterList")
    suspend fun summary(
        @Query("group_by") groupBy: String,
        @Query("from") from: String?,
        @Query("to") to: String?,
        @Query("months") months: List<String>?,
        @Query("accounts") accounts: List<String>?,
        @Query("categories") categories: List<String>?,
        @Query("q") query: String?,
        @Query("spendingOnly") spendingOnly: Boolean?,
        @Query("includeTransfers") includeTransfers: Boolean?,
        @Query("maxAmount") maxAmount: Long?,
        @Query("convert_to") convertTo: String?,
    ): SummaryResponse

    @GET("api/positions")
    suspend fun positions(@Query("convert_to") convertTo: String?): PositionsResponse

    @GET("api/accounts")
    suspend fun accounts(): AccountsResponse

    @GET("api/statement-freshness")
    suspend fun statementFreshness(): StatementFreshness

    @GET("api/imports/capabilities")
    suspend fun importCapabilities(): ImportCapabilities

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
}
