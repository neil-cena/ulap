package com.ulap.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TELEGRAM_FILE_BASE = "https://api.telegram.org/file/"
private const val DOWNLOAD_BUFFER = 256 * 1024

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
        // Resolve all chunk URLs in parallel. supervisorScope ensures one failure doesn't cancel
        // sibling coroutines; each async catches its own exception and returns null on failure.
        val results = supervisorScope {
            fileIds.map { id ->
                async {
                    try {
                        val fileResponse = api.getFile(safeToken, id)
                        if (!fileResponse.ok || fileResponse.result?.filePath == null) null
                        else "${TELEGRAM_FILE_BASE}bot$safeToken/${fileResponse.result.filePath}"
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
        // If any chunk failed to resolve, return empty to signal an error.
        if (results.any { it == null }) return@withContext emptyList()
        results.filterNotNull()
    }

    /**
     * Resolves a list of file IDs to CDN URLs in batches to avoid Telegram 429 errors at scale.
     * Each batch of [batchSize] is resolved in parallel; a [batchCooldownMs] pause separates batches.
     * Individual failures within a batch are retried once after all batches complete.
     * Returns an ordered list of CDN URLs (null for any that failed after retry).
     */
    suspend fun resolveStreamUrlsBatched(
        token: String,
        fileIds: List<String>,
        batchSize: Int = 20,
        batchCooldownMs: Long = 2_000L,
        onBatchResolved: (resolved: Int, total: Int) -> Unit = { _, _ -> },
    ): List<String?> = withContext(Dispatchers.IO) {
        if (fileIds.isEmpty()) return@withContext emptyList()
        val safeToken = sanitizeTokenForPath(token)
        val results = arrayOfNulls<String>(fileIds.size)
        val failedIndices = mutableListOf<Int>()

        fileIds.chunked(batchSize).forEachIndexed { batchIdx, batch ->
            if (batchIdx > 0) delay(batchCooldownMs)
            val offset = batchIdx * batchSize
            val batchResults = supervisorScope {
                batch.mapIndexed { i, id ->
                    async {
                        try {
                            val fileResponse = api.getFile(safeToken, id)
                            if (fileResponse.ok && fileResponse.result?.filePath != null) {
                                "${TELEGRAM_FILE_BASE}bot$safeToken/${fileResponse.result.filePath}"
                            } else null
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }
            batchResults.forEachIndexed { i, url ->
                val globalIdx = offset + i
                if (url != null) {
                    results[globalIdx] = url
                } else {
                    failedIndices.add(globalIdx)
                }
            }
            onBatchResolved(minOf((batchIdx + 1) * batchSize, fileIds.size), fileIds.size)
        }

        // Single retry pass for failures (transient errors, brief 429s).
        if (failedIndices.isNotEmpty()) {
            delay(batchCooldownMs)
            for (idx in failedIndices) {
                try {
                    val fileResponse = api.getFile(safeToken, fileIds[idx])
                    if (fileResponse.ok && fileResponse.result?.filePath != null) {
                        results[idx] = "${TELEGRAM_FILE_BASE}bot$safeToken/${fileResponse.result.filePath}"
                    }
                } catch (_: Exception) { }
            }
        }

        results.toList()
    }

    /**
     * Downloads a list of pre-resolved CDN URLs sequentially into [outputStream].
     * Use this after [resolveStreamUrls] to avoid a second round of getFile API calls.
     */
    suspend fun downloadFromUrls(
        urls: List<String>,
        outputStream: OutputStream,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        var offset = 0L
        for (url in urls) {
            var lastChunkDown = 0L
            val result = downloadFromUrl(url, outputStream) { chunkDown, _ ->
                lastChunkDown = chunkDown
                onProgress(offset + chunkDown, -1L)
            }
            if (result is DownloadResult.Error) return@withContext result
            offset += lastChunkDown
        }
        DownloadResult.Success
    }

    private suspend fun downloadFromUrl(
        url: String,
        outputStream: OutputStream,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext DownloadResult.Error(
                    Exception("Download failed: HTTP ${response.code}")
                )
            }
            val body = response.body ?: return@withContext DownloadResult.Error(
                Exception("Empty response body")
            )
            val totalSize = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
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
