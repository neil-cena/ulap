package com.ulap.sync

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.remote.BackupIndexManager
import com.ulap.data.remote.DownloadResult
import com.ulap.data.remote.Mp4FastStart
import com.ulap.data.remote.TelegramDownloader
import com.ulap.data.remote.TelegramUploader
import com.ulap.data.remote.UploadResult
import com.ulap.data.remote.MAX_FILE_SIZE
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import com.ulap.domain.model.SyncOperation
import com.ulap.domain.model.SyncProgress
import com.ulap.domain.repository.CredentialRepository
import com.ulap.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val UPLOAD_CONCURRENCY = 3
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
    @ApplicationContext private val appContext: Context,
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    suspend fun startUpload() {
        activeJob?.cancelAndJoin()
        activeJob = engineScope.launch { runUploadPipeline() }
    }

    suspend fun retryFailed() {
        mediaItemDao.resetFailedToPending()
        startUpload()
    }

    suspend fun startDownload() {
        activeJob?.cancelAndJoin()
        activeJob = engineScope.launch { runDownloadPipeline() }
    }

    fun cancel() {
        activeJob?.cancel()
        _progress.update { SyncProgress() }
    }

    private suspend fun runUploadPipeline() {
        val token = credentialRepository.getBotToken() ?: return
        val chatId = credentialRepository.getChatId() ?: return

        // Any item left in UPLOADING state means the previous run crashed mid-upload.
        // Reset them to PENDING so they are re-attempted in this run.
        mediaItemDao.resetStaleUploadingToPending()
        // Mark chunked items from old 45MB chunks as FAILED so they re-upload with 19MB (streamable) chunks.
        mediaItemDao.markOversizedChunkedItemsAsFailed()

        backupIndexManager.fetchAndMerge(token, chatId)

        // scanAndSync already marked non-enabled-bucket items as EXCLUDED,
        // so getPendingOrFailed() only returns items in currently enabled buckets.
        val items = mediaItemDao.getPendingOrFailed()
        if (items.isEmpty()) return

        val queue = Channel<com.ulap.data.local.entity.MediaItemEntity>(Channel.UNLIMITED)
        items.forEach { queue.send(it) }
        queue.close()

        _progress.update {
            SyncProgress(isActive = true, operation = SyncOperation.UPLOADING, itemsTotal = items.size)
        }

        val workers = (1..UPLOAD_CONCURRENCY).map {
            engineScope.launch {
                for (entity in queue) {
                    processUpload(entity, token, chatId)
                }
            }
        }
        workers.joinAll()
        backupIndexManager.exportAndUpload(token, chatId).getOrNull()?.let { fileId ->
            credentialRepository.setLastIndexFileId(fileId)
        }
        _progress.update { it.copy(isActive = false, operation = SyncOperation.IDLE) }
    }

    private suspend fun processUpload(
        entity: com.ulap.data.local.entity.MediaItemEntity,
        token: String,
        chatId: String,
    ) {
        try {
            processUploadInternal(entity, token, chatId)
        } catch (e: Exception) {
            // Catch anything that escaped (e.g. OkHttp re-trying on a consumed stream,
            // unexpected I/O errors) so the worker coroutine stays alive for the next item.
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
        entity: com.ulap.data.local.entity.MediaItemEntity,
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

        val uploadStream = if (isVideo && willChunk) {
            tempRaw = File(appContext.cacheDir, "ulap_raw_${entity.id.hashCode()}.mp4")
            tempFastStarted = File(appContext.cacheDir, "ulap_fs_${entity.id.hashCode()}.mp4")
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

        val result = uploadStream.use {
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
            )
        }
        tempRaw?.delete()
        tempFastStarted?.delete()

        when (result) {
            is UploadResult.Success -> {
                mediaItemDao.updateBackupResult(
                    id = entity.id,
                    status = BackupStatus.BACKED_UP,
                    error = null,
                    syncedAt = System.currentTimeMillis(),
                    fileId = result.fileId,
                    messageId = result.messageId,
                    thumbnailFileId = result.thumbnailFileId,
                )
            }
            is UploadResult.Error -> {
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
        val token = credentialRepository.getBotToken() ?: return
        val items = mediaItemDao.getPendingOrFailed()
            .filter { it.backupStatus == BackupStatus.BACKED_UP }
            .filter { it.telegramFileId != null }

        if (items.isEmpty()) {
            val backedUp = withContext(Dispatchers.IO) {
                mediaItemDao.observeByStatus(BackupStatus.BACKED_UP)
            }
            return
        }

        val queue = Channel<com.ulap.data.local.entity.MediaItemEntity>(Channel.UNLIMITED)
        val backedUpItems = mediaItemDao.let {
            val flow = it.observeByStatus(BackupStatus.BACKED_UP)
            emptyList<com.ulap.data.local.entity.MediaItemEntity>()
        }

        _progress.update {
            SyncProgress(isActive = true, operation = SyncOperation.DOWNLOADING, itemsTotal = 0)
        }

        val workers = (1..DOWNLOAD_CONCURRENCY).map {
            engineScope.launch {
                for (entity in queue) {
                    processDownload(entity, token)
                }
            }
        }
        queue.close()
        workers.joinAll()
        _progress.update { it.copy(isActive = false, operation = SyncOperation.IDLE) }
    }

    suspend fun downloadItem(item: MediaItem) {
        val token = credentialRepository.getBotToken() ?: return
        val entity = mediaItemDao.findById(item.id) ?: return
        processDownload(entity, token)
    }

    private suspend fun processDownload(
        entity: com.ulap.data.local.entity.MediaItemEntity,
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
