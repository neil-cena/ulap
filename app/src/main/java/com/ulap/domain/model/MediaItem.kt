package com.ulap.domain.model

data class MediaItem(
    val id: String,
    val path: String,
    val contentUri: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val dateModified: Long,
    val dateTaken: Long,
    val bucketName: String,
    val mediaType: MediaType,
    val durationMs: Long? = null,
    val backupStatus: BackupStatus = BackupStatus.PENDING,
    val telegramFileId: String? = null,
    val telegramMessageId: Long? = null,
    val lastSyncedAt: Long? = null,
    val errorMessage: String? = null,
    /** Telegram file_id for a small thumbnail (used for grid). */
    val thumbnailFileId: String? = null,
    val uploadBotIndex: Int = 0,
    /** Resolved CDN URL for streaming (populated at runtime for CLOUD_ONLY items). */
    val streamUrl: String? = null,
)

enum class MediaType { IMAGE, VIDEO }

enum class BackupStatus {
    PENDING, UPLOADING, BACKED_UP, FAILED, EXCLUDED, CLOUD_ONLY;

    val isTerminal get() = this == BACKED_UP || this == EXCLUDED
}
