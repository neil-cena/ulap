package com.ulap.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ulap.data.local.db.UlapDatabase
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import com.ulap.data.remote.CHUNK_UPLOAD_SIZE
import com.ulap.data.remote.FAST_START_CHUNK_SIZE
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bug Reproduction Test — MediaItemDao.markSlowStartChunkedItemsAsFailed migration.
 *
 * ## Defect
 *
 * Videos uploaded before the fast-start feature was introduced have a first chunk of size
 * [CHUNK_UPLOAD_SIZE] (19 MB). These items can never benefit from fast playback start
 * because the player must download the entire 19 MB first chunk before seeking, whereas
 * the new format starts with a [FAST_START_CHUNK_SIZE] (512 KB) chunk.
 *
 * ## Required contract (this test encodes)
 *
 * A new `markSlowStartChunkedItemsAsFailed` method must be added to [MediaItemDao].
 * When called it must:
 *  - Find `media_items` rows where `backupStatus = 'BACKED_UP'` AND
 *    `telegramFileId LIKE 'chunked:%'` AND a `chunk_metadata` row exists for that item
 *    with `chunkIndex = 0` AND `byteLength > FAST_START_CHUNK_SIZE`.
 *  - For those rows, set `backupStatus = 'FAILED'`,
 *    `errorMessage = 'Re-upload required (fast-start)'`,
 *    `telegramFileId = NULL`, `telegramMessageId = NULL`, `lastSyncedAt = NULL`.
 *  - Leave all other rows untouched.
 *
 * ## Why this test FAILS against current code
 *
 * `MediaItemDao.markSlowStartChunkedItemsAsFailed` does not exist → compile error.
 * The test will pass once the method is added AND the SQL query implements the contract above.
 *
 * Deterministic: in-memory Room database, no network, no clocks, no randomness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemDaoSlowStartMigrationBrt {

    private lateinit var db: UlapDatabase
    private lateinit var mediaItemDao: MediaItemDao
    private lateinit var chunkMetadataDao: ChunkMetadataDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            UlapDatabase::class.java,
        ).allowMainThreadQueries().build()
        mediaItemDao = db.mediaItemDao()
        chunkMetadataDao = db.chunkMetadataDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private fun mediaItem(
        id: String,
        backupStatus: BackupStatus,
        telegramFileId: String?,
        telegramMessageId: Long? = 1001L,
        lastSyncedAt: Long? = 1_700_000_000_000L,
        errorMessage: String? = null,
    ) = MediaItemEntity(
        id = id,
        path = "/sdcard/DCIM/$id.mp4",
        contentUri = "content://media/external/video/media/$id",
        fileName = "$id.mp4",
        mimeType = "video/mp4",
        size = 50_000_000L,
        dateModified = 1_700_000_000L,
        dateTaken = 1_699_999_000L,
        bucketName = "Camera",
        mediaType = MediaType.VIDEO,
        backupStatus = backupStatus,
        telegramFileId = telegramFileId,
        telegramMessageId = telegramMessageId,
        lastSyncedAt = lastSyncedAt,
        errorMessage = errorMessage,
    )

    private fun chunk(
        mediaItemId: String,
        chunkIndex: Int,
        byteLength: Int,
    ) = ChunkMetadataEntity(
        mediaItemId = mediaItemId,
        chunkIndex = chunkIndex,
        telegramFileId = "tg-$mediaItemId-$chunkIndex",
        telegramMessageId = 2000L + chunkIndex,
        byteOffset = 0L,
        byteLength = byteLength,
    )

    // -------------------------------------------------------------------------
    // Core contract test
    // -------------------------------------------------------------------------

    /**
     * Item A — BACKED_UP + chunked sentinel + large first chunk (old CHUNK_UPLOAD_SIZE).
     * MUST be reset to FAILED by the migration.
     *
     * Item B — BACKED_UP + chunked sentinel + small first chunk (FAST_START_CHUNK_SIZE).
     * Already has fast-start; must NOT be touched.
     *
     * Item C — BACKED_UP + single-upload file_id (not chunked sentinel).
     * Not a chunked video; must NOT be touched.
     *
     * Item D — FAILED + chunked sentinel + large first chunk.
     * Already FAILED (handled by existing retry logic); must NOT be touched.
     *
     * FAILS: `markSlowStartChunkedItemsAsFailed` does not exist on [MediaItemDao].
     * PASSES: once the method is added with the correct SQL.
     */
    @Test
    fun markSlowStartChunkedItemsAsFailed_marksOnlyBackedUpItemsWithLargeFirstChunk() = runBlocking {
        // Arrange — insert all four items
        val itemA = mediaItem("item-a", BackupStatus.BACKED_UP, "chunked:2")
        val itemB = mediaItem("item-b", BackupStatus.BACKED_UP, "chunked:3")
        val itemC = mediaItem("item-c", BackupStatus.BACKED_UP, "some-real-file-id")
        val itemD = mediaItem(
            "item-d",
            BackupStatus.FAILED,
            "chunked:1",
            errorMessage = "previous failure",
        )

        mediaItemDao.upsertAll(listOf(itemA, itemB, itemC, itemD))

        // Chunk metadata: first chunk for each chunked item
        chunkMetadataDao.insertChunk(chunk("item-a", chunkIndex = 0, byteLength = CHUNK_UPLOAD_SIZE.toInt()))    // old large first chunk
        chunkMetadataDao.insertChunk(chunk("item-b", chunkIndex = 0, byteLength = FAST_START_CHUNK_SIZE.toInt())) // already fast-start
        chunkMetadataDao.insertChunk(chunk("item-d", chunkIndex = 0, byteLength = CHUNK_UPLOAD_SIZE.toInt()))    // large, but already FAILED

        // Act — compile error until the method is added to MediaItemDao
        mediaItemDao.markSlowStartChunkedItemsAsFailed()

        // Assert — Item A must be reset for re-upload
        val resultA = mediaItemDao.findById("item-a")!!
        assertEquals(
            "Item A (BACKED_UP + old large first chunk) must be marked FAILED",
            BackupStatus.FAILED,
            resultA.backupStatus,
        )
        assertEquals(
            "Item A errorMessage must be 'Re-upload required (fast-start)'",
            "Re-upload required (fast-start)",
            resultA.errorMessage,
        )
        assertNull(
            "Item A telegramFileId must be cleared to NULL",
            resultA.telegramFileId,
        )
        assertNull(
            "Item A telegramMessageId must be cleared to NULL",
            resultA.telegramMessageId,
        )
        assertNull(
            "Item A lastSyncedAt must be cleared to NULL",
            resultA.lastSyncedAt,
        )

        // Assert — Item B must be unchanged (small first chunk = already has fast-start)
        val resultB = mediaItemDao.findById("item-b")!!
        assertEquals(
            "Item B (small first chunk) must remain BACKED_UP",
            BackupStatus.BACKED_UP,
            resultB.backupStatus,
        )
        assertEquals(
            "Item B telegramFileId must remain 'chunked:3'",
            "chunked:3",
            resultB.telegramFileId,
        )

        // Assert — Item C must be unchanged (single-upload, not a chunked sentinel)
        val resultC = mediaItemDao.findById("item-c")!!
        assertEquals(
            "Item C (single-upload) must remain BACKED_UP",
            BackupStatus.BACKED_UP,
            resultC.backupStatus,
        )
        assertEquals(
            "Item C telegramFileId must remain 'some-real-file-id'",
            "some-real-file-id",
            resultC.telegramFileId,
        )

        // Assert — Item D must be unchanged (already FAILED, must not be double-reset)
        val resultD = mediaItemDao.findById("item-d")!!
        assertEquals(
            "Item D (already FAILED) must remain FAILED — not re-touched by migration",
            BackupStatus.FAILED,
            resultD.backupStatus,
        )
        assertEquals(
            "Item D telegramFileId must remain 'chunked:1' — not cleared by migration",
            "chunked:1",
            resultD.telegramFileId,
        )
    }
}
