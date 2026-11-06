package page.zepto.finto.core.network

import android.os.Build
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Points each request at the server the user signed in to. */
internal class ServerRewriteInterceptor(private val endpoint: ServerEndpoint) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        return chain.proceed(original.newBuilder().url(rewrite(endpoint.baseUrl, original.url)).build())
    }

    companion object {
        /** `https://host/prefix/` + `/api/summary?x=1` → `https://host/prefix/api/summary?x=1`. */
        fun rewrite(base: HttpUrl, request: HttpUrl): HttpUrl = base.newBuilder()
            .encodedPath(base.encodedPath.removeSuffix("/") + request.encodedPath)
            .encodedQuery(request.encodedQuery)
            .build()
    }
}

/** Reports a lapsed or revoked session so the app can return to sign-in. */
internal class UnauthorizedInterceptor(private val events: SessionEvents) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 401 && !chain.request().url.encodedPath.endsWith("/auth/login")) {
            events.sessionExpired()
        }
        return response
    }
}

/** The server records the agent against each session, so name the app plainly. */
internal class UserAgentInterceptor : Interceptor {
    private val agent = "Finto-Android (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build())
}
