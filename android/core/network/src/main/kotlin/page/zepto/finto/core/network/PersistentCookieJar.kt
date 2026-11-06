package page.zepto.finto.core.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import page.zepto.finto.core.model.FintoJson
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the `finto_session` cookie across launches.
 *
 * The session is a long-lived HttpOnly cookie, exactly as in the browser. It is
 * stored in the app's no-backup directory, so it is private to the app, never
 * copied into a cloud backup, and gone when the app is uninstalled.
 */
@Singleton
class PersistentCookieJar @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CookieJar {

    @Serializable
    private data class StoredCookie(val origin: String, val header: String)

    private val file: File get() = File(context.noBackupFilesDir, "finto-cookies.json")
    private val lock = Any()
    private var cookies: MutableList<Cookie>? = null

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(lock) {
        val current = loaded()
        for (cookie in cookies) {
            current.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) current += cookie
        }
        persist(current)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val current = loaded()
        val now = System.currentTimeMillis()
        if (current.removeAll { it.expiresAt <= now }) persist(current)
        current.filter { it.matches(url) }
    }

    fun hasCookieFor(url: HttpUrl): Boolean = loadForRequest(url).isNotEmpty()

    fun clear() = synchronized(lock) {
        cookies = mutableListOf()
        file.delete()
        Unit
    }

    private fun loaded(): MutableList<Cookie> =
        cookies ?: read().also { cookies = it }

    private fun read(): MutableList<Cookie> = runCatching {
        if (!file.exists()) return mutableListOf()
        FintoJson.decodeFromString(ListSerializer(StoredCookie.serializer()), file.readText())
            .mapNotNull { stored ->
                stored.origin.toHttpUrlOrNull()?.let { Cookie.parse(it, stored.header) }
            }
            .toMutableList()
    }.getOrElse { mutableListOf() }

    private fun persist(current: List<Cookie>) {
        val stored = current.filter { it.persistent }.map { cookie ->
            StoredCookie(origin = "https://${cookie.domain}/", header = cookie.toString())
        }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(FintoJson.encodeToString(ListSerializer(StoredCookie.serializer()), stored))
            tmp.renameTo(file)
        }
    }
}
