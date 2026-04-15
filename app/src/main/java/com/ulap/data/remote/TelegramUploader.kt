package com.ulap.data.remote

import com.google.gson.Gson
import com.ulap.di.UploadClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_SINGLE_UPLOAD_SIZE = 20L * 1024 * 1024 // 20MB Bot API getFile() limit for download/streaming
internal const val CHUNK_UPLOAD_SIZE = 19L * 1024 * 1024 // 19MB per chunk (under 20MB getFile limit for streaming)
internal const val FAST_START_CHUNK_SIZE = 512L * 1024 // 512KB first chunk for faster media player start

// Top-level so BackupForegroundService can import it for the notification string.
const val CHUNK_MAX_RETRIES = 5

private const val CHUNK_BACKOFF_BASE_MS = 3_000L
private const val CHUNK_BACKOFF_MAX_MS = 60_000L

// Sentinel value stored in telegramFileId to indicate the item uses the chunk_metadata table.
// Format: "chunked:<totalChunks>" for quick chunk count lookup without a DB query.
const val CHUNKED_FILE_ID_PREFIX = "chunked:"

// 401/403 are definitively permanent (wrong token / bot banned from chat).
// 400 is NOT included — it can mean transient encoding issues; don't permanently
// exclude a user's file on a single 400.
private val PERMANENT_ERROR_CODES = setOf(401, 403)

@Singleton
class TelegramUploader @Inject constructor(
    private val api: TelegramBotApi,                       // default: metadata, single uploads
    @UploadClient private val uploadApi: TelegramBotApi,   // long-timeout: only used in uploadChunked
    private val rateLimiter: TelegramRateLimiter,
) {

    private val gson = Gson()

    suspend fun uploadMedia(
        token: String,
        chatId: String,
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        caption: String? = null,
        onProgress: (bytesUploaded: Long, total: Long) -> Unit = { _, _ -> },
        resumeFromChunk: Int = 0,
        // Called after each successful chunk with full metadata for the chunk_metadata table.
        // Suspend: calls DAO methods from IO dispatcher — safe.
        onChunkUploaded: suspend (
            chunkIndex: Int,
            telegramFileId: String,
            telegramMessageId: Long,
            byteOffset: Long,
            byteLength: Int,
        ) -> Unit = { _, _, _, _, _ -> },
        // non-suspend: only calls _progress.update{} which is a non-suspend StateFlow method.
        onChunkRetry: (attempt: Int) -> Unit = {},
        // Called once before the chunk loop with the computed total chunk count.
        onTotalChunksKnown: (totalChunks: Int) -> Unit = {},
        thumbnailFileId: String? = null,
        thumbnailMessageId: Long? = null,
    ): UploadResult = withContext(Dispatchers.IO) {
        when {
            fileSize <= MAX_SINGLE_UPLOAD_SIZE -> uploadSingle(
                token = token,
                chatId = chatId,
                inputStream = inputStream,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = fileSize,
                caption = caption,
                onProgress = onProgress,
            )
            else -> uploadChunked(
                token = token,
                chatId = chatId,
                inputStream = inputStream,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = fileSize,
                caption = caption,
                onProgress = onProgress,
                resumeFromChunk = resumeFromChunk,
                onChunkUploaded = onChunkUploaded,
                onChunkRetry = onChunkRetry,
                onTotalChunksKnown = onTotalChunksKnown,
                thumbnailFileId = thumbnailFileId,
                thumbnailMessageId = thumbnailMessageId,
            )
        }
    }

    private suspend fun uploadSingle(
        token: String,
        chatId: String,
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        caption: String?,
        onProgress: (bytesUploaded: Long, total: Long) -> Unit,
    ): UploadResult {
        val chatIdBody = chatId.toRequestBody("text/plain".toMediaType())
        val captionBody = caption?.toRequestBody("text/plain".toMediaType())
        val safeToken = sanitizeTokenForPath(token)

        fun makeBody(): MultipartBody.Part {
            val requestBody = StreamProgressRequestBody(
                inputStream = inputStream,
                contentLength = fileSize,
                contentType = mimeType.toMediaType(),
                onProgress = onProgress,
            )
            return MultipartBody.Part.createFormData(name = "document", filename = fileName, body = requestBody)
        }

        val response = rateLimiter.withRateLimit {
            api.sendDocument(safeToken, chatIdBody, makeBody(), captionBody)
        }

        if (!response.ok || response.result == null) {
            if (response.errorCode == 429) {
                val retryAfterMs = (response.parameters?.retryAfter ?: 30) * 1_000L
                throw TelegramRateLimitException(retryAfterMs)
            }
            rateLimiter.recordFailure()
            return UploadResult.Error(
                TelegramApiException(response.errorCode, response.description)
            )
        }

        rateLimiter.recordSuccess()
        val msg = response.result
        val fileId = msg.document?.fileId
            ?: return UploadResult.Error(Exception("No file_id in response"))
        val thumbId = msg.document.thumbnail?.fileId
        return UploadResult.Success(messageId = msg.messageId, fileId = fileId, thumbnailFileId = thumbId)
    }

    private suspend fun uploadChunked(
        token: String,
        chatId: String,
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        caption: String?,
        onProgress: (bytesUploaded: Long, total: Long) -> Unit,
        resumeFromChunk: Int = 0,
        onChunkUploaded: suspend (
            chunkIndex: Int,
            telegramFileId: String,
            telegramMessageId: Long,
            byteOffset: Long,
            byteLength: Int,
        ) -> Unit = { _, _, _, _, _ -> },
        onChunkRetry: (attempt: Int) -> Unit = {},
        onTotalChunksKnown: (totalChunks: Int) -> Unit = {},
        thumbnailFileId: String? = null,
        thumbnailMessageId: Long? = null,
    ): UploadResult = withContext(Dispatchers.IO) {
        val safeToken = sanitizeTokenForPath(token)
        val chatIdBody = chatId.toRequestBody("text/plain".toMediaType())
        var uploadedSoFar = 0L

        val totalChunks = if (fileSize <= FAST_START_CHUNK_SIZE) {
            1
        } else {
            val remaining = fileSize - FAST_START_CHUNK_SIZE
            (1 + (remaining + CHUNK_UPLOAD_SIZE - 1) / CHUNK_UPLOAD_SIZE).toInt()
        }
        onTotalChunksKnown(totalChunks)   // sets SyncProgress.totalChunks before any chunk starts

        // 0-based chunk index, aligned with byteOffset tracking.
        var chunkIndex = resumeFromChunk

        // Skip already-uploaded bytes (resume support).
        // Chunk 0 consumed FAST_START_CHUNK_SIZE bytes; chunks 1..n consumed CHUNK_UPLOAD_SIZE each.
        if (resumeFromChunk > 0) {
            var remaining = FAST_START_CHUNK_SIZE + (resumeFromChunk - 1).toLong() * CHUNK_UPLOAD_SIZE
            while (remaining > 0) {
                val skipped = inputStream.skip(remaining)
                if (skipped <= 0) break
                remaining -= skipped
            }
            // Guard: stream shorter than expected means the file was modified/truncated since last run.
            if (remaining > 0) {
                return@withContext UploadResult.Error(
                    Exception("Resume failed: stream ended before expected offset (file may have been modified)")
                )
            }
            uploadedSoFar = FAST_START_CHUNK_SIZE + (resumeFromChunk - 1).toLong() * CHUNK_UPLOAD_SIZE
        }

        var read: Int
        while (true) {
            val currentChunkSize = if (chunkIndex == 0) FAST_START_CHUNK_SIZE.toInt() else CHUNK_UPLOAD_SIZE.toInt()
            val buf = ByteArray(currentChunkSize)
            read = readFully(inputStream, buf)
            if (read == -1) break
            val byteOffset = uploadedSoFar
            val chunkData = buf.copyOf(read)
            val chunkCaption = "[ulap-chunk] $fileName part ${chunkIndex + 1}/$totalChunks"
            val captionBody = chunkCaption.toRequestBody("text/plain".toMediaType())

            val (fileId, chunkMsgId) = uploadChunkWithRetry(
                safeToken = safeToken,
                chatIdBody = chatIdBody,
                captionBody = captionBody,
                chunkData = chunkData,
                mimeType = mimeType,
                partFileName = "${fileName}.part${chunkIndex + 1}",
                uploadedSoFar = uploadedSoFar,
                fileSize = fileSize,
                onProgress = onProgress,
                onChunkRetry = onChunkRetry,
            )

            uploadedSoFar += read
            onProgress(uploadedSoFar, fileSize)

            // Persist chunk metadata to the chunk_metadata table after each success.
            onChunkUploaded(chunkIndex, fileId, chunkMsgId, byteOffset, read)
            chunkIndex++
        }

        UploadResult.Success(
            messageId = 0L,
            fileId = "$CHUNKED_FILE_ID_PREFIX$chunkIndex",
            thumbnailFileId = thumbnailFileId,
            thumbnailMessageId = thumbnailMessageId,
            chunkMessageIds = null,
        )
    }

    // Uploads a single chunk with per-chunk retry and exponential backoff with jitter.
    // chunkData is a ByteArray — replayable on retry (body reconstructed fresh each attempt).
    // onChunkRetry is called BEFORE the backoff delay — intentional: shows "retrying…"
    // during the sleep rather than after, giving the user immediate feedback.
    // chunkRetryAttempt is set to (attempt + 1) — the upcoming attempt number.
    // Returns Pair(fileId, messageId).
    private suspend fun uploadChunkWithRetry(
        safeToken: String,
        chatIdBody: RequestBody,
        captionBody: RequestBody,
        chunkData: ByteArray,
        mimeType: String,
        partFileName: String,
        uploadedSoFar: Long,
        fileSize: Long,
        onProgress: (Long, Long) -> Unit,
        onChunkRetry: (attempt: Int) -> Unit = {},
    ): Pair<String, Long> {
        var attempt = 0
        // highWaterMark clamps progress so the UI bar never regresses during a retry.
        // ProgressRequestBody callbacks run sequentially on OkHttp's thread; the lambda
        // from attempt N finishes before attempt N+1 starts — safe without synchronization.
        var highWaterMark = uploadedSoFar
        while (true) {
            attempt++
            val body = ProgressRequestBody(
                delegate = chunkData.toRequestBody(mimeType.toMediaType()),
                totalBytes = chunkData.size.toLong(),
                onProgress = { chunkUp, _ ->
                    val reported = maxOf(highWaterMark, uploadedSoFar + chunkUp)
                    highWaterMark = reported
                    onProgress(reported, fileSize)
                },
            )
            val part = MultipartBody.Part.createFormData("document", partFileName, body)
            try {
                val response = rateLimiter.withRateLimit {
                    uploadApi.sendDocument(safeToken, chatIdBody, part, captionBody)
                }
                if (response.ok && response.result?.document != null) {
                    rateLimiter.recordSuccess()
                    return Pair(response.result.document.fileId, response.result.messageId)
                }
                if (response.errorCode in PERMANENT_ERROR_CODES) {
                    rateLimiter.recordFailure()
                    throw TelegramApiException(response.errorCode, response.description, isPermanent = true)
                }
                if (attempt >= CHUNK_MAX_RETRIES) {
                    rateLimiter.recordFailure()
                    throw TelegramApiException(response.errorCode, response.description)
                }
                rateLimiter.recordFailure()
            } catch (e: TelegramApiException) {
                if (e.isPermanent) throw e
                if (attempt >= CHUNK_MAX_RETRIES) throw e
            } catch (e: TelegramRateLimitException) {
                // rateLimiter exhausted its own retries — propagate; don't count as a chunk retry.
                // TelegramRateLimitException < Exception so must be caught before catch(Exception).
                throw e
            } catch (e: Exception) {
                if (attempt >= CHUNK_MAX_RETRIES) throw e
            }
            // Signal retry before sleeping — user sees "retrying…" during the backoff wait.
            onChunkRetry(attempt + 1)
            val base = (CHUNK_BACKOFF_BASE_MS * (1L shl (attempt - 1))).coerceAtMost(CHUNK_BACKOFF_MAX_MS)
            val jitter = (base * 0.2 * (Math.random() * 2 - 1)).toLong()
            delay(base + jitter)
        }
    }

    /**
     * Reads exactly [buf].size bytes from [input], unless EOF is reached first.
     * Returns the total bytes read (may be < buf.size only for the final chunk), or -1 at EOF.
     * This prevents short reads from [StreamingFastStartReader.FastStartInputStream] at segment
     * boundaries from producing undersized chunks that break the byte-length assumptions in
     * [ChunkMetadataLayout] and [PrefetchingVideoDataSource].
     */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n == -1) break
            offset += n
        }
        return if (offset == 0) -1 else offset
    }

    /** Upload a JPEG thumbnail as sendDocument. Returns (fileId, messageId), or null on error. */
    suspend fun uploadThumbnail(
        token: String,
        chatId: String,
        jpeg: ByteArray,
    ): Pair<String, Long>? = withContext(Dispatchers.IO) {
        try {
            val safeToken = sanitizeTokenForPath(token)
            val chatIdBody = chatId.toRequestBody("text/plain".toMediaType())
            val part = MultipartBody.Part.createFormData(
                "document",
                "thumb.jpg",
                jpeg.toRequestBody("image/jpeg".toMediaType()),
            )
            val response = rateLimiter.withRateLimit {
                api.sendDocument(safeToken, chatIdBody, part, null)
            }
            if (response.ok && response.result?.document != null) {
                rateLimiter.recordSuccess()
                Pair(response.result.document.fileId, response.result.messageId)
            } else {
                rateLimiter.recordFailure()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

}

sealed class UploadResult {
    data class Success(
        val messageId: Long,
        val fileId: String,
        val thumbnailFileId: String? = null,
        val thumbnailMessageId: Long? = null,
        val chunkMessageIds: String? = null,
    ) : UploadResult()
    data class Error(val cause: Throwable) : UploadResult()
}
