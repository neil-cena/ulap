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

data class BackupStatsRow(
    val backupStatus: BackupStatus,
    val count: Int,
    val totalSize: Long,
)

@Dao
interface MediaItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaItemEntity)

    @Update
    suspend fun update(item: MediaItemEntity)

    /**
     * Batch UPDATE for existing rows. Use this instead of [upsertAll] when the rows already
     * exist in the DB, to avoid the DELETE+INSERT cycle that OnConflictStrategy.REPLACE performs.
     * That DELETE triggers the ForeignKey.CASCADE on chunk_metadata, wiping all chunk rows.
     */
    @Update
    suspend fun updateAll(items: List<MediaItemEntity>)

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

    /**
     * Client-side import deduplication: same display name and MIME type, and when [widthPx]/[heightPx]
     * are non-null, same pixel dimensions (stronger than filename-only).
     * Matches when either side has NULL dimensions (treat unknown as wildcard) or both match exactly.
     */
    @Query(
        """
        SELECT COUNT(*) FROM media_items
        WHERE fileName = :fileName
        AND mimeType = :mimeType
        AND backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
        AND (
            (:widthPx IS NULL AND :heightPx IS NULL)
            OR (width_px IS NULL AND height_px IS NULL)
            OR (width_px = :widthPx AND height_px = :heightPx)
        )
        """,
    )
    suspend fun countItemsMatchingImportFingerprint(
        fileName: String,
        mimeType: String,
        widthPx: Int?,
        heightPx: Int?,
    ): Int

    /**
     * Returns an already backed-up item with the same filename+mimeType fingerprint, for
     * cross-source dedup (e.g. device backup finding a Google Photos import, or vice versa).
     * Same NULL-dimension wildcard logic as [countItemsMatchingImportFingerprint].
     */
    @Query(
        """
        SELECT * FROM media_items
        WHERE fileName = :fileName
        AND mimeType = :mimeType
        AND backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
        AND telegramFileId IS NOT NULL
        AND id != :excludeId
        AND (
            (:widthPx IS NULL AND :heightPx IS NULL)
            OR (width_px IS NULL AND height_px IS NULL)
            OR (width_px = :widthPx AND height_px = :heightPx)
        )
        LIMIT 1
        """,
    )
    suspend fun findBackedUpByImportFingerprint(
        fileName: String,
        mimeType: String,
        widthPx: Int?,
        heightPx: Int?,
        excludeId: String,
    ): MediaItemEntity?

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
        SET backupStatus = 'EXCLUDED', errorMessage = :message
        WHERE id IN (:ids)
        AND backupStatus IN ('PENDING', 'FAILED')
        """
    )
    suspend fun markExcludedNotOnDevice(ids: List<String>, message: String)

    @Query(
        """
        UPDATE media_items
        SET backupStatus = 'EXCLUDED', errorMessage = 'Folder disabled'
        WHERE backupStatus IN ('PENDING', 'FAILED', 'UPLOADING')
        AND bucketName NOT IN (:enabledBuckets)
        AND bucketName != 'Google Photos'
        """
    )
    suspend fun excludeItemsNotInBuckets(enabledBuckets: List<String>)

    @Query(
        """
        UPDATE media_items
        SET backupStatus = :status, errorMessage = :error, lastSyncedAt = :syncedAt,
            telegramFileId = :fileId, telegramMessageId = :messageId,
            thumbnailFileId = :thumbnailFileId, thumbnailMessageId = :thumbnailMessageId,
            chunkMessageIds = :chunkMessageIds, contentHash = :contentHash,
            uploadBotIndex = :uploadBotIndex
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
        contentHash: String? = null,
        uploadBotIndex: Int = 0,
    )

    @Query("UPDATE media_items SET backupStatus = 'PENDING', errorMessage = NULL WHERE backupStatus = 'FAILED'")
    suspend fun resetFailedToPending()

    @Query("UPDATE media_items SET backupStatus = 'PENDING', errorMessage = NULL WHERE backupStatus = 'UPLOADING'")
    suspend fun resetStaleUploadingToPending()

    @Query("UPDATE media_items SET backupStatus = 'PENDING', errorMessage = NULL WHERE id = :id")
    suspend fun resetItemToPending(id: String)

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

    /** Mark BACKED_UP chunked items whose first chunk was uploaded with the old large size so they re-upload with the fast-start layout. */
    @Query(
        """
        UPDATE media_items
        SET backupStatus = 'FAILED', errorMessage = 'Re-upload required (fast-start)',
            telegramFileId = NULL, telegramMessageId = NULL, lastSyncedAt = NULL
        WHERE backupStatus = 'BACKED_UP'
        AND telegramFileId LIKE 'chunked:%'
        AND id IN (
            SELECT mediaItemId FROM chunk_metadata
            WHERE chunkIndex = 0 AND byteLength > 524288
        )
        """
    )
    suspend fun markSlowStartChunkedItemsAsFailed()

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

    @Query("SELECT COALESCE(SUM(size), 0) FROM media_items WHERE backupStatus = :status")
    fun sumSizeByStatus(status: BackupStatus): Flow<Long>

    @Query("""
        SELECT backupStatus, COUNT(*) as count, COALESCE(SUM(size), 0) as totalSize
        FROM media_items
        GROUP BY backupStatus
    """)
    fun observeBackupStatsGrouped(): Flow<List<BackupStatsRow>>

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
        UPDATE media_items
        SET backupStatus = 'CLOUD_ONLY', path = '', contentUri = ''
        WHERE id IN (:ids)
        """
    )
    suspend fun markAsCloudOnly(ids: List<String>)

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

    @Query(
        """
        SELECT * FROM media_items
        WHERE contentHash = :hash
        AND backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
        AND telegramFileId IS NOT NULL
        LIMIT 1
        """
    )
    suspend fun findByContentHash(hash: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE telegramFileId = :fileId LIMIT 1")
    suspend fun findByTelegramFileId(fileId: String): MediaItemEntity?

    @Query("UPDATE media_items SET uploadBotIndex = :uploadBotIndex WHERE telegramFileId = :telegramFileId")
    suspend fun updateUploadBotIndexByFileId(telegramFileId: String, uploadBotIndex: Int)

    @Query("SELECT * FROM media_items WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<MediaItemEntity>

    @Query("SELECT telegramFileId FROM media_items WHERE telegramFileId IN (:fileIds)")
    suspend fun findExistingTelegramFileIds(fileIds: List<String>): List<String>

    @Query("SELECT id FROM media_items WHERE id IN (:ids)")
    suspend fun findExistingIds(ids: List<String>): List<String>

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

    // ── Bot repair queries ────────────────────────────────────────────────────

    /**
     * Returns all backed-up or cloud-only items that were uploaded by the given [botIndex].
     * Used to enumerate which items need re-forwarding when that bot is banned.
     */
    @Query("""
        SELECT * FROM media_items
        WHERE uploadBotIndex = :botIndex
        AND backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
        AND telegramFileId IS NOT NULL
        ORDER BY dateTaken DESC
    """)
    suspend fun getBackedUpItemsByBotIndex(botIndex: Int): List<MediaItemEntity>

    /**
     * Updates [telegramFileId], [thumbnailFileId], and [uploadBotIndex] for a repaired item.
     * Only touches repair-relevant columns to avoid clobbering other fields.
     */
    @Query("""
        UPDATE media_items
        SET telegramFileId = :telegramFileId,
            thumbnailFileId = :thumbnailFileId,
            uploadBotIndex = :uploadBotIndex
        WHERE id = :id
    """)
    suspend fun updateRepairResult(
        id: String,
        telegramFileId: String,
        thumbnailFileId: String?,
        uploadBotIndex: Int,
    )

    /**
     * Remaps [uploadBotIndex] values after a bot promotion:
     *  - Items belonging to the promoted alt bot → index 0 (new primary).
     *  - Items belonging to the banned primary   → -1 (sentinel: needs repair via re-forward).
     *  - Items belonging to bots with index > [promotedAltIndex] → decremented by 1 (index compaction).
     *
     * [bannedPrimaryIndex] is always 0 in the current promotion flow; kept explicit for clarity.
     */
    @Query("""
        UPDATE media_items
        SET uploadBotIndex = CASE
            WHEN uploadBotIndex = :promotedAltIndex THEN 0
            WHEN uploadBotIndex = :bannedPrimaryIndex THEN -1
            WHEN uploadBotIndex > :promotedAltIndex THEN uploadBotIndex - 1
            ELSE uploadBotIndex
        END
        WHERE uploadBotIndex IN (:affectedIndices)
    """)
    suspend fun remapBotIndices(
        bannedPrimaryIndex: Int,
        promotedAltIndex: Int,
        affectedIndices: List<Int>,
    )

    /**
     * Records that a repair attempt could not restore this item (original message missing or
     * chunk metadata absent). Annotates the errorMessage without changing backupStatus or
     * file references so the item remains visible in the gallery.
     */
    @Query("UPDATE media_items SET errorMessage = :reason WHERE id = :id")
    suspend fun markRepairItemNeedsReupload(id: String, reason: String)

    /**
     * Marks corrupt chunked items (backed-up but with no chunk_metadata rows) as FAILED so
     * the next sync pipeline re-uploads them and recreates the chunk_metadata rows.
     * Returns the count of items marked.
     */
    @Query(
        """
        UPDATE media_items
        SET backupStatus = 'FAILED', errorMessage = 'Re-upload required (chunk metadata missing)'
        WHERE telegramFileId LIKE 'chunked:%'
        AND backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
        AND NOT EXISTS (SELECT 1 FROM chunk_metadata c WHERE c.mediaItemId = id)
        """,
    )
    suspend fun markCorruptChunkedItemsForReupload(): Int

    /**
     * Chunked backups use `telegramFileId` sentinel `chunked:…` with real part IDs in `chunk_metadata`.
     * Rows matching this query lost chunk rows (e.g. legacy bug) and cannot play or export correctly.
     */
    @Query(
        """
        SELECT COUNT(*) FROM media_items mi
        WHERE mi.telegramFileId LIKE 'chunked:%'
        AND mi.backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
        AND NOT EXISTS (SELECT 1 FROM chunk_metadata c WHERE c.mediaItemId = mi.id)
        """,
    )
    fun observeCorruptChunkedBackupCount(): Flow<Int>

    /**
     * Chunked rows missing [com.ulap.data.local.entity.ChunkMetadataEntity] (same predicate as [observeCorruptChunkedBackupCount]).
     */
    @Query(
        """
        SELECT * FROM media_items mi
        WHERE mi.telegramFileId LIKE 'chunked:%'
        AND mi.backupStatus IN ('BACKED_UP', 'CLOUD_ONLY')
        AND NOT EXISTS (SELECT 1 FROM chunk_metadata c WHERE c.mediaItemId = mi.id)
        """,
    )
    suspend fun getCorruptChunkedBackedUpItems(): List<MediaItemEntity>
}
