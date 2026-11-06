package page.zepto.finto.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-disk HTTP cache of ledger reads. Cleared after anything changes the
 * ledger and on sign-out, so a cached figure never outlives the data it shows.
 */
@Singleton
class NetworkCache @Inject constructor(private val cache: Cache) {
    suspend fun clear() {
        withContext(Dispatchers.IO) { runCatching { cache.evictAll() } }
    }
}
