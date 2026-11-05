package page.zepto.finto.core.network.di

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import page.zepto.finto.core.model.FintoJson
import page.zepto.finto.core.network.FintoApi
import page.zepto.finto.core.network.PersistentCookieJar
import page.zepto.finto.core.network.ServerEndpoint
import page.zepto.finto.core.network.ServerRewriteInterceptor
import page.zepto.finto.core.network.SessionEvents
import page.zepto.finto.core.network.UnauthorizedInterceptor
import page.zepto.finto.core.network.UserAgentInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Duration
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun okHttpClient(
        @ApplicationContext context: Context,
        cookieJar: PersistentCookieJar,
        endpoint: ServerEndpoint,
        events: SessionEvents,
    ): OkHttpClient {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(ServerRewriteInterceptor(endpoint))
            .addInterceptor(UserAgentInterceptor())
            .addInterceptor(UnauthorizedInterceptor(events))
            .apply {
                // Request lines and status codes only: never headers (the session
                // cookie) or bodies (the ledger).
                if (debuggable) {
                    addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
                }
            }
            .connectTimeout(Duration.ofSeconds(20))
            // Statement previews ask the analysis model for a verdict, which can
            // take a while on a cold server.
            .readTimeout(Duration.ofMinutes(3))
            .writeTimeout(Duration.ofMinutes(2))
            .build()
    }

    @Provides
    @Singleton
    fun fintoApi(client: OkHttpClient): FintoApi = Retrofit.Builder()
        .baseUrl(ServerEndpoint.PLACEHOLDER)
        .client(client)
        .addConverterFactory(FintoJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(FintoApi::class.java)
}
