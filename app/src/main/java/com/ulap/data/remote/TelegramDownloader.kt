package com.ulap.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TELEGRAM_FILE_BASE = "https://api.telegram.org/file/"
private const val DOWNLOAD_BUFFER = 8 * 1024

@Singleton
class TelegramDownloader @Inject constructor(
    private val api: TelegramBotApi,
    private val okHttpClient: OkHttpClient,
) {

    suspend fun download(
        token: String,
        fileId: String,
        outputStream: OutputStream,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val fileIds = parseFileIds(fileId)
        if (fileIds.isEmpty()) {
            return@withContext DownloadResult.Error(Exception("Invalid fileId: $fileId"))
        }
        if (fileIds.size == 1) {
            return@withContext downloadSingle(token, fileIds[0], outputStream, onProgress)
        }
        var offset = 0L
        for (id in fileIds) {
            var lastChunkDown = 0L
            val result = downloadSingle(token, id, outputStream) { chunkDown, _ ->
                lastChunkDown = chunkDown
                onProgress(offset + chunkDown, -1L)
            }
            if (result is DownloadResult.Error) return@withContext result
            offset += lastChunkDown
        }
        DownloadResult.Success
    }

    /** Resolves file_id (single or JSON array of chunk ids) to CDN URLs for streaming. Returns empty list on failure. */
    suspend fun resolveStreamUrls(token: String, fileId: String): List<String> = withContext(Dispatchers.IO) {
        val fileIds = parseFileIds(fileId)
        if (fileIds.isEmpty()) return@withContext emptyList()
        val safeToken = sanitizeTokenForPath(token)
        val urls = mutableListOf<String>()
        for (id in fileIds) {
            val fileResponse = api.getFile(safeToken, id)
            if (!fileResponse.ok || fileResponse.result?.filePath == null) return@withContext emptyList()
            urls.add("${TELEGRAM_FILE_BASE}bot$safeToken/${fileResponse.result.filePath}")
        }
        urls
    }

    /** Resolves a single file_id to one CDN URL for streaming. Returns null on failure. */
    suspend fun resolveStreamUrl(token: String, fileId: String): String? {
        val urls = resolveStreamUrls(token, fileId)
        return urls.singleOrNull()
    }

    private fun parseFileIds(fileId: String): List<String> {
        val trimmed = fileId.trim()
        if (!trimmed.startsWith("[")) return listOf(trimmed)
        return try {
            val arr = JSONArray(trimmed)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            listOf(trimmed)
        }
    }

    private suspend fun downloadSingle(
        token: String,
        fileId: String,
        outputStream: OutputStream,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val safeToken = sanitizeTokenForPath(token)
        val fileResponse = api.getFile(safeToken, fileId)
        if (!fileResponse.ok || fileResponse.result?.filePath == null) {
            return@withContext DownloadResult.Error(
                TelegramApiException(fileResponse.errorCode, fileResponse.description)
            )
        }

        val filePath = fileResponse.result.filePath
        val downloadUrl = "${TELEGRAM_FILE_BASE}bot$safeToken/$filePath"
        val totalSize = fileResponse.result.fileSize ?: -1L

        val request = Request.Builder().url(downloadUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext DownloadResult.Error(
                    Exception("Download failed: HTTP ${response.code}")
                )
            }
            val body = response.body ?: return@withContext DownloadResult.Error(
                Exception("Empty response body")
            )
            var downloaded = 0L
            val buf = ByteArray(DOWNLOAD_BUFFER)
            body.byteStream().use { input ->
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    outputStream.write(buf, 0, read)
                    downloaded += read
                    onProgress(downloaded, totalSize)
                }
            }
        }
        DownloadResult.Success
    }
}

sealed class DownloadResult {
    object Success : DownloadResult()
    data class Error(val cause: Throwable) : DownloadResult()
}
