package page.zepto.finto.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import page.zepto.finto.core.data.IoDispatcher
import page.zepto.finto.core.model.ImportCapabilities
import page.zepto.finto.core.model.ConfirmResponse
import page.zepto.finto.core.model.StagePreview
import page.zepto.finto.core.network.FintoApi
import page.zepto.finto.core.network.NetworkCache
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A statement copied into the app's cache, so preview and confirm send the same bytes. */
data class StagedFile(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val path: String,
)

interface ImportRepository {
    suspend fun capabilities(): ImportCapabilities
    suspend fun stage(uri: Uri): StagedFile
    /** [accountId] files rows the statement itself doesn't route; null lets the server decide. */
    suspend fun preview(file: StagedFile, review: Boolean = true, accountId: String? = null): StagePreview
    suspend fun confirm(file: StagedFile, sha256: String, accountId: String? = null): ConfirmResponse
    suspend fun discard(file: StagedFile)
}

@Singleton
internal class NetworkImportRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: FintoApi,
    private val cache: NetworkCache,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ImportRepository {

    override suspend fun capabilities(): ImportCapabilities = api.importCapabilities()

    override suspend fun stage(uri: Uri): StagedFile = withContext(io) {
        val resolver = context.contentResolver
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
        val displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "statement"
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}-${displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        val input = resolver.openInputStream(uri) ?: throw IOException("Can't open that file.")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        if (target.length() > MAX_BYTES) {
            target.delete()
            throw IOException("That file is larger than 64 MB.")
        }
        StagedFile(
            name = displayName,
            sizeBytes = target.length(),
            mimeType = resolver.getType(uri) ?: guessMime(displayName),
            path = target.absolutePath,
        )
    }

    override suspend fun preview(file: StagedFile, review: Boolean, accountId: String?): StagePreview =
        api.previewImport(part(file), if (review) "true".toRequestBody() else null, accountId?.toRequestBody())

    override suspend fun confirm(file: StagedFile, sha256: String, accountId: String?): ConfirmResponse =
        api.confirmImport(part(file), sha256.toRequestBody(), accountId?.toRequestBody()).also { cache.clear() }

    override suspend fun discard(file: StagedFile) {
        withContext(io) { File(file.path).delete() }
    }

    private fun part(file: StagedFile): MultipartBody.Part = MultipartBody.Part.createFormData(
        "file",
        file.name,
        File(file.path).asRequestBody(file.mimeType.toMediaTypeOrNull()),
    )

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "csv" -> "text/csv"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

    private companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
