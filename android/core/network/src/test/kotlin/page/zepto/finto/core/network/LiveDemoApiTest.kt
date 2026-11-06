package page.zepto.finto.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import page.zepto.finto.core.model.FintoJson
import page.zepto.finto.core.model.LoginRequest
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The app's real API interface, URL rewriting and models against a live server.
 * Opt-in, so CI never depends on the network:
 *
 *     ./gradlew :core:network:testDebugUnitTest -Pfinto.liveServer=https://demo.finto.zepto.page
 *
 * Only the public demo's published, read-only credentials are used.
 */
class LiveDemoApiTest {
    private val server: String? = System.getProperty("finto.liveServer")

    @Before
    fun requireServer() = assumeTrue("Pass -Pfinto.liveServer to run against a server", server != null)

    private class MemoryCookieJar : CookieJar {
        val cookies = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies.removeAll { old -> cookies.any { it.name == old.name } }
            this.cookies += cookies
        }
        override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
    }

    private fun api(jar: CookieJar): FintoApi {
        val endpoint = ServerEndpoint().apply { set(ServerEndpoint.normalize(server!!)!!) }
        val client = OkHttpClient.Builder().cookieJar(jar).addInterceptor(ServerRewriteInterceptor(endpoint)).build()
        return Retrofit.Builder()
            .baseUrl(ServerEndpoint.PLACEHOLDER)
            .client(client)
            .addConverterFactory(FintoJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FintoApi::class.java)
    }

    @Test
    fun signInReadTheLedgerAndSignOut() = runTest {
        val jar = MemoryCookieJar()
        val api = api(jar)

        val login = api.login(LoginRequest("demo", "finto-demo-2026"))
        assertEquals("demo", login.user.username)
        assertTrue("session cookie stored", jar.cookies.any { it.name == "finto_session" })
        assertEquals("demo", api.me().username)

        val page = api.transactions(
            scalars = mapOf("spendingOnly" to "true"), months = null, accounts = null, cards = null, cardholders = null,
            institutions = null, categories = null, kinds = null, tags = null, detail = null, statements = null,
            limit = 20, offset = 0,
        )
        assertTrue(page.items.isNotEmpty())
        val account = page.items.first().accountId
        val scoped = api.transactions(
            scalars = emptyMap(), months = null, accounts = listOf(account), cards = null, cardholders = null,
            institutions = null, categories = null, kinds = null, tags = null, detail = null, statements = null,
            limit = 20, offset = 0,
        )
        assertTrue("account filter applied", scoped.items.all { it.accountId == account })
        val second = api.transactions(
            scalars = mapOf("spendingOnly" to "true"), months = null, accounts = null, cards = null, cardholders = null,
            institutions = null, categories = null, kinds = null, tags = null, detail = null, statements = null,
            limit = 20, offset = 20,
        )
        assertTrue("offset paging", second.items.none { it.id in page.items.map { item -> item.id } })
        assertEquals(page.items.first().id, api.transaction(page.items.first().id).id)

        val summary = api.summary(
            groupBy = "category", scalars = mapOf("from" to "2025-01-01", "spendingOnly" to "true"), months = null,
            accounts = null, cards = null, cardholders = null, institutions = null, categories = null, kinds = null,
            tags = null, detail = null, statements = null, convertTo = "HKD",
        )
        assertNotNull(summary.ledgerThrough)
        assertTrue(summary.rows.any { it.spendConverted?.ok == true })

        val positions = api.positions("HKD")
        assertEquals("HKD", positions.normalised?.netWorth?.currency)
        assertTrue(api.accounts().accounts.isNotEmpty())
        assertTrue(api.statementFreshness().accounts.isNotEmpty())
        assertTrue(api.importCapabilities().formats.isNotEmpty())

        api.logout()
        val afterLogout = runCatching { api.me() }.exceptionOrNull()
        assertEquals(401, (afterLogout as? HttpException)?.code())
    }

    @Test
    fun wrongPasswordIsA401() = runTest {
        val failure = runCatching { api(MemoryCookieJar()).login(LoginRequest("demo", "not-the-password")) }.exceptionOrNull()
        assertEquals(401, (failure as? HttpException)?.code())
    }
}
