package page.zepto.finto.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Finto server every request goes to: the one the APK was built for
 * ([DEFAULT_SERVER]), set by the data layer before the first call.
 */
@Singleton
class ServerEndpoint @Inject constructor() {
    @Volatile
    var baseUrl: HttpUrl = DEFAULT_SERVER.toHttpUrlOrNull()!!
        private set

    fun set(url: HttpUrl) {
        baseUrl = url
    }

    companion object {
        /** Set at build time with `-Pfinto.server`; production Finto by default. */
        const val DEFAULT_SERVER: String = BuildConfig.FINTO_SERVER
        const val DEMO_SERVER = "https://demo.finto.zepto.page/"

        /** Placeholder host Retrofit is built with; [ServerRewriteInterceptor] replaces it. */
        const val PLACEHOLDER = "https://finto.invalid/"

        /**
         * Accepts `finto.example.com`, `https://finto.example.com` or a full URL
         * and returns its origin plus path, ending in `/`. HTTPS is assumed.
         */
        fun normalize(input: String): HttpUrl? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            return url.newBuilder().query(null).fragment(null).build()
                .let { if (it.encodedPath.endsWith("/")) it else it.newBuilder().addPathSegment("").build() }
        }
    }
}
