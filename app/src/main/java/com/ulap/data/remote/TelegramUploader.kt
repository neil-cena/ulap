package com.ulap.data.remote // sendVideo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_SINGLE_UPLOAD_SIZE = 50L * 1024 * 1024
private const val CHUNK_UPLOAD_SIZE = 19L * 1024 * 1024
const val MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024

@Singleton
class TelegramUploader @Inject constructor(
    private val api: TelegramBotApi,
    private val rateLimiter: TelegramRateLimiter,
) {

    suspend fun uploadMedia(
        token: String,
        chatId: String,
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        caption: String? = null,
        onProgress: (bytesUploaded: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadResult = withContext(Dispatchers.IO) {
        if (fileSize > MAX_FILE_SIZE) {
            return@withContext UploadResult.FileTooLarge(fileSize)
        }
        when {
            fileSize <= MAX_SINGLE_UPLOAD_SIZE -> uploadSingle(
                token, chatId, inputStream, fileName, mimeType, fileSize, caption, onProgress,
            )
            else -> uploadChunked(
                token, chatId, inputStream, fileName, mimeType, fileSize, caption, onProgress,
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

        // All images are sent via sendPhoto regardless of MIME type
        val isPhoto = mimeType.startsWith("image/")
        val isVideo = mimeType.startsWith("video/")

        val bytes: ByteArray? = if (isPhoto) inputStream.readBytes() else null

        fun makeBody(name: String): MultipartBody.Part {
            val requestBody = if (bytes != null) {
                ProgressRequestBody(
                    delegate = bytes.toRequestBody(mimeType.toMediaType()),
                    totalBytes = bytes.size.toLong(),
                    onProgress = { uploaded, total -> onProgress(uploaded, total) },
                )
            } else {
                StreamProgressRequestBody(
                    inputStream = inputStream,
                    contentLength = fileSize,
                    contentType = mimeType.toMediaType(),
                    onProgress = onProgress,
                )
            }
            return MultipartBody.Part.createFormData(name, fileName, requestBody)
        }

        val response = rateLimiter.withRateLimit {
            when {
                isPhoto -> api.sendPhoto(safeToken, chatIdBody, makeBody("photo"), captionBody)
                isVideo -> api.sendVideo(safeToken, chatIdBody, makeBody("video"), captionBody)
                else -> api.sendDocument(safeToken, chatIdBody, makeBody("document"), captionBody)
            }
        }

        if (!response.ok || response.result == null) {
            if (response.errorCode == 429) {
                val retryAfterMs = (response.parameters?.retryAfter ?: 30) * 1_000L
                throw TelegramRateLimitException(retryAfterMs)
            }
            rateLimiter.recordFailure()
            return UploadResult.Error(TelegramApiException(response.errorCode, response.description))
        }

        rateLimiter.recordSuccess()
        val msg = response.result
        val fileId = msg.document?.fileId ?: msg.video?.fileId
            ?: msg.photo?.maxByOrNull { it.fileSize ?: 0 }?.fileId
            ?: return UploadResult.Error(Exception("No file_id in response"))
        val thumbId = when {
            msg.photo != null -> msg.photo.minByOrNull { it.fileSize ?: Long.MAX_VALUE }?.fileId
            msg.video != null -> msg.video.thumbnail?.fileId
            msg.document != null -> msg.document.thumbnail?.fileId
            else -> null
        }
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
    ): UploadResult = withContext(Dispatchers.IO) {
        val safeToken = sanitizeTokenForPath(token)
        val chatIdBody = chatId.toRequestBody("text/plain".toMediaType())
        val fileIds = mutableListOf<String>()
        var firstMessageId: Long? = null
        var uploadedSoFar = 0L
        var partIndex = 0
        val totalChunks = ((fileSize + CHUNK_UPLOAD_SIZE - 1) / CHUNK_UPLOAD_SIZE).toInt()

        val buf = ByteArray(CHUNK_UPLOAD_SIZE.toInt())
        var read: Int
        while (inputStream.read(buf).also { read = it } != -1) {
            partIndex++
            val chunkData = buf.copyOf(read)
            val chunkCaption = "[ulap-chunk] $fileName part $partIndex/$totalChunks"
            val chunkProgressBody = ProgressRequestBody(
                delegate = chunkData.toRequestBody(mimeType.toMediaType()),
                totalBytes = read.toLong(),
                onProgress = { chunkUp, _ -> onProgress(uploadedSoFar + chunkUp, fileSize) },
            )
            val part = MultipartBody.Part.createFormData(
                "document", "${fileName}.part$partIndex", chunkProgressBody,
            )
            val captionBody = chunkCaption.toRequestBody("text/plain".toMediaType())
            val response = rateLimiter.withRateLimit {
                api.sendDocument(safeToken, chatIdBody, part, captionBody)
            }
            if (!response.ok || response.result == null) {
                if (response.errorCode == 429) {
                    val retryAfterMs = (response.parameters?.retryAfter ?: 30) * 1_000L
                    throw TelegramRateLimitException(retryAfterMs)
                }
                rateLimiter.recordFailure()
                return@withContext UploadResult.Error(
                    TelegramApiException(response.errorCode, response.description)
                )
            }
            rateLimiter.recordSuccess()
            val msg = response.result
            val fileId = msg.document?.fileId
                ?: return@withContext UploadResult.Error(Exception("No file_id in chunk response"))
            fileIds.add(fileId)
            if (firstMessageId == null) firstMessageId = msg.messageId
            uploadedSoFar += read
            onProgress(uploadedSoFar, fileSize)
        }
        val fileIdJson = fileIds.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
        UploadResult.Success(messageId = firstMessageId ?: 0L, fileId = fileIdJson, thumbnailFileId = null)
    }
}

sealed class UploadResult {
    data class Success(val messageId: Long, val fileId: String, val thumbnailFileId: String? = null) : UploadResult()
    data class Error(val cause: Throwable) : UploadResult()
    data class FileTooLarge(val actualSize: Long) : UploadResult()
}
