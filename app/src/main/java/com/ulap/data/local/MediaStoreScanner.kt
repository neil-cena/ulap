package com.ulap.data.local // null mimeType guard

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceFolder(
    val bucketName: String,
    val displayName: String,
    val imageCount: Int,
    val videoCount: Int,
) {
    val totalCount: Int get() = imageCount + videoCount
}

@Singleton
class MediaStoreScanner @Inject constructor(
    private val contentResolver: ContentResolver,
) {

    suspend fun scanFolders(): List<DeviceFolder> = withContext(Dispatchers.IO) {
        val folderMap = mutableMapOf<String, DeviceFolder>()
        scanFoldersByType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE, folderMap)
        scanFoldersByType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO, folderMap)
        folderMap.values.sortedBy { it.displayName }
    }

    private fun scanFoldersByType(
        uri: Uri,
        type: MediaType,
        folderMap: MutableMap<String, DeviceFolder>,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val bucketIdIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdIdx) ?: continue
                val bucketName = cursor.getString(bucketNameIdx) ?: bucketId
                val existing = folderMap[bucketId]
                folderMap[bucketId] = when (type) {
                    MediaType.IMAGE -> DeviceFolder(
                        bucketName = bucketId,
                        displayName = bucketName,
                        imageCount = (existing?.imageCount ?: 0) + 1,
                        videoCount = existing?.videoCount ?: 0,
                    )
                    MediaType.VIDEO -> DeviceFolder(
                        bucketName = bucketId,
                        displayName = bucketName,
                        imageCount = existing?.imageCount ?: 0,
                        videoCount = (existing?.videoCount ?: 0) + 1,
                    )
                }
            }
        }
    }

    suspend fun scanMedia(
        enabledBuckets: List<String>,
        sinceModified: Long = 0L,
    ): List<MediaItemEntity> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MediaItemEntity>()
        results += scanByType(
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaType = MediaType.IMAGE,
            enabledBuckets = enabledBuckets,
            sinceModified = sinceModified,
        )
        results += scanByType(
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            mediaType = MediaType.VIDEO,
            enabledBuckets = enabledBuckets,
            sinceModified = sinceModified,
        )
        results
    }

    private fun scanByType(
        uri: Uri,
        mediaType: MediaType,
        enabledBuckets: List<String>,
        sinceModified: Long,
    ): List<MediaItemEntity> {
        if (enabledBuckets.isEmpty()) return emptyList()

        val projection = buildProjection(mediaType)
        val bucketPlaceholders = enabledBuckets.joinToString(",") { "?" }
        val selection = buildString {
            append("${MediaStore.MediaColumns.BUCKET_ID} IN ($bucketPlaceholders)")
            if (sinceModified > 0) {
                append(" AND ${MediaStore.MediaColumns.DATE_MODIFIED} > ?")
            }
        }
        val selectionArgs = if (sinceModified > 0) {
            (enabledBuckets + (sinceModified / 1000).toString()).toTypedArray()
        } else {
            enabledBuckets.toTypedArray()
        }

        val results = mutableListOf<MediaItemEntity>()
        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                cursorToEntity(cursor, uri, mediaType)?.let { results += it }
            }
        }
        return results
    }

    private fun buildProjection(mediaType: MediaType): Array<String> {
        val base = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
        )
        return if (mediaType == MediaType.VIDEO) {
            base + MediaStore.Video.VideoColumns.DURATION
        } else base
    }

    private fun cursorToEntity(cursor: Cursor, baseUri: Uri, mediaType: MediaType): MediaItemEntity? {
        return try {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val contentUri = ContentUris.withAppendedId(baseUri, id).toString()
            val fileName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: return null
            val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: return null
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
            val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)) * 1000L
            val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN))
            val bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)) ?: return null
            val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)) ?: contentUri
            val durationMs = if (mediaType == MediaType.VIDEO) {
                val col = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                if (col != -1) cursor.getLong(col).takeIf { it > 0 } else null
            } else null

            MediaItemEntity(
                id = "${baseUri.pathSegments.last()}_$id",
                path = path,
                contentUri = contentUri,
                fileName = fileName,
                mimeType = mimeType,
                size = size,
                dateModified = dateModified,
                dateTaken = dateTaken.takeIf { it > 0 } ?: dateModified,
                bucketName = bucketName,
                mediaType = mediaType,
                durationMs = durationMs,
                backupStatus = BackupStatus.PENDING,
            )
        } catch (e: Exception) {
            null
        }
    }
}
