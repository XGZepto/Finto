package page.zepto.finto.core.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import page.zepto.finto.core.model.FintoJson
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Turns a failed call into a sentence a person can act on. */
object ApiErrors {

    fun message(error: Throwable): String = when (error) {
        is HttpException -> serverDetail(error) ?: statusMessage(error.code())
        is IOException -> "Can't reach the server. Check your connection and try again."
        else -> error.message ?: "Something went wrong."
    }

    private fun serverDetail(error: HttpException): String? = runCatching {
        val body = error.response()?.errorBody()?.string().orEmpty()
        val detail = FintoJson.parseToJsonElement(body).jsonObject["detail"]
        (detail as? JsonPrimitive)?.content?.replaceFirstChar { it.uppercase() }
    }.getOrNull()

    private fun statusMessage(code: Int) = when (code) {
        401 -> "Your session has ended. Sign in again."
        403 -> "This account can't do that."
        404 -> "Not found."
        413 -> "That file is too large."
        in 500..599 -> "The server had a problem ($code). Try again shortly."
        else -> "Request failed ($code)."
    }
}

/** [runCatching] that never swallows coroutine cancellation. */
inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    Result.failure(error)
}
