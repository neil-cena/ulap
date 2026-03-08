package com.ulap.domain.model

import com.ulap.data.remote.ThrottleReason

/**
 * Progress snapshot for a single file being uploaded concurrently.
 * Keyed by entity.id in SyncProgress.activeUploads.
 */
data class FileUploadProgress(
    val fileName: String,
    val bytesUploaded: Long = 0L,
    val bytesTotal: Long,
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val chunkRetryAttempt: Int = 0,
    val uploadSpeedBps: Long = 0L,
    val estimatedRemainingMs: Long = 0L,
) {
    val fraction: Float get() = if (bytesTotal == 0L) 0f
        else (bytesUploaded.toFloat() / bytesTotal).coerceIn(0f, 1f)
    val isChunked: Boolean get() = totalChunks > 1
}

data class SyncProgress(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val operation: SyncOperation = SyncOperation.IDLE,
    val currentItem: MediaItem? = null,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val bytesTransferred: Long = 0L,
    val currentSpeedBps: Long = 0L,
    val failedItems: List<MediaItem> = emptyList(),
    val isRateLimited: Boolean = false,
    val throttleReason: ThrottleReason = ThrottleReason.NONE,
    /** Wall-clock ms when normal speed is expected to resume; 0 if unknown or not throttled. */
    val throttleResumeAtMs: Long = 0L,
    val completionEvent: BackupCompletionEvent? = null,
    /** All currently active concurrent uploads, keyed by entity.id. */
    val activeUploads: Map<String, FileUploadProgress> = emptyMap(),
) {
    val progressFraction: Float get() = if (itemsTotal == 0) 0f else itemsDone.toFloat() / itemsTotal

    // Derived from the largest active upload — used by BackupForegroundService and legacy callers.
    private val primaryUpload: FileUploadProgress?
        get() = activeUploads.values.maxByOrNull { it.bytesTotal }

    val currentFileName: String get() = primaryUpload?.fileName ?: ""
    val currentFileBytes: Long get() = primaryUpload?.bytesUploaded ?: 0L
    val currentFileBytesTotal: Long get() = primaryUpload?.bytesTotal ?: 0L
    val currentFileFraction: Float get() = primaryUpload?.fraction ?: 0f
    val totalChunks: Int get() = primaryUpload?.totalChunks ?: 0
    val currentChunk: Int get() = primaryUpload?.currentChunk ?: 0
    val chunkRetryAttempt: Int get() = primaryUpload?.chunkRetryAttempt ?: 0
    val uploadSpeedBps: Long get() = primaryUpload?.uploadSpeedBps ?: 0L
    val estimatedRemainingMs: Long get() = primaryUpload?.estimatedRemainingMs ?: 0L
}

data class BackupCompletionEvent(
    val succeeded: Int,
    val failed: Int,
)

enum class SyncOperation { IDLE, UPLOADING, DOWNLOADING }
