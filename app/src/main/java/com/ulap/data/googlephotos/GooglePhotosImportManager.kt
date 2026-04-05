package com.ulap.data.googlephotos

import android.util.Log
import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
import com.ulap.data.remote.CHUNK_UPLOAD_SIZE
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramRateLimiter
import com.ulap.data.remote.sanitizeTokenForPath
import com.ulap.di.UploadClient
import com.ulap.domain.repository.CredentialRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GooglePhotosImport"

/** Telegram Bot API: max chunk size is capped at 19 MB to stay under the 20 MB getFile() streaming limit. */
internal val GOOGLE_PHOTOS_VIDEO_CHUNK_BYTES = CHUNK_UPLOAD_SIZE.toInt() // 19 * 1024 * 1024

/** Outcome of [GooglePhotosImportManager.importGooglePhotosMediaItem] for worker progress accounting. */
enum class GooglePhotosImportItemStatus {
    /** Uploaded to Telegram and persisted. */
    UPLOADED,

    /** Skipped: a row with the same display file name already exists locally or from a prior backup. */
    SKIPPED_DUPLICATE,

    /** Skipped: MIME type is not handled as image or video. */
    SKIPPED_UNSUPPORTED,
}

@Singleton
class GooglePhotosImportManager @Inject constructor(
    private val pickerApi: GooglePhotosPickerApi,
    @UploadClient private val uploadTelegramBotApi: TelegramBotApi,
    private val mediaItemDao: MediaItemDao,
    private val chunkMetadataDao: ChunkMetadataDao,
    private val rateLimiter: TelegramRateLimiter,
    private val credentialRepository: CredentialRepository,
) {

    /**
     * Imports a single media item (image via authenticated download, video via in-memory chunking).
     * Used by [com.ulap.sync.GooglePhotosImportWorker]; skips non-image/non-video MIME types.
     */
    suspend fun importGooglePhotosMediaItem(item: GooglePhotosMediaItem): Result<GooglePhotosImportItemStatus> =
        withContext(Dispatchers.IO) {
            val fileName = item.filename?.takeIf { it.isNotBlank() } ?: item.id
            val (w, h) = item.mediaMetadata.pixelDimensions()
            val existingCount = mediaItemDao.countItemsMatchingImportFingerprint(
                fileName = fileName,
                mimeType = item.mimeType,
                widthPx = w,
                heightPx = h,
            )
            if (existingCount > 0) {
                Log.d(
                    TAG,
                    "skip duplicate fingerprint fileName=$fileName mime=${item.mimeType} dims=${w}x${h} id=${item.id}",
                )
                return@withContext Result.success(GooglePhotosImportItemStatus.SKIPPED_DUPLICATE)
            }
            val botToken = credentialRepository.getBotToken()
                ?: return@withContext Result.failure(IllegalStateException("Telegram bot not configured"))
            val chatId = credentialRepository.getChatId()
                ?: return@withContext Result.failure(IllegalStateException("Telegram chat not configured"))
            val safeToken = sanitizeTokenForPath(botToken)
            val chatIdBody = chatId.toRequestBody("text/plain".toMediaType())
            when {
                item.mimeType.startsWith("video/") ->
                    importVideoItem(item, safeToken, chatIdBody).map { GooglePhotosImportItemStatus.UPLOADED }
                item.mimeType.startsWith("image/") ->
                    importImageItem(item, safeToken, chatIdBody).map { GooglePhotosImportItemStatus.UPLOADED }
                else -> {
                    Log.d(TAG, "skip non-media mime=${item.mimeType} id=${item.id}")
                    Result.success(GooglePhotosImportItemStatus.SKIPPED_UNSUPPORTED)
                }
            }
        }

    /**
     * Downloads the image bytes from the authenticated Picker API base URL and uploads to
     * Telegram as a document. Using sendDocument preserves original quality and avoids
     * Telegram's 10 MB sendPhoto limit.
     */
    private suspend fun importImageItem(
        item: GooglePhotosMediaItem,
        safeBotToken: String,
        chatIdBody: RequestBody,
    ): Result<Unit> {
        if (item.baseUrl.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("Missing baseUrl (item likely still processing or unsupported on Google servers)"),
            )
        }
        val baseUrl = item.baseUrl!!
        val downloadUrl = GooglePhotosUrls.fullResolutionImageUrl(baseUrl)
        val streamResponse = pickerApi.streamMedia(downloadUrl)
        if (!streamResponse.isSuccessful) {
            streamResponse.errorBody()?.close()
            return Result.failure(
                IllegalStateException("Google stream failed: HTTP ${streamResponse.code()}"),
            )
        }
        val body = streamResponse.body()
            ?: return Result.failure(IllegalStateException("empty stream body from Google Photos"))
        val bytes = body.use { it.bytes() }
        val fileName = item.filename?.takeIf { it.isNotBlank() } ?: "${item.id}.jpg"
        val mediaType = item.mimeType.toMediaTypeOrNull() ?: "image/jpeg".toMediaType()
        val part = MultipartBody.Part.createFormData("document", fileName, bytes.toRequestBody(mediaType))
        val response = rateLimiter.withRateLimit {
            uploadTelegramBotApi.sendDocument(
                token = safeBotToken,
                chatId = chatIdBody,
                document = part,
                caption = null,
            )
        }
        if (!response.ok || response.result?.document == null) {
            if (!response.ok) rateLimiter.recordFailure()
            return Result.failure(
                IllegalStateException(response.description ?: "sendDocument failed for image"),
            )
        }
        val message = response.result
        val entity = GooglePhotosImportEntityFactory.cloudEntityFromGooglePhoto(
            item = item,
            telegramFileId = message.document.fileId,
            messageId = message.messageId,
            thumbnailFileId = message.document.thumbnail?.fileId,
        )
        mediaItemDao.upsert(entity)
        return Result.success(Unit)
    }

    private suspend fun importVideoItem(
        item: GooglePhotosMediaItem,
        safeBotToken: String,
        chatIdBody: RequestBody,
    ): Result<Unit> {
        if (item.baseUrl.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("Missing baseUrl (item likely still processing or unsupported on Google servers)"),
            )
        }
        val baseUrl = item.baseUrl!!

        // Fetch poster frame while the baseUrl is still valid; used as sendDocument thumbnail.
        val posterFrameBytes = runCatching {
            val posterUrl = GooglePhotosUrls.remoteThumbnailVideo(baseUrl)
            val resp = pickerApi.streamMedia(posterUrl)
            if (resp.isSuccessful) resp.body()?.use { it.bytes() } else { resp.errorBody()?.close(); null }
        }.getOrNull()

        val videoUrl = GooglePhotosUrls.downloadVideoUrl(baseUrl)
        val response = pickerApi.streamMedia(videoUrl)
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return Result.failure(
                IllegalStateException("Google stream failed: HTTP ${response.code()}"),
            )
        }
        val body = response.body()
            ?: return Result.failure(IllegalStateException("empty stream body"))
        body.use { rb ->
            rb.byteStream().use { input ->
                return importVideoFromStream(item, safeBotToken, chatIdBody, input, posterFrameBytes)
            }
        }
    }

    private suspend fun importVideoFromStream(
        item: GooglePhotosMediaItem,
        safeBotToken: String,
        chatIdBody: RequestBody,
        input: InputStream,
        posterFrameBytes: ByteArray? = null,
    ): Result<Unit> {
        val baseName = item.filename ?: item.id
        val totalChunks = mutableListOf<UploadedVideoChunk>()
        var totalBytes = 0L
        var chunkIndex = 0

        val thumbnailPart = posterFrameBytes?.let { bytes ->
            MultipartBody.Part.createFormData(
                "thumbnail", "thumb.jpg", bytes.toRequestBody("image/jpeg".toMediaType()),
            )
        }

        while (true) {
            var buffer: ByteArray? = ByteArray(GOOGLE_PHOTOS_VIDEO_CHUNK_BYTES)
            val read = readFully(input, buffer!!)
            if (read == -1) break

            val toUpload = if (read == buffer.size) {
                val b = buffer
                buffer = null
                b
            } else {
                val slice = buffer.copyOf(read)
                buffer = null
                slice
            }

            val fileName = "$baseName.part${chunkIndex + 1}"
            val caption = "[gphoto-chunk] $baseName part ${chunkIndex + 1}"
            val uploadResult = uploadVideoChunkDocument(
                safeBotToken = safeBotToken,
                chatIdBody = chatIdBody,
                chunkData = toUpload,
                fileName = fileName,
                mimeType = item.mimeType,
                caption = caption,
                thumbnail = if (chunkIndex == 0) thumbnailPart else null,
            )
            toUpload.fill(0)

            val uploaded = uploadResult
                ?: return Result.failure(IllegalStateException("sendDocument failed for chunk $chunkIndex"))

            totalChunks.add(
                UploadedVideoChunk(
                    chunkIndex = chunkIndex,
                    fileId = uploaded.fileId,
                    messageId = uploaded.messageId,
                    byteOffset = totalBytes,
                    byteLength = read,
                    thumbnailFileId = uploaded.thumbnailFileId,
                ),
            )
            totalBytes += read
            chunkIndex++
        }

        if (totalChunks.isEmpty()) {
            return Result.failure(IllegalStateException("empty video stream"))
        }

        val lastMsg = totalChunks.last().messageId
        val entity = GooglePhotosImportEntityFactory.cloudVideoEntityChunked(
            item = item,
            totalSizeBytes = totalBytes,
            totalChunks = totalChunks.size,
            lastChunkMessageId = lastMsg,
            thumbnailFileId = totalChunks.first().thumbnailFileId,
        )
        mediaItemDao.upsert(entity)

        for (c in totalChunks) {
            chunkMetadataDao.insertChunk(
                ChunkMetadataEntity(
                    mediaItemId = entity.id,
                    chunkIndex = c.chunkIndex,
                    telegramFileId = c.fileId,
                    telegramMessageId = c.messageId,
                    byteOffset = c.byteOffset,
                    byteLength = c.byteLength,
                    status = ChunkStatus.UPLOADED,
                ),
            )
        }

        return Result.success(Unit)
    }

    private suspend fun uploadVideoChunkDocument(
        safeBotToken: String,
        chatIdBody: RequestBody,
        chunkData: ByteArray,
        fileName: String,
        mimeType: String,
        caption: String,
        thumbnail: MultipartBody.Part? = null,
    ): UploadedVideoChunk? {
        val captionBody = caption.toRequestBody("text/plain".toMediaType())
        val mediaType = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        val body = chunkData.toRequestBody(mediaType)
        val part = MultipartBody.Part.createFormData("document", fileName, body)
        val response = rateLimiter.withRateLimit {
            uploadTelegramBotApi.sendDocument(
                token = safeBotToken,
                chatId = chatIdBody,
                document = part,
                caption = captionBody,
                thumbnail = thumbnail,
            )
        }
        if (!response.ok || response.result?.document == null) {
            rateLimiter.recordFailure()
            return null
        }
        val msg = response.result
        return UploadedVideoChunk(
            chunkIndex = 0,
            fileId = msg.document.fileId,
            messageId = msg.messageId,
            byteOffset = 0,
            byteLength = chunkData.size,
            thumbnailFileId = msg.document.thumbnail?.fileId,
        )
    }

    private data class UploadedVideoChunk(
        val chunkIndex: Int,
        val fileId: String,
        val messageId: Long,
        val byteOffset: Long,
        val byteLength: Int,
        val thumbnailFileId: String? = null,
    )

    /**
     * Reads up to [buf].size bytes into [buf], or returns -1 at EOF before any byte read.
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
}
