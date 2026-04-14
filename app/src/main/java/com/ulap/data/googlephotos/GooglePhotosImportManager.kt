package com.ulap.data.googlephotos

import android.content.Context
import android.util.Log
import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
import com.ulap.data.remote.BotPool
import com.ulap.data.remote.CHUNK_UPLOAD_SIZE
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramRateLimiter
import com.ulap.data.remote.TelegramRateLimitException
import com.ulap.data.remote.TelegramResponse
import com.ulap.data.remote.sanitizeTokenForPath
import com.ulap.di.UploadClient
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Collections
import kotlin.math.min
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GooglePhotosImport"

/** Retries across bots when a bot returns HTTP 429 during upload. */
private const val MAX_UPLOAD_BOT_ATTEMPTS = 8

/** Maximum time (ms) to wait for a single bot cooldown before retrying anyway. */
private const val MAX_COOLDOWN_WAIT_MS = 60_000L

/** Hard cap on concurrent import slots regardless of bot count. */
private const val MAX_IMPORT_CONCURRENCY = 6

/** Minimum backoff (ms) for exponential retry after a 429. */
private const val BACKOFF_BASE_MS = 2_000L

/** Multiplier applied to the backoff on each successive retry attempt. */
private const val BACKOFF_MULTIPLIER = 1.5

/** Maximum backoff (ms) per retry attempt. */
private const val BACKOFF_CAP_MS = 60_000L

/**
 * Batch API-call-to-bot ratio above which the AIMD controller starts at concurrency 1
 * instead of the full bot count, to prevent an immediate 429 storm.
 */
private const val CONSERVATIVE_START_RATIO = 50

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

/** Per-item result returned by [GooglePhotosImportManager.importBatch]. */
data class BatchItemResult(
    val item: GooglePhotosMediaItem,
    val result: Result<GooglePhotosImportItemStatus>,
)

/** Pre-import analysis of a batch's expected API cost, used to tune AIMD starting state. */
data class BatchProfile(
    val totalItems: Int,
    val imageCount: Int,
    val videoCount: Int,
    val estimatedApiCalls: Int,
    val botCount: Int,
    val initialConcurrency: Int,
)

/**
 * AIMD (Additive Increase / Multiplicative Decrease) concurrency controller.
 *
 * Uses a [Channel] as a permit pool: coroutines receive a permit before starting work
 * and send it back when done. On success the controller may add a permit (additive increase);
 * on a 429 it drains permits to halve concurrency (multiplicative decrease).
 *
 * Thread-safe: all mutations go through the atomic [_currentLimit].
 */
class AimdConcurrencyController(
    initialConcurrency: Int,
    private val maxConcurrency: Int,
) {
    private val _currentLimit = AtomicInteger(initialConcurrency.coerceIn(1, maxConcurrency))
    val currentLimit: Int get() = _currentLimit.get()

    private val permits = Channel<Unit>(Channel.UNLIMITED)
    private val successesSinceLastDecrease = AtomicInteger(0)

    init {
        repeat(_currentLimit.get()) { permits.trySend(Unit) }
    }

    suspend fun acquirePermit() { permits.receive() }

    fun releasePermit() { permits.trySend(Unit) }

    /**
     * Called after a successful upload. After every 2 consecutive successes (without a decrease),
     * adds one permit — up to [maxConcurrency].
     */
    fun onSuccess() {
        val count = successesSinceLastDecrease.incrementAndGet()
        if (count >= 2) {
            successesSinceLastDecrease.set(0)
            val newLimit = _currentLimit.updateAndGet { cur -> min(cur + 1, maxConcurrency) }
            // If the limit actually grew, inject a new permit into the pool.
            if (newLimit > _currentLimit.get() - 1) {
                permits.trySend(Unit)
            }
        }
    }

    /**
     * Called when a 429 rate-limit is encountered. Halves the concurrency (floor 1) by
     * draining excess permits from the channel.
     */
    fun onRateLimit() {
        successesSinceLastDecrease.set(0)
        val oldLimit = _currentLimit.get()
        val newLimit = _currentLimit.updateAndGet { cur -> max(cur / 2, 1) }
        val toDrain = oldLimit - newLimit
        repeat(toDrain) { permits.tryReceive() }
    }
}

/** Subdirectory inside [Context.getCacheDir] used for temporary image downloads. */
internal const val GPHOTO_IMPORT_TEMP_DIR = "gphoto_import"

@Singleton
class GooglePhotosImportManager @Inject constructor(
    private val pickerApi: GooglePhotosPickerApi,
    @UploadClient private val uploadTelegramBotApi: TelegramBotApi,
    private val mediaItemDao: MediaItemDao,
    private val chunkMetadataDao: ChunkMetadataDao,
    private val rateLimiter: TelegramRateLimiter,
    private val credentialRepository: CredentialRepository,
    private val botPool: BotPool,
    @ApplicationContext private val appContext: Context,
) {

    private val importTempDir: File
        get() = File(appContext.cacheDir, GPHOTO_IMPORT_TEMP_DIR).also { it.mkdirs() }

    /**
     * Returns the recommended concurrency for [importBatch] based on the number of configured bots.
     * Scales linearly: 1 bot → 1 (no self-contention), capped at [MAX_IMPORT_CONCURRENCY].
     */
    fun recommendedConcurrency(): Int =
        botPool.allBots().size.coerceIn(1, MAX_IMPORT_CONCURRENCY)

    /**
     * Estimates the total Telegram API calls a batch will produce.
     * - Image → 1 call
     * - Video → ceil(estimatedSize / 19MB) calls, defaulting to 3 if size is unknown
     */
    fun estimateApiCalls(items: List<GooglePhotosMediaItem>): Int =
        items.sumOf { item -> estimateItemApiCalls(item) }

    /**
     * Builds a [BatchProfile] that tunes the AIMD controller's starting concurrency.
     *
     * High API-call-to-bot ratio (> [CONSERVATIVE_START_RATIO]) → start at 1 to avoid
     * an immediate 429 storm. Otherwise start at `min(botCount, MAX_IMPORT_CONCURRENCY)`.
     */
    fun profileBatch(items: List<GooglePhotosMediaItem>): BatchProfile {
        val botCount = botPool.allBots().size.coerceAtLeast(1)
        val imageCount = items.count { it.mimeType.startsWith("image/") }
        val videoCount = items.count { it.mimeType.startsWith("video/") }
        val estimatedCalls = estimateApiCalls(items)
        val ratio = estimatedCalls.toDouble() / botCount
        val initialConcurrency = if (ratio > CONSERVATIVE_START_RATIO) 1
            else min(botCount, MAX_IMPORT_CONCURRENCY)
        return BatchProfile(
            totalItems = items.size,
            imageCount = imageCount,
            videoCount = videoCount,
            estimatedApiCalls = estimatedCalls,
            botCount = botCount,
            initialConcurrency = initialConcurrency,
        )
    }

    /**
     * Imports a single media item (image via authenticated download, video via in-memory chunking).
     * Used by [com.ulap.sync.GooglePhotosImportWorker]; skips non-image/non-video MIME types.
     *
     * @param aimd optional AIMD controller — when provided, [onRateLimit][AimdConcurrencyController.onRateLimit]
     *   is called on 429 and [onSuccess][AimdConcurrencyController.onSuccess] on successful upload,
     *   so the batch-level concurrency adapts in real time.
     */
    suspend fun importGooglePhotosMediaItem(
        item: GooglePhotosMediaItem,
        sessionId: String,
        aimd: AimdConcurrencyController? = null,
    ): Result<GooglePhotosImportItemStatus> =
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
            if (credentialRepository.getBotToken() == null) {
                return@withContext Result.failure(IllegalStateException("Telegram bot not configured"))
            }
            val chatId = credentialRepository.getChatId()
                ?: return@withContext Result.failure(IllegalStateException("Telegram chat not configured"))
            val chatIdBody = chatId.toRequestBody("text/plain".toMediaType())
            when {
                item.mimeType.startsWith("video/") ->
                    importVideoItemWithBotRetries(item, chatIdBody, sessionId, aimd).map { GooglePhotosImportItemStatus.UPLOADED }
                item.mimeType.startsWith("image/") ->
                    importImageItemWithBotRetries(item, chatIdBody, sessionId, aimd).map { GooglePhotosImportItemStatus.UPLOADED }
                else -> {
                    Log.d(TAG, "skip non-media mime=${item.mimeType} id=${item.id}")
                    Result.success(GooglePhotosImportItemStatus.SKIPPED_UNSUPPORTED)
                }
            }
        }

    /**
     * Processes [items] with AIMD-controlled concurrency. The controller starts at the
     * [BatchProfile.initialConcurrency] and adapts in real time: additive increase on success,
     * multiplicative decrease (halve) on 429.
     *
     * The `concurrency` parameter is kept for backward compatibility but is only used as
     * a fallback when the caller has not profiled the batch.
     */
    suspend fun importBatch(
        items: List<GooglePhotosMediaItem>,
        sessionId: String,
        concurrency: Int = 3,
        onItemComplete: suspend (item: GooglePhotosMediaItem, result: Result<GooglePhotosImportItemStatus>) -> Unit = { _, _ -> },
    ): List<BatchItemResult> = coroutineScope {
        val profile = profileBatch(items)
        val maxConcurrency = min(profile.botCount * 2, MAX_IMPORT_CONCURRENCY)
        val aimd = AimdConcurrencyController(
            initialConcurrency = profile.initialConcurrency,
            maxConcurrency = maxConcurrency,
        )
        Log.d(TAG, "importBatch: items=${profile.totalItems} est_calls=${profile.estimatedApiCalls} " +
            "bots=${profile.botCount} initial_concurrency=${profile.initialConcurrency} max=$maxConcurrency")

        val results = Collections.synchronizedList(mutableListOf<BatchItemResult>())
        items.map { item ->
            async {
                aimd.acquirePermit()
                try {
                    val result = try {
                        importGooglePhotosMediaItem(item, sessionId, aimd)
                    } catch (e: Throwable) {
                        if (e is OutOfMemoryError) {
                            Log.e(TAG, "OOM importing item=${item.id}; skipping to protect batch", e)
                        }
                        Result.failure(if (e is Exception) e else RuntimeException(e))
                    }
                    val batchResult = BatchItemResult(item, result)
                    results.add(batchResult)
                    onItemComplete(item, result)
                    batchResult
                } finally {
                    aimd.releasePermit()
                }
            }
        }.awaitAll()
        results
    }

    private fun estimateItemApiCalls(item: GooglePhotosMediaItem): Int {
        if (!item.mimeType.startsWith("video/")) return 1
        // Google Photos Picker API doesn't expose file size, so we use a conservative default.
        return 3
    }

    /**
     * Picks a bot via [botPool] on each attempt; on HTTP 429 marks that bot cooldown, notifies the
     * AIMD controller, and applies exponential backoff with jitter before retrying.
     */
    private suspend fun importImageItemWithBotRetries(
        item: GooglePhotosMediaItem,
        chatIdBody: RequestBody,
        sessionId: String,
        aimd: AimdConcurrencyController? = null,
    ): Result<Unit> {
        var lastError: Exception? = null
        repeat(MAX_UPLOAD_BOT_ATTEMPTS) { attempt ->
            val bot = botPool.selectForUpload()
                ?: return Result.failure(IllegalStateException("No Telegram bot available for upload"))
            try {
                val result = importImageItem(item, bot, chatIdBody, sessionId)
                aimd?.onSuccess()
                return result
            } catch (e: TelegramRateLimitException) {
                botPool.markRateLimited(bot.index, e.retryAfterMs)
                aimd?.onRateLimit()
                lastError = e
                exponentialBackoff(attempt, e.retryAfterMs)
            }
        }
        return Result.failure(
            lastError ?: IllegalStateException("Image upload failed after $MAX_UPLOAD_BOT_ATTEMPTS bot attempts"),
        )
    }

    /**
     * Downloads the image bytes from the authenticated Picker API base URL and uploads to
     * Telegram as a document. Using sendDocument preserves original quality and avoids
     * Telegram's 10 MB sendPhoto limit.
     */
    private suspend fun importImageItem(
        item: GooglePhotosMediaItem,
        bot: BotCredential,
        chatIdBody: RequestBody,
        sessionId: String,
    ): Result<Unit> {
        if (item.baseUrl.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("Missing baseUrl (item likely still processing or unsupported on Google servers)"),
            )
        }
        val baseUrl = item.baseUrl!!
        val downloadUrl = GooglePhotosUrls.fullResolutionImageUrl(baseUrl)
        var streamResponse = pickerApi.streamMedia(downloadUrl)
        if (streamResponse.code() in listOf(401, 403)) {
            // baseUrl has expired — re-fetch a fresh one and retry exactly once
            streamResponse.errorBody()?.close()
            val freshItem = runCatching { pickerApi.getMediaItem(item.id, sessionId) }.getOrNull()
            val freshBaseUrl = freshItem?.mediaFile?.baseUrl
            if (freshBaseUrl != null) {
                val freshDownloadUrl = GooglePhotosUrls.fullResolutionImageUrl(freshBaseUrl)
                streamResponse = pickerApi.streamMedia(freshDownloadUrl)
            }
        }
        if (!streamResponse.isSuccessful) {
            streamResponse.errorBody()?.close()
            return Result.failure(
                IllegalStateException("Google stream failed: HTTP ${streamResponse.code()}"),
            )
        }
        val body = streamResponse.body()
            ?: return Result.failure(IllegalStateException("empty stream body from Google Photos"))
        val fileName = item.filename?.takeIf { it.isNotBlank() } ?: "${item.id}.jpg"
        val mediaType = item.mimeType.toMediaTypeOrNull() ?: "image/jpeg".toMediaType()
        val tempFile = File(importTempDir, "${item.id}_${System.currentTimeMillis()}")
        try {
            body.use { rb ->
                tempFile.outputStream().use { out ->
                    rb.byteStream().copyTo(out)
                }
            }
            val part = MultipartBody.Part.createFormData(
                "document", fileName, tempFile.asRequestBody(mediaType),
            )
            val safeBotToken = sanitizeTokenForPath(bot.token)
            val response = rateLimiter.withRateLimit {
                uploadTelegramBotApi.sendDocument(
                    token = safeBotToken,
                    chatId = chatIdBody,
                    document = part,
                    caption = null,
                )
            }
            throwIfTelegramRateLimited(response)
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
                uploadBotIndex = bot.index,
            )
            mediaItemDao.upsert(entity)
            return Result.success(Unit)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Wraps [importVideoItem] with the same AIMD-aware exponential backoff pattern used for images.
     */
    private suspend fun importVideoItemWithBotRetries(
        item: GooglePhotosMediaItem,
        chatIdBody: RequestBody,
        sessionId: String,
        aimd: AimdConcurrencyController? = null,
    ): Result<Unit> {
        var lastError: Exception? = null
        repeat(MAX_UPLOAD_BOT_ATTEMPTS) { attempt ->
            val bot = botPool.selectForUpload()
                ?: return Result.failure(IllegalStateException("No Telegram bot available for upload"))
            val result = importVideoItem(item, bot, chatIdBody, sessionId)
            if (result.isSuccess) {
                aimd?.onSuccess()
                return result
            }
            val err = result.exceptionOrNull()
            if (err is TelegramRateLimitException) {
                aimd?.onRateLimit()
                lastError = err
                exponentialBackoff(attempt, err.retryAfterMs)
            } else {
                return result
            }
        }
        return Result.failure(
            lastError ?: IllegalStateException("Video upload failed after $MAX_UPLOAD_BOT_ATTEMPTS bot attempts"),
        )
    }

    private suspend fun importVideoItem(
        item: GooglePhotosMediaItem,
        bot: BotCredential,
        chatIdBody: RequestBody,
        sessionId: String,
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
        var response = pickerApi.streamMedia(videoUrl)
        if (response.code() in listOf(401, 403)) {
            response.errorBody()?.close()
            val freshItem = runCatching { pickerApi.getMediaItem(item.id, sessionId) }.getOrNull()
            val freshBaseUrl = freshItem?.mediaFile?.baseUrl
            if (freshBaseUrl != null) {
                val freshVideoUrl = GooglePhotosUrls.downloadVideoUrl(freshBaseUrl)
                response = pickerApi.streamMedia(freshVideoUrl)
            }
        }
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return Result.failure(
                IllegalStateException("Google stream failed: HTTP ${response.code()}"),
            )
        }
        val body = response.body()
            ?: return Result.failure(IllegalStateException("empty stream body"))
        val safeBotToken = sanitizeTokenForPath(bot.token)
        return try {
            body.use { rb ->
                rb.byteStream().use { input ->
                    importVideoFromStream(item, bot, safeBotToken, chatIdBody, input, posterFrameBytes)
                }
            }
        } catch (e: TelegramRateLimitException) {
            botPool.markRateLimited(bot.index, e.retryAfterMs)
            Result.failure(e)
        }
    }

    private suspend fun importVideoFromStream(
        item: GooglePhotosMediaItem,
        bot: BotCredential,
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

        val readBuf = ByteArray(65_536)

        while (true) {
            val chunkTempFile = File(importTempDir, "${item.id}_vc${chunkIndex}_${System.nanoTime()}")
            var chunkSize = 0
            try {
                chunkTempFile.outputStream().use { out ->
                    while (chunkSize < GOOGLE_PHOTOS_VIDEO_CHUNK_BYTES) {
                        val remaining = GOOGLE_PHOTOS_VIDEO_CHUNK_BYTES - chunkSize
                        val n = input.read(readBuf, 0, minOf(readBuf.size, remaining))
                        if (n == -1) break
                        out.write(readBuf, 0, n)
                        chunkSize += n
                    }
                }

                if (chunkSize == 0) break

                val fileName = "$baseName.part${chunkIndex + 1}"
                val caption = "[gphoto-chunk] $baseName part ${chunkIndex + 1}"
                val uploadResult = uploadVideoChunkDocument(
                    safeBotToken = safeBotToken,
                    chatIdBody = chatIdBody,
                    chunkFile = chunkTempFile,
                    chunkSize = chunkSize,
                    fileName = fileName,
                    mimeType = item.mimeType,
                    caption = caption,
                    thumbnail = if (chunkIndex == 0) thumbnailPart else null,
                )

                val uploaded = uploadResult
                    ?: return Result.failure(IllegalStateException("sendDocument failed for chunk $chunkIndex"))

                totalChunks.add(
                    UploadedVideoChunk(
                        chunkIndex = chunkIndex,
                        fileId = uploaded.fileId,
                        messageId = uploaded.messageId,
                        byteOffset = totalBytes,
                        byteLength = chunkSize,
                        thumbnailFileId = uploaded.thumbnailFileId,
                    ),
                )
                totalBytes += chunkSize
                chunkIndex++
            } finally {
                chunkTempFile.delete()
            }
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
            uploadBotIndex = bot.index,
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
        chunkFile: File,
        chunkSize: Int,
        fileName: String,
        mimeType: String,
        caption: String,
        thumbnail: MultipartBody.Part? = null,
    ): UploadedVideoChunk? {
        val captionBody = caption.toRequestBody("text/plain".toMediaType())
        val mediaType = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        val body = chunkFile.asRequestBody(mediaType)
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
        throwIfTelegramRateLimited(response)
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
            byteLength = chunkSize,
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
     * Suspends until the first bot's cooldown has expired, capped at [MAX_COOLDOWN_WAIT_MS].
     * Uses [BotPool.minTempCooldownExpiryMs] so we resume as soon as any bot becomes available,
     * rather than waiting for the slowest bot.
     */
    private suspend fun awaitBotCooldown() {
        val expiryMs = botPool.minTempCooldownExpiryMs()
        val waitMs = (expiryMs - System.currentTimeMillis()).coerceIn(0, MAX_COOLDOWN_WAIT_MS)
        if (waitMs > 0) delay(waitMs)
    }

    /**
     * Exponential backoff with jitter. Waits for at least the server's `retryAfterMs` hint,
     * then adds geometrically increasing delay per attempt: `base * multiplier^attempt + jitter`.
     * Capped at [BACKOFF_CAP_MS].
     */
    private suspend fun exponentialBackoff(attempt: Int, retryAfterMs: Long) {
        val minCooldownWait = botPool.minTempCooldownExpiryMs() - System.currentTimeMillis()
        val expDelay = (BACKOFF_BASE_MS * Math.pow(BACKOFF_MULTIPLIER, attempt.toDouble())).toLong()
        val jitter = (expDelay * 0.2 * (Math.random() * 2 - 1)).toLong()
        val waitMs = maxOf(retryAfterMs, minCooldownWait, expDelay + jitter).coerceIn(0, BACKOFF_CAP_MS)
        if (waitMs > 0) delay(waitMs)
    }

    /** Same as [com.ulap.data.remote.TelegramUploader]: 429 → exception so [TelegramRateLimiter] and callers can react. */
    private fun throwIfTelegramRateLimited(response: TelegramResponse<*>) {
        if (response.errorCode == 429) {
            val retryAfterMs = (response.parameters?.retryAfter ?: 30) * 1000L
            throw TelegramRateLimitException(retryAfterMs)
        }
    }

}
