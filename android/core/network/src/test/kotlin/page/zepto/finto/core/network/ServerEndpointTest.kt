package page.zepto.finto.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerEndpointTest {
    @Test
    fun normalizeAcceptsBareHostsAndFullUrls() {
        assertEquals("https://finto.zepto.page/", ServerEndpoint.normalize("finto.zepto.page").toString())
        assertEquals("https://finto.zepto.page/", ServerEndpoint.normalize(" https://finto.zepto.page/ ").toString())
        assertEquals("http://10.0.2.2:8000/", ServerEndpoint.normalize("http://10.0.2.2:8000").toString())
        assertEquals("https://example.com/finto/", ServerEndpoint.normalize("example.com/finto?x=1").toString())
        assertNull(ServerEndpoint.normalize("   "))
    }

    @Test
    fun rewriteKeepsPathAndQuery() {
        val request = "https://finto.invalid/api/transactions?accounts=a&accounts=b".toHttpUrl()
        assertEquals(
            "https://demo.finto.zepto.page/api/transactions?accounts=a&accounts=b",
            ServerRewriteInterceptor.rewrite("https://demo.finto.zepto.page/".toHttpUrl(), request).toString(),
        )
        assertEquals(
            "https://example.com/finto/api/auth/me",
            ServerRewriteInterceptor.rewrite(
                "https://example.com/finto/".toHttpUrl(), "https://finto.invalid/api/auth/me".toHttpUrl(),
            ).toString(),
        )
    }
}
