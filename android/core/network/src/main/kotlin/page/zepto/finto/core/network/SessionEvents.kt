package page.zepto.finto.core.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionEvents @Inject constructor() {
    private val expired = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits when the server rejects the stored session. */
    val sessionExpiry: SharedFlow<Unit> = expired.asSharedFlow()

    fun sessionExpired() {
        expired.tryEmit(Unit)
    }
}
