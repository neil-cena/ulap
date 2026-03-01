package com.ulap.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaType { IMAGE, VIDEO }

enum class BackupStatus { PENDING, UPLOADING, BACKED_UP, FAILED, EXCLUDED, CLOUD_ONLY }

@Entity(
    tableName = "media_items",
    indices = [
        Index("backupStatus"),
        Index("bucketName"),
        Index("dateTaken"),
        Index("mediaType"),
    ]
)
data class MediaItemEntity(
    @PrimaryKey val id: String,
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
    val thumbnailFileId: String? = null,
    val thumbnailMessageId: Long? = null,
    val uploadedChunks: String? = null,    // JSON array of completed chunk file_ids
    val uploadedChunkCount: Int = 0,       // count of entries; 0 = no resume state
    val chunkMessageIds: String? = null,   // JSON array of Telegram message IDs for each chunk
)
