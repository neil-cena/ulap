package com.ulap.domain.model

data class SyncProgress(
    val isActive: Boolean = false,
    val operation: SyncOperation = SyncOperation.IDLE,
    val currentItem: MediaItem? = null,
    val currentFileName: String = "",
    val currentFileBytes: Long = 0L,
    val currentFileBytesTotal: Long = 0L,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val bytesTransferred: Long = 0L,
    val currentSpeedBps: Long = 0L,
    val failedItems: List<MediaItem> = emptyList(),
) {
    val progressFraction: Float get() = if (itemsTotal == 0) 0f else itemsDone.toFloat() / itemsTotal
    val currentFileFraction: Float get() = if (currentFileBytesTotal == 0L) 0f
        else (currentFileBytes.toFloat() / currentFileBytesTotal).coerceIn(0f, 1f)
}

enum class SyncOperation { IDLE, UPLOADING, DOWNLOADING }
