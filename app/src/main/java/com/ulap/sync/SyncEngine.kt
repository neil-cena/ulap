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
import com.google.gson.Gson
import com.ulap.data.remote.BackupIndexManager
import com.ulap.data.remote.DownloadResult
import com.ulap.data.remote.Mp4FastStart
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramDownloader
import com.ulap.data.remote.TelegramApiException
import com.ulap.data.remote.TelegramRateLimitException
import com.ulap.data.remote.TelegramRateLimiter
import com.ulap.data.remote.TelegramUploader
import com.ulap.data.remote.UploadResult
import com.ulap.data.remote.MAX_FILE_SIZE
import com.ulap.data.remote.sanitizeTokenForPath
import com.ulap.debug.DebugLogBuffer
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.SyncOperation
import com.ulap.domain.model.SyncProgress
import com.ulap.domain.model.BackupCompletionEvent
import com.ulap.domain.repository.CredentialRepository
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
import javax.inject.Inject
import javax.inject.Singleton

private const val LARGE_FILE_CONCURRENCY = 1
private const val SMALL_FILE_CONCURRENCY = 3
private const val DOWNLOAD_CONCURRENCY = 3
private const val CHUNKED_THRESHOLD = 50L * 1024 * 1024 // same as TelegramUploader's single upload limit

@Singleton
class SyncEngine @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val uploader: TelegramUploader,
    private val downloader: TelegramDownloader,
    private val credentialRepository: CredentialRepository,
    private val contentResolver: ContentResolver,
    private val backupIndexManager: BackupIndexManager,
    private val debugLog: DebugLogBuffer,
    @ApplicationContext private val appContext: Context,
    private val api: TelegramBotApi,
    private val rateLimiter: TelegramRateLimiter,
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var uploadCancelled = false  // must be reset by startUpload() before each run

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    suspend fun startUpload() {
        activeJob?.cancelAndJoin()
        uploadCancelled = false
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
        _progress.update { SyncProgress() }
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
        val allIds = (indexIds + dbBackupIds + thumbIds + chunkIds + listOfNotNull(indexMessageId))
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

    private suspend fun runUploadPipeline() {
        _progress.update {
            SyncProgress(isActive = true, operation = SyncOperation.UPLOADING, itemsTotal = 0)
        }
        try {
            val token = credentialRepository.getBotToken() ?: return
            val chatId = credentialRepository.getChatId() ?: return

            // Sweep orphaned temp files before fetchAndMerge to avoid racing with new temp files.
            appContext.cacheDir.listFiles { f ->
                f.name.startsWith("ulap_raw_") || f.name.startsWith("ulap_fs_")
            }?.forEach { it.delete() }

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

            try {
                supervisorScope {
                    val largeWorkers = (1..LARGE_FILE_CONCURRENCY).map {
                        launch { for (e in largeQueue) processUpload(e, token, chatId) }
                    }
                    val smallWorkers = (1..SMALL_FILE_CONCURRENCY).map {
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
                // One or more workers failed (e.g. TelegramRateLimitException).
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
            // Global rate limit exhausted inside rateLimiter — this is NOT this item's fault.
            // Re-throw so the worker coroutine is cancelled rather than marking the item FAILED.
            // The item stays UPLOADING; resetStaleUploadingToPending on the next run resets it
            // to PENDING with its uploadedChunkCount intact so it can resume.
            // Because SyncEngine uses SupervisorJob, only this worker is cancelled —
            // other workers continue with their current items.
            _progress.update { it.copy(chunkRetryAttempt = 0, currentChunk = 0, totalChunks = 0, isRateLimited = true) }
            throw e
        } catch (e: Exception) {
            // Catch anything that escaped (e.g. OkHttp re-trying on a consumed stream,
            // unexpected I/O errors) so the worker coroutine stays alive for the next item.
            _progress.update { it.copy(chunkRetryAttempt = 0, currentChunk = 0, totalChunks = 0) }
            mediaItemDao.updateBackupResult(
                id = entity.id,
                status = BackupStatus.FAILED,
                error = e.message ?: "Unexpected error",
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
        // Clear rate-limited flag when we successfully start processing the next item
        _progress.update { it.copy(isRateLimited = false) }
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

        // Announce which file is being uploaded so the UI can show per-file progress
        _progress.update { it.copy(
            currentFileName = entity.fileName,
            currentFileBytesTotal = entity.size,
            currentFileBytes = 0L,
        ) }

        if (entity.size > MAX_FILE_SIZE) {
            mediaItemDao.updateBackupResult(
                id = entity.id,
                status = BackupStatus.EXCLUDED,
                error = "File exceeds 2GB limit",
                syncedAt = System.currentTimeMillis(),
                fileId = null,
                messageId = null,
                thumbnailFileId = null,
            )
            _progress.update { it.copy(itemsDone = it.itemsDone + 1) }
            return
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
            _progress.update { it.copy(itemsDone = it.itemsDone + 1) }
            return
        }

        val isVideo = entity.mimeType.startsWith("video/")
        val willChunk = entity.size > CHUNKED_THRESHOLD
        var tempRaw: File? = null
        var tempFastStarted: File? = null

        // entity.id is the DB primary key in "external_1234" / "internal_1234" format —
        // no slashes, unique by definition. Use it directly as a temp filename component.
        val uploadStream = if (isVideo && willChunk) {
            tempRaw = File(appContext.cacheDir, "ulap_raw_${entity.id}.mp4")
            tempFastStarted = File(appContext.cacheDir, "ulap_fs_${entity.id}.mp4")
            try {
                inputStream.use { src ->
                    FileOutputStream(tempRaw).use { dst -> src.copyTo(dst) }
                }
                val success = Mp4FastStart.fastStart(tempRaw, tempFastStarted)
                if (success && tempFastStarted.exists()) {
                    tempRaw.delete()
                    FileInputStream(tempFastStarted)
                } else {
                    tempFastStarted.delete()
                    FileInputStream(tempRaw)
                }
            } catch (e: Exception) {
                tempFastStarted?.delete()
                if (tempRaw.exists()) {
                    FileInputStream(tempRaw)
                } else {
                    tempRaw.delete()
                    contentResolver.openInputStream(Uri.parse(entity.contentUri))
                        ?: return
                }
            }
        } else {
            inputStream
        }

        val actualFileSize = when {
            tempFastStarted != null && tempFastStarted.exists() -> tempFastStarted.length()
            tempRaw != null && tempRaw.exists() -> tempRaw.length()
            else -> entity.size
        }

        val resumeFromChunk = entity.uploadedChunkCount
        val existingFileIdsJson = entity.uploadedChunks ?: "[]"

        // For chunked video uploads, extract a first-frame thumbnail and upload it separately.
        // This provides thumbnails for large (>50MB) videos that Telegram never returns a thumbnail for.
        var videoThumbnailFileId: String? = null
        var videoThumbnailMessageId: Long? = null
        if (isVideo && willChunk) {
            val thumbSourceFile = when {
                tempFastStarted != null && tempFastStarted.exists() -> tempFastStarted
                tempRaw != null && tempRaw.exists() -> tempRaw
                else -> null
            }
            if (thumbSourceFile != null) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(thumbSourceFile.absolutePath)
                    val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        val out = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 40, out)
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
                        _progress.update { prog ->
                            prog.copy(
                                currentFileBytes = uploaded,
                                currentFileBytesTotal = total,
                                bytesTransferred = prog.bytesTransferred + delta,
                            )
                        }
                    },
                    resumeFromChunk = resumeFromChunk,
                    existingFileIdsJson = existingFileIdsJson,
                    onTotalChunksKnown = { total ->
                        _progress.update { it.copy(totalChunks = total, currentChunk = resumeFromChunk) }
                    },
                    onChunkSaved = { count, json ->
                        mediaItemDao.saveChunkProgress(entity.id, json, count)
                        _progress.update { it.copy(currentChunk = count, chunkRetryAttempt = 0) }
                    },
                    onChunkRetry = { attempt ->
                        _progress.update { it.copy(chunkRetryAttempt = attempt) }
                    },
                    thumbnailFileId = videoThumbnailFileId,
                    thumbnailMessageId = videoThumbnailMessageId,
                )
            }
        } finally {
            // Guaranteed cleanup even if uploadMedia throws (e.g. coroutine cancellation).
            // Do NOT add itemsDone+1 here — rate-limited items are not completed.
            tempRaw?.delete()
            tempFastStarted?.delete()
        }
        // result is null only when an exception escaped the try block (e.g. TelegramRateLimitException
        // or coroutine cancellation), in which case the exception propagates past this point anyway.
        result ?: return

        when (result) {
            is UploadResult.Success -> {
                mediaItemDao.clearChunkProgress(entity.id)
                _progress.update { it.copy(chunkRetryAttempt = 0, currentChunk = 0, totalChunks = 0) }
                mediaItemDao.updateBackupResult(
                    id = entity.id,
                    status = BackupStatus.BACKED_UP,
                    error = null,
                    syncedAt = System.currentTimeMillis(),
                    fileId = result.fileId,
                    messageId = result.messageId,
                    thumbnailFileId = result.thumbnailFileId,
                    thumbnailMessageId = result.thumbnailMessageId,
                    chunkMessageIds = result.chunkMessageIds,
                )
            }
            is UploadResult.Error -> {
                _progress.update { it.copy(chunkRetryAttempt = 0, currentChunk = 0, totalChunks = 0) }
                mediaItemDao.updateBackupResult(
                    id = entity.id,
                    status = BackupStatus.FAILED,
                    error = result.cause.message,
                    syncedAt = null,
                    fileId = null,
                    messageId = null,
                    thumbnailFileId = null,
                )
            }
            is UploadResult.FileTooLarge -> {
                mediaItemDao.updateBackupResult(
                    id = entity.id,
                    status = BackupStatus.EXCLUDED,
                    error = "File exceeds 2GB limit",
                    syncedAt = System.currentTimeMillis(),
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
        val result = outputStream.use {
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
        val result = outputStream.use {
            downloader.download(
                token = token,
                fileId = fileId,
                outputStream = it,
                onProgress = { _, _ -> },
            )
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
}

sealed class DeleteAllBackupsResult {
    object Success : DeleteAllBackupsResult()
    data class PartialSuccess(val failedBatches: Int) : DeleteAllBackupsResult()
    data class Failure(val cause: Exception) : DeleteAllBackupsResult()
}
