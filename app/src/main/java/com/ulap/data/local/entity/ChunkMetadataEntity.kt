package com.ulap.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ChunkStatus { PENDING, UPLOADED }

/**
 * Represents a single 19MB chunk of a large media file that has been uploaded to Telegram.
 *
 * Replaces the legacy JSON-encoded chunk arrays (uploadedChunks / chunkMessageIds) stored
 * directly on MediaItemEntity. Provides indexed lookup by mediaItemId and chunkIndex, and
 * stores byteOffset + byteLength for O(log n) seek-to-chunk mapping during playback.
 */
@Entity(
    tableName = "chunk_metadata",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["mediaItemId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["mediaItemId", "chunkIndex"], unique = true),
        Index(value = ["mediaItemId"]),
    ]
)
data class ChunkMetadataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaItemId: String,
    val chunkIndex: Int,
    val telegramFileId: String,
    val telegramMessageId: Long,
    val byteOffset: Long,
    val byteLength: Int,
    val status: ChunkStatus = ChunkStatus.UPLOADED,
)
