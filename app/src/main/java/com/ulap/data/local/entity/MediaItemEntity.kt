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
)
