package com.ulap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ulap.data.local.entity.ChunkMetadataEntity

@Dao
interface ChunkMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: ChunkMetadataEntity)

    /** All chunks for a media item, ordered by chunkIndex. Used for download and seek mapping. */
    @Query("SELECT * FROM chunk_metadata WHERE mediaItemId = :mediaItemId ORDER BY chunkIndex ASC")
    suspend fun getChunksForMedia(mediaItemId: String): List<ChunkMetadataEntity>

    /** Number of successfully uploaded chunks. Used as the resume checkpoint. */
    @Query("SELECT COUNT(*) FROM chunk_metadata WHERE mediaItemId = :mediaItemId AND status = 'UPLOADED'")
    suspend fun getUploadedCount(mediaItemId: String): Int

    /** Ordered list of Telegram file_ids for all chunks. Used to build download URLs. */
    @Query("SELECT telegramFileId FROM chunk_metadata WHERE mediaItemId = :mediaItemId ORDER BY chunkIndex ASC")
    suspend fun getAllFileIdsForMedia(mediaItemId: String): List<String>

    /** Ordered list of Telegram message IDs. Used for batch deletion. */
    @Query("SELECT telegramMessageId FROM chunk_metadata WHERE mediaItemId = :mediaItemId ORDER BY chunkIndex ASC")
    suspend fun getAllMessageIdsForMedia(mediaItemId: String): List<Long>

    /**
     * Returns the chunk that contains the given byte offset.
     * Finds the highest chunkIndex whose byteOffset is <= the requested offset.
     */
    @Query("""
        SELECT * FROM chunk_metadata
        WHERE mediaItemId = :mediaItemId AND byteOffset <= :byteOffset
        ORDER BY byteOffset DESC
        LIMIT 1
    """)
    suspend fun getChunkAtByteOffset(mediaItemId: String, byteOffset: Long): ChunkMetadataEntity?

    /** Deletes all chunk records for a media item (called on delete or re-upload). */
    @Query("DELETE FROM chunk_metadata WHERE mediaItemId = :mediaItemId")
    suspend fun deleteChunksForMedia(mediaItemId: String)

    /** Returns true if any chunk rows exist for this media item (used to detect new vs legacy items). */
    @Query("SELECT COUNT(*) FROM chunk_metadata WHERE mediaItemId = :mediaItemId LIMIT 1")
    suspend fun hasChunks(mediaItemId: String): Int

    /**
     * Updates the [telegramFileId] for a single chunk row after a re-forward repair.
     * Identified by the chunk's primary key [id].
     */
    @Query("UPDATE chunk_metadata SET telegramFileId = :newFileId WHERE id = :id")
    suspend fun updateChunkFileId(id: Long, newFileId: String)
}
