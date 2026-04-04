package com.ulap.data.googlephotos

import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import com.ulap.data.remote.CHUNKED_FILE_ID_PREFIX
import java.time.Instant
import java.time.format.DateTimeParseException

private const val GOOGLE_PHOTO_ID_PREFIX = "gphoto_"
private const val BUCKET_GOOGLE_PHOTOS = "Google Photos"

/** Builds [MediaItemEntity] rows for Google Photos → Telegram URL relay (no local bytes). */
internal object GooglePhotosImportEntityFactory {

    fun cloudEntityFromGooglePhoto(
        item: GooglePhotosMediaItem,
        telegramFileId: String,
        messageId: Long,
        remoteThumbnailUrl: String? = null,
    ): MediaItemEntity {
        val createdMs = parseCreationTime(item.mediaMetadata?.creationTime)
        val name = item.filename ?: item.id
        val (w, h) = item.mediaMetadata.pixelDimensions()
        return MediaItemEntity(
            id = "$GOOGLE_PHOTO_ID_PREFIX${item.id}",
            path = "",
            contentUri = "",
            fileName = name,
            mimeType = item.mimeType,
            size = 0L,
            dateModified = createdMs,
            dateTaken = createdMs,
            bucketName = BUCKET_GOOGLE_PHOTOS,
            mediaType = MediaType.IMAGE,
            durationMs = null,
            backupStatus = BackupStatus.CLOUD_ONLY,
            telegramFileId = telegramFileId,
            telegramMessageId = messageId,
            lastSyncedAt = null,
            errorMessage = null,
            remoteThumbnailUrl = remoteThumbnailUrl,
            widthPx = w,
            heightPx = h,
        )
    }

    /** Chunked video: [telegramFileId] is `chunked:<totalChunks>`; part file_ids live in [chunk_metadata]. */
    fun cloudVideoEntityChunked(
        item: GooglePhotosMediaItem,
        totalSizeBytes: Long,
        totalChunks: Int,
        lastChunkMessageId: Long,
        remoteThumbnailUrl: String? = null,
    ): MediaItemEntity {
        val createdMs = parseCreationTime(item.mediaMetadata?.creationTime)
        val name = item.filename ?: item.id
        val (w, h) = item.mediaMetadata.pixelDimensions()
        return MediaItemEntity(
            id = "$GOOGLE_PHOTO_ID_PREFIX${item.id}",
            path = "",
            contentUri = "",
            fileName = name,
            mimeType = item.mimeType,
            size = totalSizeBytes,
            dateModified = createdMs,
            dateTaken = createdMs,
            bucketName = BUCKET_GOOGLE_PHOTOS,
            mediaType = MediaType.VIDEO,
            durationMs = null,
            backupStatus = BackupStatus.CLOUD_ONLY,
            telegramFileId = "$CHUNKED_FILE_ID_PREFIX$totalChunks",
            telegramMessageId = lastChunkMessageId,
            lastSyncedAt = null,
            errorMessage = null,
            uploadedChunkCount = totalChunks,
            remoteThumbnailUrl = remoteThumbnailUrl,
            widthPx = w,
            heightPx = h,
        )
    }

    fun failedEntity(item: GooglePhotosMediaItem, errorMessage: String): MediaItemEntity {
        val createdMs = parseCreationTime(item.mediaMetadata?.creationTime)
        val name = item.filename ?: item.id
        val (w, h) = item.mediaMetadata.pixelDimensions()
        val base = item.baseUrl?.takeIf { it.isNotBlank() }
        val thumb = when {
            base == null -> null
            item.mimeType.startsWith("video/") -> GooglePhotosUrls.remoteThumbnailVideo(base)
            item.mimeType.startsWith("image/") -> GooglePhotosUrls.remoteThumbnailImage(base)
            else -> null
        }
        return MediaItemEntity(
            id = "$GOOGLE_PHOTO_ID_PREFIX${item.id}",
            path = "",
            contentUri = "",
            fileName = name,
            mimeType = item.mimeType,
            size = 0L,
            dateModified = createdMs,
            dateTaken = createdMs,
            bucketName = BUCKET_GOOGLE_PHOTOS,
            mediaType = if (item.mimeType.startsWith("video/")) MediaType.VIDEO else MediaType.IMAGE,
            durationMs = null,
            backupStatus = BackupStatus.FAILED,
            telegramFileId = null,
            telegramMessageId = null,
            lastSyncedAt = null,
            errorMessage = errorMessage,
            remoteThumbnailUrl = thumb,
            widthPx = w,
            heightPx = h,
        )
    }

    private fun parseCreationTime(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: DateTimeParseException) {
            System.currentTimeMillis()
        }
    }
}
