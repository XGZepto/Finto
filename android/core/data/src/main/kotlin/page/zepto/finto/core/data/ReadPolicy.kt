package page.zepto.finto.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import page.zepto.finto.core.network.CacheControls

/** Where a read may be answered from. */
enum class ReadPolicy(internal val header: String?) {
    /** The last copy on disk, however old; fails fast when there is none. */
    CacheOnly(CacheControls.CACHE_ONLY),

    /** Ordinary HTTP caching: the server's max-age decides. */
    Default(null),

    /** Always ask the server (pull to refresh). */
    Network(CacheControls.NETWORK),
}

/**
 * Paints the last known copy at once, then the current one.
 *
 * The cached pass is best-effort: a cold cache or an unreadable entry simply
 * skips it. The second pass is the real read, and its failure is the
 * caller's to report, alongside whatever the first pass already showed.
 */
fun <T> staleWhileRevalidate(refresh: Boolean = false, read: suspend (ReadPolicy) -> T): Flow<T> = flow {
    if (!refresh) {
        suspendRunCatching { read(ReadPolicy.CacheOnly) }.getOrNull()?.let { emit(it) }
    }
    emit(read(if (refresh) ReadPolicy.Network else ReadPolicy.Default))
}.distinctUntilChanged()
