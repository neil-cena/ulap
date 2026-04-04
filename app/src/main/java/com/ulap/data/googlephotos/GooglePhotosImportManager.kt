package com.ulap.data.googlephotos

import android.util.Log
import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramMessage
import com.ulap.data.remote.TelegramRateLimiter
import com.ulap.data.remote.largestPhotoFileId
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

/** Telegram Bot API: max document ~50 MiB; use 49 MiB payload so multipart boundaries/caption stay under the limit. */
internal const val GOOGLE_PHOTOS_VIDEO_CHUNK_BYTES = 51_380_224 // 49 * 1024 * 1024

data class GooglePhotosImportStats(
    val itemsListed: Int,
    val imagesImported: Int,
    val videosImported: Int,
)

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
    private val googlePhotosApi: GooglePhotosApi,
    private val telegramBotApi: TelegramBotApi,
    @UploadClient private val uploadTelegramBotApi: TelegramBotApi,
    private val mediaItemDao: MediaItemDao,
    private val chunkMetadataDao: ChunkMetadataDao,
    private val rateLimiter: TelegramRateLimiter,
    private val credentialRepository: CredentialRepository,
) {

    suspend fun importGooglePhotosLibrary(
        onProgress: (processed: Int, imported: Int) -> Unit = { _, _ -> },
    ): Result<GooglePhotosImportStats> = withContext(Dispatchers.IO) {
        val botToken = credentialRepository.getBotToken()
            ?: return@withContext Result.failure(IllegalStateException("Telegram bot not configured"))
        val chatId = credentialRepository.getChatId()
            ?: return@withContext Result.failure(IllegalStateException("Telegram chat not configured"))
        val safeToken = sanitizeTokenForPath(botToken)
        val chatIdBody = chatId.toRequestBody("text/plain".toMediaType())

        var pageToken: String? = null
        var itemsListed = 0
        var imagesImported = 0
        var videosImported = 0
        var processed = 0

        do {
            val page = googlePhotosApi.listMediaItems(pageToken = pageToken)
            val items = page.mediaItems.orEmpty()
            itemsListed += items.size
            pageToken = page.nextPageToken

            for (item in items) {
                processed++
                when {
                    item.mimeType.startsWith("video/") -> {
                        val result = importVideoItem(item, safeToken, chatIdBody)
                        if (result.isSuccess) videosImported++
                    }
                    !item.mimeType.startsWith("image/") -> {
                        Log.d(TAG, "skip non-image mime=${item.mimeType} id=${item.id}")
                    }
                    else -> {
                        val result = importImageItem(item, safeToken, chatIdBody)
                        if (result.isSuccess) imagesImported++
                    }
                }
                onProgress(processed, imagesImported + videosImported)
            }
        } while (pageToken != null)

        Result.success(
            GooglePhotosImportStats(
                itemsListed = itemsListed,
                imagesImported = imagesImported,
                videosImported = videosImported,
            ),
        )
    }

    /**
     * Imports a single library item (image via URL relay, video via in-memory chunking).
     * Used by [com.ulap.sync.GooglePhotosImportWorker]; skips non-image/non-video MIME types without failure.
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

    suspend fun importSingleGooglePhotoItemForTest(
        item: GooglePhotosMediaItem,
        botToken: String,
        chatId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!item.mimeType.startsWith("image/")) {
            return@withContext Result.failure(IllegalArgumentException("not an image"))
        }
        val safeToken = sanitizeTokenForPath(botToken)
        val chatIdBody = chatId.toRequestBody("text/plain".toMediaType())
        importImageItem(item, safeToken, chatIdBody)
    }

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
        val photoUrl = GooglePhotosUrls.fullResolutionImageUrl(baseUrl)
        val photoBody = photoUrl.toRequestBody("text/plain".toMediaType())
        val response = rateLimiter.withRateLimit {
            telegramBotApi.sendPhotoFromUrl(
                token = safeBotToken,
                chatId = chatIdBody,
                photoUrl = photoBody,
                caption = null,
            )
        }
        if (!response.ok || response.result == null) {
            return Result.failure(
                IllegalStateException(response.description ?: "sendPhoto failed"),
            )
        }
        val message: TelegramMessage = response.result
        val fileId = message.largestPhotoFileId()
            ?: return Result.failure(IllegalStateException("no file_id in Telegram response"))
        val entity = GooglePhotosImportEntityFactory.cloudEntityFromGooglePhoto(
            item = item,
            telegramFileId = fileId,
            messageId = message.messageId,
            remoteThumbnailUrl = GooglePhotosUrls.remoteThumbnailImage(baseUrl),
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
        val videoUrl = GooglePhotosUrls.downloadVideoUrl(baseUrl)
        val response = googlePhotosApi.streamMedia(videoUrl)
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
                return importVideoFromStream(item, safeBotToken, chatIdBody, input, baseUrl)
            }
        }
    }

    private suspend fun importVideoFromStream(
        item: GooglePhotosMediaItem,
        safeBotToken: String,
        chatIdBody: RequestBody,
        input: InputStream,
        thumbnailBaseUrl: String,
    ): Result<Unit> {
        val baseName = item.filename ?: item.id
        val totalChunks = mutableListOf<UploadedVideoChunk>()
        var totalBytes = 0L
        var chunkIndex = 0

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
            )
            toUpload.fill(0)

            val (fileId, messageId) = uploadResult
                ?: return Result.failure(IllegalStateException("sendDocument failed for chunk $chunkIndex"))

            totalChunks.add(
                UploadedVideoChunk(
                    chunkIndex = chunkIndex,
                    fileId = fileId,
                    messageId = messageId,
                    byteOffset = totalBytes,
                    byteLength = read,
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
            remoteThumbnailUrl = GooglePhotosUrls.remoteThumbnailVideo(thumbnailBaseUrl),
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
    ): Pair<String, Long>? {
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
            )
        }
        if (!response.ok || response.result?.document == null) {
            rateLimiter.recordFailure()
            return null
        }
        return Pair(response.result.document.fileId, response.result.messageId)
    }

    private data class UploadedVideoChunk(
        val chunkIndex: Int,
        val fileId: String,
        val messageId: Long,
        val byteOffset: Long,
        val byteLength: Int,
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
