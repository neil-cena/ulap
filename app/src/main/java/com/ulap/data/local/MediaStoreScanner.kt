package com.ulap.data.local

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

/**
 * Result of [MediaStoreScanner.scanMedia]. [mediaStoreQueriesSucceeded] is false if a
 * [ContentResolver.query] returned null, or a row threw while parsing — do not apply [items]
 * to the database or advance scan watermarks.
 */
data class MediaScanOutcome(
    val items: List<MediaItemEntity>,
    val mediaStoreQueriesSucceeded: Boolean,
)

/**
 * Result of [MediaStoreScanner.scanFolders]. If [mediaStoreQueriesSucceeded] is false, do not
 * overwrite folder rows (same contract as [MediaScanOutcome]).
 */
data class FolderScanOutcome(
    val folders: List<DeviceFolder>,
    val mediaStoreQueriesSucceeded: Boolean,
)

@Singleton
class MediaStoreScanner @Inject constructor(
    private val contentResolver: ContentResolver,
) {

    suspend fun scanFolders(): FolderScanOutcome = withContext(Dispatchers.IO) {
        val folderMap = mutableMapOf<String, DeviceFolder>()
        val imagesOk = scanFoldersByType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE, folderMap)
        val videosOk = scanFoldersByType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO, folderMap)
        FolderScanOutcome(
            folders = folderMap.values.sortedBy { it.displayName },
            mediaStoreQueriesSucceeded = imagesOk && videosOk,
        )
    }

    /** @return false if [ContentResolver.query] returned null */
    private fun scanFoldersByType(
        uri: Uri,
        type: MediaType,
        folderMap: MutableMap<String, DeviceFolder>,
    ): Boolean {
        val projection = arrayOf(
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        val cursor = contentResolver.query(uri, projection, null, null, null) ?: return false
        cursor.use { c ->
            val bucketIdIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val bucketId = c.getString(bucketIdIdx) ?: continue
                val bucketName = c.getString(bucketNameIdx) ?: bucketId
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
        return true
    }

    suspend fun scanMedia(
        enabledBuckets: List<String>,
        sinceModified: Long = 0L,
    ): MediaScanOutcome = withContext(Dispatchers.IO) {
        val images = scanByType(
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaType = MediaType.IMAGE,
            enabledBuckets = enabledBuckets,
            sinceModified = sinceModified,
        )
        val videos = scanByType(
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            mediaType = MediaType.VIDEO,
            enabledBuckets = enabledBuckets,
            sinceModified = sinceModified,
        )
        MediaScanOutcome(
            items = images.first + videos.first,
            mediaStoreQueriesSucceeded = images.second && videos.second,
        )
    }

    private fun scanByType(
        uri: Uri,
        mediaType: MediaType,
        enabledBuckets: List<String>,
        sinceModified: Long,
    ): Pair<List<MediaItemEntity>, Boolean> {
        if (enabledBuckets.isEmpty()) return Pair(emptyList(), true)

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
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
            ?: return Pair(emptyList(), false)
        var rowParseFailed = false
        cursor.use {
            while (it.moveToNext()) {
                try {
                    cursorToEntity(it, uri, mediaType)?.let { row -> results += row }
                } catch (_: RuntimeException) {
                    rowParseFailed = true
                }
            }
        }
        return Pair(results, !rowParseFailed)
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
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )
        return if (mediaType == MediaType.VIDEO) {
            base + MediaStore.Video.VideoColumns.DURATION
        } else base
    }

    /**
     * Returns null if required fields are missing (skip row without failing the scan).
     * Unexpected cursor errors propagate to [scanByType] and mark the scan as failed.
     */
    private fun cursorToEntity(cursor: Cursor, baseUri: Uri, mediaType: MediaType): MediaItemEntity? {
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

        val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val widthPx = if (widthCol != -1) cursor.getInt(widthCol).takeIf { it > 0 } else null
        val heightPx = if (heightCol != -1) cursor.getInt(heightCol).takeIf { it > 0 } else null

        return MediaItemEntity(
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
            widthPx = widthPx,
            heightPx = heightPx,
        )
    }
}
