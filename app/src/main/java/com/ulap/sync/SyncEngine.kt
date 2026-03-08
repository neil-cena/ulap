package com.ulap.sync

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.domain.model.FileUploadProgress
import com.google.gson.Gson
import com.ulap.data.remote.BackupIndexManager
import com.ulap.data.remote.DownloadResult
import com.ulap.data.remote.StreamingFastStartReader
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramDownloader
import com.ulap.data.remote.ParallelChunkDownloader
import com.ulap.data.remote.TelegramApiException
import com.ulap.data.remote.TelegramRateLimitException
import com.ulap.data.remote.TelegramRateLimiter
import com.ulap.data.remote.ThrottleReason
import com.ulap.data.remote.TelegramUploader
import com.ulap.data.remote.UploadResult
import com.ulap.data.remote.toUploadErrorDetail
import com.ulap.data.remote.CHUNKED_FILE_ID_PREFIX
import com.ulap.data.remote.sanitizeTokenForPath
import com.ulap.debug.DebugLogBuffer
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.SyncOperation
import com.ulap.domain.model.SyncProgress
import com.ulap.domain.model.BackupCompletionEvent
import com.ulap.domain.repository.CredentialRepository
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.data.repository.UploadSpeedMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import androidx.exifinterface.media.ExifInterface
import javax.inject.Inject
import javax.inject.Singleton

private const val LARGE_FILE_CONCURRENCY = 1
private const val SMALL_FILE_CONCURRENCY = 3
private const val DOWNLOAD_CONCURRENCY = 3
private const val CHUNKED_THRESHOLD = 50L * 1024 * 1024 // same as TelegramUploader's single upload limit

@Singleton
class SyncEngine @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val chunkMetadataDao: ChunkMetadataDao,
    private val uploader: TelegramUploader,
    private val downloader: TelegramDownloader,
    private val parallelDownloader: ParallelChunkDownloader,
    private val credentialRepository: CredentialRepository,
    private val contentResolver: ContentResolver,
    private val backupIndexManager: BackupIndexManager,
    private val debugLog: DebugLogBuffer,
    @ApplicationContext private val appContext: Context,
    private val api: TelegramBotApi,
    private val rateLimiter: TelegramRateLimiter,
    private val userPrefs: UserPreferencesRepository,
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    /** Mirrors rateLimiter.throttleState into _progress while an upload is active. */
    private var throttleSyncJob: Job? = null
    private var uploadCancelled = false  // must be reset by startUpload() before each run

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    // Rolling speed tracking: circular buffer of (timestampMs, bytesTransferred) samples.
    private val speedSamples = ArrayDeque<Pair<Long, Long>>(32)
    private val speedWindowMs = 30_000L // 30-second rolling window

    suspend fun startUpload() {
        activeJob?.cancelAndJoin()
        uploadCancelled = false
        _progress.update { it.copy(isPaused = false) }
        // Mirror throttle state into progress for the duration of the upload.
        throttleSyncJob?.cancel()
        throttleSyncJob = engineScope.launch {
            rateLimiter.throttleState.collect { ts ->
                _progress.update {
                    it.copy(
                        isRateLimited = ts.isThrottled,
                        throttleReason = ts.reason,
                        throttleResumeAtMs = ts.resumeAtMs,
                    )
                }
            }
        }
        activeJob = engineScope.launch { runUploadPipeline() }
    }

    suspend fun retryFailed() {
        mediaItemDao.resetFailedToPending()
        startUpload()
    }

    suspend fun resetFailedToPending() {
        mediaItemDao.resetFailedToPending()
    }

    suspend fun startDownload() {
        activeJob?.cancelAndJoin()
        activeJob = engineScope.launch { runDownloadPipeline() }
    }

    fun cancel() {
        uploadCancelled = true
        activeJob?.cancel()
        throttleSyncJob?.cancel()
        throttleSyncJob = null
        _progress.update { SyncProgress() }
    }

    fun pause() {
        uploadCancelled = true
        activeJob?.cancel()
        // Keep progress visible but mark as paused so the service doesn't auto-stop.
        _progress.update { it.copy(isActive = false, isPaused = true) }
        // Reset any item that got stuck in UPLOADING status back to PENDING.
        engineScope.launch { mediaItemDao.resetStaleUploadingToPending() }
    }

    suspend fun resume() {
        startUpload()
    }

    fun clearCompletionEvent() {
        _progress.update { it.copy(completionEvent = null) }
    }

    suspend fun deleteAllBackups(
        onProgress: (deleted: Int, total: Int) -> Unit = { _, _ -> },
    ): DeleteAllBackupsResult = withContext(Dispatchers.IO) {
        // Stop any ongoing sync first.
        cancel()

        val token = credentialRepository.getBotToken()
            ?: return@withContext DeleteAllBackupsResult.Failure(Exception("No credentials"))
        val chatId = credentialRepository.getChatId()
            ?: return@withContext DeleteAllBackupsResult.Failure(Exception("No credentials"))
        val safeToken = sanitizeTokenForPath(token)
        val gson = Gson()

        // Collect all message IDs to delete.
        // Primary source: the pinned Telegram index — the ground truth of what's in the chat.
        // Supplemental: local DB (covers items uploaded in this session before index export).
        val indexIds = backupIndexManager.loadMessageIdsFromPinnedIndex(token, chatId)
        val dbBackupIds = mediaItemDao.getAllBackupMessageIds()
        val thumbIds = mediaItemDao.getAllThumbnailMessageIds()
        val chunkIds = mediaItemDao.getAllChunkMessageIdsJson()
            .flatMap { json ->
                runCatching {
                    gson.fromJson(json, Array<Long>::class.java)?.toList() ?: emptyList()
                }.getOrElse { emptyList() }
            }

        // Also collect chunk message IDs from the new chunk_metadata table.
        val chunkTableIds = try {
            val allBackedUpItems = mediaItemDao.getAllBackedUp()
            allBackedUpItems.flatMap { item ->
                chunkMetadataDao.getAllMessageIdsForMedia(item.id)
            }
        } catch (e: Exception) {
            debugLog.log("SyncEngine", "deleteAllBackups: chunk table query failed — ${e.message}")
            emptyList()
        }

        // Also include the pinned index message itself.
        val indexMessageId: Long? = try {
            val chatResponse = rateLimiter.withRateLimit { api.getChat(safeToken, chatId) }
            val pinned = chatResponse.result?.pinnedMessage
            if (pinned != null && pinned.caption == "[ulap-backup-index]") pinned.messageId else null
        } catch (e: Exception) {
            debugLog.log("SyncEngine", "deleteAllBackups: getChat failed — ${e.message}")
            null
        }

        // Deduplicate all message IDs.
        val allIds = (indexIds + dbBackupIds + thumbIds + chunkIds + chunkTableIds + listOfNotNull(indexMessageId))
            .distinct()
            .filter { it > 0L }
        val total = allIds.size
        debugLog.log("SyncEngine", "deleteAllBackups: indexIds=${indexIds.size}, dbIds=${dbBackupIds.size}, thumbIds=${thumbIds.size}, chunkIds=${chunkIds.size}, total=$total ids=${allIds.take(5)}")
        if (allIds.isEmpty()) {
            debugLog.log("SyncEngine", "deleteAllBackups: no message IDs found — nothing to delete from Telegram")
            mediaItemDao.resetBackedUpToPending()
            mediaItemDao.deleteCloudOnlyItems()
            credentialRepository.setLastIndexFileId(null)
            return@withContext DeleteAllBackupsResult.Success
        }
        var deletedSoFar = 0
        var failedBatches = 0

        for (batch in allIds.chunked(100)) {
            try {
                val idsJson = gson.toJson(batch)
                debugLog.log("SyncEngine", "deleteAllBackups: sending batch of ${batch.size}, first=${batch.first()}")
                val response = rateLimiter.withRateLimit {
                    api.deleteMessages(safeToken, chatId, idsJson)
                }
                debugLog.log("SyncEngine", "deleteAllBackups: deleteMessages ok=${response.ok} err=${response.errorCode} desc=${response.description}")
                if (!response.ok) {
                    // Bulk delete rejected — fall back to per-message delete.
                    for (msgId in batch) {
                        try {
                            val r = rateLimiter.withRateLimit { api.deleteMessage(safeToken, chatId, msgId) }
                            if (!r.ok) debugLog.log("SyncEngine", "deleteAllBackups: deleteMessage($msgId) ok=false err=${r.errorCode} ${r.description}")
                        } catch (e: TelegramApiException) {
                            if (e.errorCode == 400 && e.description?.contains("not found", ignoreCase = true) == true) {
                                // already gone — no-op
                            } else {
                                debugLog.log("SyncEngine", "deleteAllBackups: deleteMessage($msgId) exception — ${e.message}")
                                failedBatches++
                            }
                        } catch (e: Exception) {
                            debugLog.log("SyncEngine", "deleteAllBackups: deleteMessage($msgId) exception — ${e.message}")
                            failedBatches++
                        }
                    }
                }
            } catch (e: TelegramApiException) {
                if (e.errorCode == 400 && e.description?.contains("not found", ignoreCase = true) == true) {
                    // already gone — no-op
                } else {
                    debugLog.log("SyncEngine", "deleteAllBackups: batch exception — ${e.message}")
                    failedBatches++
                }
            } catch (e: Exception) {
                debugLog.log("SyncEngine", "deleteAllBackups: batch exception — ${e.message}")
                failedBatches++
            }
            deletedSoFar += batch.size
            onProgress(deletedSoFar, total)
        }

        // Always reset local DB regardless of API failures (best-effort Telegram delete).
        mediaItemDao.resetBackedUpToPending()
        mediaItemDao.deleteCloudOnlyItems()
        credentialRepository.setLastIndexFileId(null)

        debugLog.log("SyncEngine", "deleteAllBackups: done. failedBatches=$failedBatches, total=$total")

        if (failedBatches == 0) DeleteAllBackupsResult.Success
        else DeleteAllBackupsResult.PartialSuccess(failedBatches)
    }

    /**
     * Records a speed sample and returns (speedBps, estimatedRemainingMs).
     * Uses a 30-second rolling window of (time, totalBytes) samples.
     * Thread-safety: called from IO coroutines; synchronized via the _progress StateFlow update.
     */
    @Synchronized
    private fun computeSpeedAndEta(totalBytesUploaded: Long, totalBytesExpected: Long): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        speedSamples.addLast(Pair(now, totalBytesUploaded))
        // Evict samples older than the window.
        while (speedSamples.size > 1 && now - speedSamples.first().first > speedWindowMs) {
            speedSamples.removeFirst()
        }
        if (speedSamples.size < 2) return Pair(0L, 0L)
        val oldest = speedSamples.first()
        val deltaBytes = totalBytesUploaded - oldest.second
        val deltaMs = now - oldest.first
        val speedBps = if (deltaMs > 0) deltaBytes * 1000L / deltaMs else 0L
        val remaining = totalBytesExpected - totalBytesUploaded
        val etaMs = if (speedBps > 0 && remaining > 0) remaining * 1000L / speedBps else 0L
        return Pair(speedBps, etaMs)
    }

    private suspend fun runUploadPipeline() {
        speedSamples.clear()
        _progress.update {
            SyncProgress(isActive = true, operation = SyncOperation.UPLOADING, itemsTotal = 0, activeUploads = emptyMap())
        }
        try {
            val token = credentialRepository.getBotToken() ?: return
            val chatId = credentialRepository.getChatId() ?: return

            // Sweep orphaned temp files before fetchAndMerge to avoid racing with new temp files.
            appContext.cacheDir.listFiles { f ->
                f.name.startsWith("ulap_raw_") ||
                    f.name.startsWith("ulap_fs_") ||
                    f.name.startsWith("ulap_exif_")
            }?.forEach { it.delete() }

            // Sweep incomplete stream files (no .done marker) left by crashed download sessions.
            // Apply a 5-minute grace period to avoid deleting files being actively written by
            // MediaViewerViewModel (which may start a background download concurrently).
            val sweepCutoff = System.currentTimeMillis() - 5L * 60 * 1000
            appContext.cacheDir.listFiles { f ->
                f.name.startsWith("ulap_stream_") && f.name.endsWith(".mp4")
            }?.forEach { mp4 ->
                val markerName = mp4.name.removeSuffix(".mp4") + ".done"
                val marker = File(appContext.cacheDir, markerName)
                if (!marker.exists() && mp4.lastModified() < sweepCutoff) mp4.delete()
            }

            // Any item left in UPLOADING state means the previous run crashed mid-upload.
            // Reset them to PENDING so they are re-attempted in this run.
            // resetStaleUploadingToPending intentionally does NOT clear uploadedChunkCount —
            // the chunk progress is the resume seed for the next run.
            mediaItemDao.resetStaleUploadingToPending()
            // Mark chunked items from old 45MB chunks as FAILED so they re-upload with 19MB (streamable) chunks.
            mediaItemDao.markOversizedChunkedItemsAsFailed()
            // Clear chunk progress for items in terminal states (BACKED_UP/EXCLUDED/CLOUD_ONLY).
            // PENDING and FAILED items keep their progress for resume on the next attempt.
            mediaItemDao.clearOrphanedChunkProgress()

            backupIndexManager.fetchAndMerge(token, chatId)

            // scanAndSync already marked non-enabled-bucket items as EXCLUDED,
            // so getPendingOrFailed() only returns items in currently enabled buckets.
            val items = mediaItemDao.getPendingOrFailed()
            if (items.isEmpty()) return

            debugLog.log("SyncEngine", "upload pipeline: starting — ${items.size} pending/failed items (large=${items.count { it.size > CHUNKED_THRESHOLD }}, small=${items.count { it.size <= CHUNKED_THRESHOLD }})")
            _progress.update { it.copy(itemsTotal = items.size) }

            // Partition into large/small queues to prevent a large chunked upload from starving
            // small files. Large files (1 worker) and small files (3 workers) run concurrently.
            val (largeItems, smallItems) = items.partition { it.size > CHUNKED_THRESHOLD }

            val largeQueue = Channel<MediaItemEntity>(Channel.UNLIMITED)
            val smallQueue = Channel<MediaItemEntity>(Channel.UNLIMITED)

            // Use for loops — Channel.send is a suspend function.
            // Collection.forEach does not accept a suspend lambda; forEach { send(it) } is a compile error.
            for (item in largeItems) largeQueue.send(item)
            largeQueue.close()
            for (item in smallItems) smallQueue.send(item)
            smallQueue.close()

            // In Conservative mode, use 1 small-file worker to lower API call rate.
            val smallFileConcurrency = if (userPrefs.uploadSpeedMode.value == UploadSpeedMode.CONSERVATIVE) 1
            else SMALL_FILE_CONCURRENCY

            try {
                supervisorScope {
                    val largeWorkers = (1..LARGE_FILE_CONCURRENCY).map {
                        launch { for (e in largeQueue) processUpload(e, token, chatId) }
                    }
                    val smallWorkers = (1..smallFileConcurrency).map {
                        launch { for (e in smallQueue) processUpload(e, token, chatId) }
                    }
                    // supervisorScope awaits all children before returning; joinAll() is not needed.
                }
                debugLog.log("SyncEngine", "upload pipeline: all workers completed normally")
            } catch (e: CancellationException) {
                uploadCancelled = true
                debugLog.log("SyncEngine", "upload pipeline: cancelled")
                throw e
            } catch (e: Exception) {
                // One or more workers threw an unexpected exception.
                // Fall through to finally so exportAndUpload still runs for successful files.
                debugLog.log("SyncEngine", "upload pipeline: worker exception — ${e.message}")
            } finally {
                // Export the index for any items that succeeded in this run.
                // Only reached when items were actually queued (early-exit paths before this try
                // return before any work is done and do not need an export).
                // NonCancellable ensures the suspend call proceeds even when the coroutine is being cancelled.
                // exportAndUpload internally guards: if there are no indexable items it returns success(null).
                debugLog.log("SyncEngine", "upload pipeline: exporting index")
                withContext(NonCancellable) {
                    val result = backupIndexManager.exportAndUpload(token, chatId)
                    result.getOrNull()?.let { fileId ->
                        credentialRepository.setLastIndexFileId(fileId)
                        debugLog.log("SyncEngine", "upload pipeline: index exported, fileId=$fileId")
                    }
                    if (result.isFailure) {
                        debugLog.log("SyncEngine", "upload pipeline: index export failed — ${result.exceptionOrNull()?.message}")
                    }
                }
            }
        } finally {
            throttleSyncJob?.cancel()
            throttleSyncJob = null
            val snapshot = _progress.value
            val completionEvent = if (!uploadCancelled && snapshot.itemsTotal > 0) {
                BackupCompletionEvent(
                    succeeded = snapshot.itemsDone,
                    failed = snapshot.itemsTotal - snapshot.itemsDone,
                )
            } else null
            _progress.update {
                it.copy(
                    isActive = false,
                    operation = SyncOperation.IDLE,
                    isRateLimited = false,
                    throttleReason = ThrottleReason.NONE,
                    throttleResumeAtMs = 0L,
                    completionEvent = completionEvent,
                )
            }
        }
    }

    private suspend fun processUpload(
        entity: MediaItemEntity,
        token: String,
        chatId: String,
    ) {
        try {
            processUploadInternal(entity, token, chatId)
        } catch (e: TelegramRateLimitException) {
            // Rate limit exhausted inside the rate limiter (all MAX_RETRIES 429s used).
            // The circuit breaker is now OPEN, so the next item's withRateLimit call will
            // naturally suspend until the cooldown expires — no explicit delay needed here.
            // The worker stays alive to process remaining queue items.
            // This item remains UPLOADING; resetStaleUploadingToPending resets it at the
            // start of the next backup run with chunk progress intact for resume.
            debugLog.log("SyncEngine", "Rate limit exhausted for ${entity.fileName} — worker continues, item will retry next run")
        } catch (e: Exception) {
            // Catch anything that escaped (e.g. OkHttp re-trying on a consumed stream,
            // unexpected I/O errors) so the worker coroutine stays alive for the next item.
            // Note: the activeUploads entry is already removed by processUploadInternal's finally block.
            mediaItemDao.updateBackupResult(
                id = entity.id,
                status = BackupStatus.FAILED,
                error = e.toUploadErrorDetail(),
                syncedAt = null,
                fileId = null,
                messageId = null,
                thumbnailFileId = null,
            )
            _progress.update { it.copy(itemsDone = it.itemsDone + 1) }
        }
    }

    private suspend fun processUploadInternal(
        entity: MediaItemEntity,
        token: String,
        chatId: String,
    ) {
        val existing = mediaItemDao.findByFileNameSizeDate(entity.fileName, entity.size, entity.dateTaken)
        if (existing != null && existing.id != entity.id && existing.telegramFileId != null &&
            (existing.backupStatus == BackupStatus.BACKED_UP || existing.backupStatus == BackupStatus.CLOUD_ONLY)
        ) {
            mediaItemDao.updateBackupResult(
                id = entity.id,
                status = BackupStatus.BACKED_UP,
                error = null,
                syncedAt = System.currentTimeMillis(),
                fileId = existing.telegramFileId,
                messageId = existing.telegramMessageId,
                thumbnailFileId = existing.thumbnailFileId,
                contentHash = existing.contentHash,
            )
            _progress.update { it.copy(itemsDone = it.itemsDone + 1) }
            return
        }

        mediaItemDao.updateBackupResult(
            id = entity.id,
            status = BackupStatus.UPLOADING,
            error = null,
            syncedAt = null,
            fileId = null,
            messageId = null,
            thumbnailFileId = null,
        )

        // Register this file in the concurrent-uploads map so the UI can display all active
        // uploads simultaneously. Removed in the finally block below regardless of outcome.
        val displayFileName = sanitizeDisplayFileName(entity.fileName, entity.mimeType)
        _progress.update { prev ->
            prev.copy(
                activeUploads = prev.activeUploads + (entity.id to FileUploadProgress(
                    fileName = displayFileName,
                    bytesTotal = entity.size,
                ))
            )
        }

        val inputStream = try {
            contentResolver.openInputStream(Uri.parse(entity.contentUri))
        } catch (e: Exception) {
            null
        }

        if (inputStream == null) {
            mediaItemDao.updateBackupResult(
                id = entity.id,
                status = BackupStatus.FAILED,
                error = "Could not open file",
                syncedAt = null,
                fileId = null,
                messageId = null,
                thumbnailFileId = null,
            )
            _progress.update { prev ->
                prev.copy(
                    itemsDone = prev.itemsDone + 1,
                    activeUploads = prev.activeUploads - entity.id,
                )
            }
            return
        }

        // Compute MD5 from a separate stream open so the upload stream is untouched.
        // Non-fatal: if hashing fails (e.g. stream error) we proceed without it.
        val contentUri = Uri.parse(entity.contentUri)
        val contentHash: String? = computeMd5(contentUri)

        // Hash-based dedup: if another item with identical content is already backed up, reuse it.
        if (contentHash != null) {
            val hashMatch = mediaItemDao.findByContentHash(contentHash)
            if (hashMatch != null && hashMatch.id != entity.id && hashMatch.telegramFileId != null) {
                inputStream.close()
                mediaItemDao.updateBackupResult(
                    id = entity.id,
                    status = BackupStatus.BACKED_UP,
                    error = null,
                    syncedAt = System.currentTimeMillis(),
                    fileId = hashMatch.telegramFileId,
                    messageId = hashMatch.telegramMessageId,
                    thumbnailFileId = hashMatch.thumbnailFileId,
                    contentHash = contentHash,
                )
                _progress.update { prev ->
                    prev.copy(
                        itemsDone = prev.itemsDone + 1,
                        activeUploads = prev.activeUploads - entity.id,
                    )
                }
                return
            }
        }

        val isVideo = entity.mimeType.startsWith("video/")
        val willChunk = entity.size > CHUNKED_THRESHOLD
        var tempExif: File? = null

        // Strip GPS EXIF from JPEG images when the user has opted in.
        // We copy to a temp file, strip the GPS tags in-place, then stream from there.
        // Non-fatal: if stripping fails we fall through and re-open the original stream.
        var strippedInputStream = inputStream
        if (!isVideo && entity.mimeType == "image/jpeg" && userPrefs.stripExif.value) {
            val dest = File(appContext.cacheDir, "ulap_exif_${entity.id}.jpg")
            var strippingSucceeded = false
            try {
                inputStream.use { src ->
                    FileOutputStream(dest).use { dst -> src.copyTo(dst) }
                }
                val exif = ExifInterface(dest.absolutePath)
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null)
                exif.setAttribute(ExifInterface.TAG_GPS_SPEED, null)
                exif.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_TRACK, null)
                exif.setAttribute(ExifInterface.TAG_GPS_TRACK_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, null)
                exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, null)
                exif.setAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION, null)
                exif.saveAttributes()
                tempExif = dest
                strippingSucceeded = true
            } catch (_: Exception) {
                dest.delete()
            }
            if (!strippingSucceeded) {
                // inputStream was consumed during the copy attempt; re-open the original.
                strippedInputStream = contentResolver.openInputStream(Uri.parse(entity.contentUri))
                    ?: inputStream
            }
        }

        // For large videos, use StreamingFastStartReader: reorders moov/mdat in-flight with zero
        // extra disk usage by seeking the FileChannel rather than copying the full file to temp storage.
        // Falls back to strippedInputStream if the URI is not seekable or parsing fails.
        val uploadStream = if (isVideo && willChunk) {
            strippedInputStream.close()
            StreamingFastStartReader.open(contentResolver, contentUri)
                ?: contentResolver.openInputStream(contentUri) ?: return
        } else {
            // If EXIF stripping produced a temp file, stream from it; otherwise use the original.
            if (tempExif != null) FileInputStream(tempExif) else strippedInputStream
        }

        val actualFileSize = when {
            tempExif != null && tempExif.exists() -> tempExif.length()
            else -> entity.size
        }

        val resumeFromChunk = chunkMetadataDao.getUploadedCount(entity.id)
            .takeIf { it > 0 } ?: entity.uploadedChunkCount

        // Extract a first-frame thumbnail for all video uploads and upload it separately.
        // For chunked videos, use the content URI directly (no temp file needed with streaming FS).
        var videoThumbnailFileId: String? = null
        var videoThumbnailMessageId: Long? = null
        if (isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, Uri.parse(entity.contentUri))
                val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val out = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    bitmap.recycle()
                    val thumbResult = uploader.uploadThumbnail(token, chatId, out.toByteArray())
                    videoThumbnailFileId = thumbResult?.first
                    videoThumbnailMessageId = thumbResult?.second
                }
            } catch (_: Exception) {
                // Thumbnail extraction is non-fatal; proceed without it.
            } finally {
                retriever.release()
            }
        }

        var result: UploadResult? = null
        try {
            result = uploadStream.use {
                var lastReported = 0L
                uploader.uploadMedia(
                    token = token,
                    chatId = chatId,
                    inputStream = it,
                    fileName = entity.fileName,
                    mimeType = entity.mimeType,
                    fileSize = actualFileSize,
                    caption = entity.fileName,
                    onProgress = { uploaded, total ->
                        val delta = uploaded - lastReported
                        lastReported = uploaded
                        val (speedBps, etaMs) = computeSpeedAndEta(uploaded, total.takeIf { it > 0 } ?: uploaded)
                        _progress.update { prog ->
                            val entry = prog.activeUploads[entity.id] ?: return@update prog.copy(
                                bytesTransferred = prog.bytesTransferred + delta
                            )
                            prog.copy(
                                bytesTransferred = prog.bytesTransferred + delta,
                                activeUploads = prog.activeUploads + (entity.id to entry.copy(
                                    bytesUploaded = uploaded,
                                    bytesTotal = total.takeIf { it > 0 } ?: entry.bytesTotal,
                                    uploadSpeedBps = speedBps,
                                    estimatedRemainingMs = etaMs,
                                ))
                            )
                        }
                    },
                    resumeFromChunk = resumeFromChunk,
                    onTotalChunksKnown = { total ->
                        _progress.update { prog ->
                            val entry = prog.activeUploads[entity.id] ?: return@update prog
                            prog.copy(activeUploads = prog.activeUploads + (entity.id to entry.copy(
                                totalChunks = total,
                                currentChunk = resumeFromChunk,
                            )))
                        }
                    },
                    onChunkUploaded = { index, fileId, messageId, byteOffset, byteLength ->
                        chunkMetadataDao.insertChunk(
                            ChunkMetadataEntity(
                                mediaItemId = entity.id,
                                chunkIndex = index,
                                telegramFileId = fileId,
                                telegramMessageId = messageId,
                                byteOffset = byteOffset,
                                byteLength = byteLength,
                            )
                        )
                        _progress.update { prog ->
                            val entry = prog.activeUploads[entity.id] ?: return@update prog
                            prog.copy(activeUploads = prog.activeUploads + (entity.id to entry.copy(
                                currentChunk = index + 1,
                                chunkRetryAttempt = 0,
                            )))
                        }
                    },
                    onChunkRetry = { attempt ->
                        _progress.update { prog ->
                            val entry = prog.activeUploads[entity.id] ?: return@update prog
                            prog.copy(activeUploads = prog.activeUploads + (entity.id to entry.copy(
                                chunkRetryAttempt = attempt,
                            )))
                        }
                    },
                    thumbnailFileId = videoThumbnailFileId,
                    thumbnailMessageId = videoThumbnailMessageId,
                )
            }
        } finally {
            // Guaranteed cleanup even if uploadMedia throws (e.g. coroutine cancellation).
            // Remove the per-file entry so it stops showing in the UI.
            // Do NOT add itemsDone+1 here — rate-limited items are not completed.
            tempExif?.delete()
            _progress.update { prev -> prev.copy(activeUploads = prev.activeUploads - entity.id) }
        }
        // result is null only when an exception escaped the try block (e.g. TelegramRateLimitException
        // or coroutine cancellation), in which case the exception propagates past this point anyway.
        result ?: return

        when (result) {
            is UploadResult.Success -> {
                mediaItemDao.clearChunkProgress(entity.id)
                chunkMetadataDao.deleteChunksForMedia(entity.id)
                // Prefer Telegram's returned thumbnail; fall back to our pre-extracted one for videos.
                val effectiveThumbnailFileId = result.thumbnailFileId ?: videoThumbnailFileId
                val effectiveThumbnailMessageId = if (result.thumbnailFileId != null) result.thumbnailMessageId else videoThumbnailMessageId
                mediaItemDao.updateBackupResult(
                    id = entity.id,
                    status = BackupStatus.BACKED_UP,
                    error = null,
                    syncedAt = System.currentTimeMillis(),
                    fileId = result.fileId,
                    messageId = result.messageId,
                    thumbnailFileId = effectiveThumbnailFileId,
                    thumbnailMessageId = effectiveThumbnailMessageId,
                    chunkMessageIds = result.chunkMessageIds,
                    contentHash = contentHash,
                )
            }
            is UploadResult.Error -> {
                mediaItemDao.updateBackupResult(
                    id = entity.id,
                    status = BackupStatus.FAILED,
                    error = result.cause.toUploadErrorDetail(),
                    syncedAt = null,
                    fileId = null,
                    messageId = null,
                    thumbnailFileId = null,
                )
            }
        }
        _progress.update { it.copy(itemsDone = it.itemsDone + 1) }
    }

    private suspend fun runDownloadPipeline() {
        _progress.update {
            SyncProgress(isActive = true, operation = SyncOperation.DOWNLOADING, itemsTotal = 0)
        }

        try {
            val token = credentialRepository.getBotToken() ?: return
            val items = mediaItemDao.getAllCloudOnlyItems()

            if (items.isEmpty()) return

            val queue = Channel<MediaItemEntity>(Channel.UNLIMITED)
            for (item in items) queue.send(item)
            queue.close()

            _progress.update { it.copy(itemsTotal = items.size) }

            supervisorScope {
                (1..DOWNLOAD_CONCURRENCY).map {
                    launch { for (entity in queue) processDownload(entity, token) }
                }
            }
        } finally {
            val snapshot = _progress.value
            val completionEvent = if (snapshot.itemsTotal > 0) {
                BackupCompletionEvent(
                    succeeded = snapshot.itemsDone,
                    failed = maxOf(0, snapshot.itemsTotal - snapshot.itemsDone),
                )
            } else null
            _progress.update {
                it.copy(
                    isActive = false,
                    operation = SyncOperation.IDLE,
                    completionEvent = completionEvent,
                )
            }
        }
    }

    suspend fun downloadItem(item: MediaItem) {
        val token = credentialRepository.getBotToken() ?: return
        val entity = mediaItemDao.findById(item.id) ?: return
        processDownload(entity, token)
    }

    private suspend fun processDownload(
        entity: MediaItemEntity,
        token: String,
    ) {
        val fileId = entity.telegramFileId ?: return

        val mimeType = entity.mimeType
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entity.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Ulap Restore"
            )
        }

        val uri = if (mimeType.startsWith("video/")) {
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } ?: return

        val outputStream = contentResolver.openOutputStream(uri) ?: return
        val isChunkedItem = fileId.startsWith(CHUNKED_FILE_ID_PREFIX) ||
            chunkMetadataDao.hasChunks(entity.id) > 0

        val result = outputStream.use {
            if (isChunkedItem) {
                parallelDownloader.downloadToStream(
                    token = token,
                    mediaItemId = entity.id,
                    outputStream = it,
                    onProgress = { downloaded, total ->
                        _progress.update { prog ->
                            prog.copy(bytesTransferred = prog.bytesTransferred + downloaded)
                        }
                    },
                )
            } else {
                downloader.download(
                    token = token,
                    fileId = fileId,
                    outputStream = it,
                    onProgress = { downloaded, total ->
                        _progress.update { prog ->
                            prog.copy(bytesTransferred = prog.bytesTransferred + downloaded)
                        }
                    },
                )
            }
        }

        when (result) {
            is DownloadResult.Success -> {
                _progress.update { it.copy(itemsDone = it.itemsDone + 1) }
            }
            is DownloadResult.Error -> {
                contentResolver.delete(uri, null, null)
            }
        }
    }

    suspend fun downloadCloudItemToLocal(mediaId: String): Result<android.net.Uri> = withContext(Dispatchers.IO) {
        val token = credentialRepository.getBotToken() ?: return@withContext Result.failure(Exception("No token"))
        val entity = mediaItemDao.findById(mediaId) ?: return@withContext Result.failure(Exception("Item not found"))
        if (entity.backupStatus != BackupStatus.CLOUD_ONLY) return@withContext Result.failure(Exception("Not a cloud item"))
        val fileId = entity.telegramFileId ?: return@withContext Result.failure(Exception("No file id"))
        val mimeType = entity.mimeType
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entity.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Ulap Restore"
            )
        }
        val insertedUri = if (mimeType.startsWith("video/")) {
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } ?: return@withContext Result.failure(Exception("Could not create file"))
        val outputStream = contentResolver.openOutputStream(insertedUri)
        if (outputStream == null) {
            contentResolver.delete(insertedUri, null, null)
            return@withContext Result.failure(Exception("Could not open output"))
        }
        val isChunkedItem = fileId.startsWith(CHUNKED_FILE_ID_PREFIX) ||
            chunkMetadataDao.hasChunks(entity.id) > 0

        val result = outputStream.use {
            if (isChunkedItem) {
                parallelDownloader.downloadToStream(
                    token = token,
                    mediaItemId = entity.id,
                    outputStream = it,
                )
            } else {
                downloader.download(
                    token = token,
                    fileId = fileId,
                    outputStream = it,
                    onProgress = { _, _ -> },
                )
            }
        }
        when (result) {
            is DownloadResult.Success -> {
                mediaItemDao.update(
                    entity.copy(
                        contentUri = insertedUri.toString(),
                        backupStatus = BackupStatus.BACKED_UP,
                        lastSyncedAt = System.currentTimeMillis(),
                    ),
                )
                Result.success(insertedUri)
            }
            is DownloadResult.Error -> {
                contentResolver.delete(insertedUri, null, null)
                Result.failure(result.cause)
            }
        }
    }

    /**
     * Streams the file at [uri] through MD5 and returns the hex digest, or null if the file
     * cannot be opened. Uses a fixed 64KB buffer to keep memory usage constant for large files.
     */
    private fun computeMd5(uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val buf = ByteArray(65_536)
            val opened = contentResolver.openInputStream(uri) ?: return null
            opened.use { stream ->
                var read: Int
                while (stream.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Returns a corrected display name for a media file when the filename extension disagrees with
 * the actual mime type. For example, a video named "Screenshot_20260108.jpg" (video/mp4) will
 * be shown as "Screenshot_20260108.mp4" so users aren't confused by a 9 GB ".jpg" file.
 *
 * Only corrects image-vs-video mismatches (the most common real-world case); other mime types
 * are returned with the original name.
 */
internal fun sanitizeDisplayFileName(fileName: String, mimeType: String): String {
    val dotIdx = fileName.lastIndexOf('.')
    val currentExt = if (dotIdx >= 0) fileName.substring(dotIdx + 1).lowercase() else ""
    val base = if (dotIdx >= 0) fileName.substring(0, dotIdx) else fileName

    val isVideoMime = mimeType.startsWith("video/")
    val isImageMime = mimeType.startsWith("image/")

    val imageExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif")
    val videoExts = setOf("mp4", "mov", "mkv", "avi", "3gp", "webm", "ts", "m4v", "m2ts", "mts")

    val extensionMismatch = (isVideoMime && currentExt in imageExts) ||
        (isImageMime && currentExt in videoExts)

    if (!extensionMismatch) return fileName

    // Pick a canonical extension from the mime subtype (e.g. "video/mp4" → "mp4").
    val mimeSubtype = mimeType.substringAfter('/').lowercase()
    val canonicalExt = when (mimeSubtype) {
        "jpeg" -> "jpg"
        "quicktime" -> "mov"
        "x-matroska" -> "mkv"
        "x-msvideo" -> "avi"
        "3gpp" -> "3gp"
        "x-m4v" -> "m4v"
        else -> if (mimeSubtype.length <= 5) mimeSubtype else currentExt // keep original if unknown
    }

    return if (canonicalExt != currentExt) "$base.$canonicalExt" else fileName
}

sealed class DeleteAllBackupsResult {
    object Success : DeleteAllBackupsResult()
    data class PartialSuccess(val failedBatches: Int) : DeleteAllBackupsResult()
    data class Failure(val cause: Exception) : DeleteAllBackupsResult()
}
