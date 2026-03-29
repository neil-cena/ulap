package com.ulap.domain.repository

import com.ulap.domain.model.BackupFolder
import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    fun observeTimeline(): Flow<List<MediaItem>>
    fun observeByFolder(bucketName: String): Flow<List<MediaItem>>
    fun observeByMediaType(type: MediaType): Flow<List<MediaItem>>
    fun observeBackupStats(): Flow<BackupStats>
    suspend fun scanAndSync(fullScan: Boolean = false)
    suspend fun getItemById(id: String): MediaItem?
    /** Returns all BACKED_UP items that have a local copy (non-empty contentUri). */
    suspend fun getBackedUpWithLocal(): List<MediaItem>
    /** Mark the given items as CLOUD_ONLY in the DB (caller has already deleted from MediaStore). */
    suspend fun markAsCloudOnly(ids: List<String>)
    /** Live stream of items in FAILED status. */
    fun observeFailedItems(): Flow<List<MediaItem>>

    /**
     * Count of backed-up items with `chunked:` sentinel but no `chunk_metadata` rows (broken chunked backup).
     */
    fun observeCorruptChunkedBackupCount(): Flow<Int>
}
