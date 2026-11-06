package page.zepto.finto.feature.investments

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import page.zepto.finto.core.data.ReadPolicy
import page.zepto.finto.core.data.staleWhileRevalidate

/**
 * [staleWhileRevalidate] as results: the cached copy (when there is one), then
 * the network copy or the reason it failed. A failure never ends the flow
 * with an exception, so the caller can keep what it already painted.
 */
internal fun <T> revalidate(refresh: Boolean, read: suspend (ReadPolicy) -> T): Flow<Result<T>> =
    staleWhileRevalidate(refresh, read)
        .map { Result.success(it) }
        .catch { emit(Result.failure(it)) }
