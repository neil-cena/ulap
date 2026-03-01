package com.ulap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaItemEntity)

    @Update
    suspend fun update(item: MediaItemEntity)

    @Query(
        """
        SELECT * FROM media_items
        WHERE bucketName IN (:buckets)
        ORDER BY dateTaken DESC
        """
    )
    fun observeByBuckets(buckets: List<String>): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items ORDER BY dateTaken DESC")
    fun observeAll(): Flow<List<MediaItemEntity>>

    @Query(
        """
        SELECT * FROM media_items
        WHERE backupStatus = :status
        ORDER BY dateTaken DESC
        """
    )
    fun observeByStatus(status: BackupStatus): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun findById(id: String): MediaItemEntity?

    @Query(
        """
        SELECT * FROM media_items
        WHERE backupStatus IN ('PENDING', 'FAILED')
        ORDER BY dateTaken DESC
        """
    )
    suspend fun getPendingOrFailed(): List<MediaItemEntity>

    @Query(
        """
        SELECT * FROM media_items
        WHERE backupStatus IN ('PENDING', 'FAILED')
        AND bucketName IN (:buckets)
        ORDER BY dateTaken DESC
        """
    )
    suspend fun getPendingOrFailedInBuckets(buckets: List<String>): List<MediaItemEntity>

    @Query(
        """
        UPDATE media_items
        SET backupStatus = 'EXCLUDED', errorMessage = 'Folder disabled'
        WHERE backupStatus IN ('PENDING', 'FAILED', 'UPLOADING')
        AND bucketName NOT IN (:enabledBuckets)
        """
    )
    suspend fun excludeItemsNotInBuckets(enabledBuckets: List<String>)

    @Query(
        """
        UPDATE media_items
        SET backupStatus = :status, errorMessage = :error, lastSyncedAt = :syncedAt,
            telegramFileId = :fileId, telegramMessageId = :messageId,
            thumbnailFileId = :thumbnailFileId, thumbnailMessageId = :thumbnailMessageId,
            chunkMessageIds = :chunkMessageIds
        WHERE id = :id
        """
    )
    suspend fun updateBackupResult(
        id: String,
        status: BackupStatus,
        error: String?,
        syncedAt: Long?,
        fileId: String?,
        messageId: Long?,
        thumbnailFileId: String?,
        thumbnailMessageId: Long? = null,
        chunkMessageIds: String? = null,
    )

    @Query("UPDATE media_items SET backupStatus = 'PENDING', errorMessage = NULL WHERE backupStatus = 'FAILED'")
    suspend fun resetFailedToPending()

    @Query("UPDATE media_items SET backupStatus = 'PENDING', errorMessage = NULL WHERE backupStatus = 'UPLOADING'")
    suspend fun resetStaleUploadingToPending()

    /** Mark items with chunked file_id (JSON array) as FAILED so they can be re-uploaded with 19MB chunks (streamable). */
    @Query(
        """
        UPDATE media_items
        SET backupStatus = 'FAILED', errorMessage = 'Re-upload required (playback fix)',
            telegramFileId = NULL, telegramMessageId = NULL, lastSyncedAt = NULL
        WHERE backupStatus = 'BACKED_UP'
        AND telegramFileId LIKE '[%'
        """
    )
    suspend fun markOversizedChunkedItemsAsFailed()

    @Query(
        """
        SELECT COUNT(*) FROM media_items
        WHERE bucketName = :bucket AND backupStatus = 'BACKED_UP'
        """
    )
    suspend fun countBackedUp(bucket: String): Int

    @Query(
        """
        SELECT * FROM media_items
        WHERE bucketName = :bucket AND dateModified > :since
        """
    )
    suspend fun getModifiedSince(bucket: String, since: Long): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE backupStatus = :status")
    fun countByStatus(status: BackupStatus): Flow<Int>

    @Query(
        """
        SELECT * FROM media_items
        WHERE mediaType = :type
        ORDER BY dateTaken DESC
        """
    )
    fun observeByMediaType(type: MediaType): Flow<List<MediaItemEntity>>

    @Query(
        """
        SELECT * FROM media_items
        WHERE backupStatus = 'BACKED_UP'
        AND telegramFileId IS NOT NULL
        ORDER BY dateTaken DESC
        """
    )
    suspend fun getAllBackedUp(): List<MediaItemEntity>

    @Query(
        """
        SELECT * FROM media_items
        WHERE backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
        AND telegramFileId IS NOT NULL
        ORDER BY dateTaken DESC
        """
    )
    suspend fun getAllIndexedItems(): List<MediaItemEntity>

    @Query(
        """
        SELECT * FROM media_items
        WHERE backupStatus = 'CLOUD_ONLY'
        AND telegramFileId IS NOT NULL
        ORDER BY dateTaken DESC
        """
    )
    suspend fun getAllCloudOnlyItems(): List<MediaItemEntity>

    @Query(
        """
        SELECT * FROM media_items
        WHERE fileName = :fileName AND size = :size AND dateTaken = :dateTaken
        LIMIT 1
        """
    )
    suspend fun findByFileNameSizeDate(fileName: String, size: Long, dateTaken: Long): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE telegramFileId = :fileId LIMIT 1")
    suspend fun findByTelegramFileId(fileId: String): MediaItemEntity?

    @Query("UPDATE media_items SET uploadedChunks = :chunks, uploadedChunkCount = :count WHERE id = :id")
    suspend fun saveChunkProgress(id: String, chunks: String, count: Int)

    @Query("UPDATE media_items SET uploadedChunks = NULL, uploadedChunkCount = 0 WHERE id = :id")
    suspend fun clearChunkProgress(id: String)

    // Only clears terminal states (BACKED_UP/EXCLUDED/CLOUD_ONLY).
    // PENDING and FAILED items keep their chunk state so a retry can resume from where it left off.
    @Query("""
        UPDATE media_items SET uploadedChunks = NULL, uploadedChunkCount = 0
        WHERE uploadedChunkCount > 0 AND backupStatus IN ('BACKED_UP', 'EXCLUDED', 'CLOUD_ONLY')
    """)
    suspend fun clearOrphanedChunkProgress()

    @Query("""
        SELECT telegramMessageId FROM media_items
        WHERE telegramMessageId IS NOT NULL
        AND telegramMessageId != 0
        AND backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
    """)
    suspend fun getAllBackupMessageIds(): List<Long>

    @Query("""
        SELECT thumbnailMessageId FROM media_items
        WHERE thumbnailMessageId IS NOT NULL
        AND thumbnailMessageId != 0
        AND backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
    """)
    suspend fun getAllThumbnailMessageIds(): List<Long>

    @Query("""
        SELECT chunkMessageIds FROM media_items
        WHERE chunkMessageIds IS NOT NULL
        AND backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
    """)
    suspend fun getAllChunkMessageIdsJson(): List<String>

    @Query("""
        UPDATE media_items
        SET backupStatus = 'PENDING',
            errorMessage = NULL,
            telegramFileId = NULL,
            telegramMessageId = NULL,
            thumbnailFileId = NULL,
            thumbnailMessageId = NULL,
            chunkMessageIds = NULL,
            uploadedChunks = NULL,
            uploadedChunkCount = 0,
            lastSyncedAt = NULL
        WHERE backupStatus = 'BACKED_UP'
    """)
    suspend fun resetBackedUpToPending()

    @Query("DELETE FROM media_items WHERE backupStatus = 'CLOUD_ONLY'")
    suspend fun deleteCloudOnlyItems()
}
