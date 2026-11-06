package page.zepto.finto.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.ResponseBody
import okio.BufferedSource
import page.zepto.finto.core.model.AskEvent
import page.zepto.finto.core.model.AskStep
import page.zepto.finto.core.model.FintoJson
import page.zepto.finto.core.model.QueryResult
import page.zepto.finto.core.model.TextDelta

/**
 * Reads `POST /api/query/stream`: `event: <kind>` / `data: <json>` blocks
 * separated by blank lines, with `:` comment lines (the server opens with
 * `: open`). Multi-line data is joined; an event whose JSON does not parse is
 * dropped rather than ending the answer.
 */
object AskStreamParser {

    fun events(body: ResponseBody): Flow<AskEvent> = flow {
        body.use { events(it.source()) { event -> emit(event) } }
    }.flowOn(Dispatchers.IO)

    internal suspend fun events(source: BufferedSource, emit: suspend (AskEvent) -> Unit) {
        var kind: String? = null
        val data = StringBuilder()
        suspend fun flush() {
            if (kind != null && data.isNotEmpty()) decode(kind!!, data.toString())?.let { emit(it) }
            kind = null
            data.clear()
        }
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> flush()
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> kind = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        flush()
    }

    internal fun decode(kind: String, json: String): AskEvent? = runCatching {
        when (kind) {
            "tool" -> AskEvent.Step(FintoJson.decodeFromString(AskStep.serializer(), json))
            "data" -> AskEvent.Data(FintoJson.decodeFromString(QueryResult.serializer(), json))
            "text" -> AskEvent.Text(FintoJson.decodeFromString(TextDelta.serializer(), json).delta)
            "discard" -> AskEvent.Discard
            "done" -> AskEvent.Done(FintoJson.decodeFromString(QueryResult.serializer(), json))
            else -> null
        }
    }.getOrNull()
}
