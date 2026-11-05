package page.zepto.finto.core.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A statement shared into Finto from another app (a bank's app, Files, Gmail),
 * waiting for the Import screen to pick it up.
 */
@Singleton
class IncomingFiles @Inject constructor() {
    private val pending = MutableStateFlow<Uri?>(null)
    val file: StateFlow<Uri?> = pending.asStateFlow()

    fun offer(uri: Uri) {
        pending.value = uri
    }

    /** Takes the pending file, so it is handled once. */
    fun take(): Uri? = pending.value.also { pending.value = null }
}
