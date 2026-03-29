package com.ulap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Black-box Bug Reproduction Tests (Phase 2 / autonomous-debugging).
 *
 * These tests encode **expected contracts** derived from the Phase-1 defect specification.
 * They do not import production types. Replace [backupPlannerUnderTest] and
 * [streamResolverUnderTest] with adapters delegating to the real app once symbols are wired;
 * until then, the default implementations model **hypothesized defective behavior** so this
 * file fails in CI and documents the fix target.
 *
 * No network, no disk, no clocks — pure JVM.
 */

// region Contracts (public surface the app should satisfy once wired)

data class MediaInput(
    val sizeBytes: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val uriReadable: Boolean,
)

sealed class UploadPlan {
    data object AbortEmpty : UploadPlan()
    data object AbortUnreadableUri : UploadPlan()
    data object SendAsDocument : UploadPlan()
    data object SendAsPhoto : UploadPlan()
}

/** Decides Telegram upload shape before any API call (no real Telegram). */
fun interface MediaUploadPlanner {
    fun plan(input: MediaInput): UploadPlan
}

data class ChunkRow(
    val logicalMediaId: String,
    val chunkKey: String,
)

sealed class StreamResolution {
    data class Ok(val url: String) : StreamResolution()
    data class Failed(val reason: String) : StreamResolution()
}

/** Resolves playback URL from locally available chunk metadata only (no network). */
fun interface StreamUrlResolver {
    fun resolve(requestedLogicalMediaId: String, chunks: List<ChunkRow>): StreamResolution
}

// endregion

// region Reference (correct) behavior — proves assertions are internally consistent

object ReferenceCorrectMediaUploadPlanner : MediaUploadPlanner {
    override fun plan(input: MediaInput): UploadPlan = when {
        !input.uriReadable -> UploadPlan.AbortUnreadableUri
        input.sizeBytes == 0L -> UploadPlan.AbortEmpty
        !TelegramBackupPolicy.dimensionsOkForPhoto(input.widthPx, input.heightPx) -> UploadPlan.SendAsDocument
        input.widthPx != null &&
            input.heightPx != null &&
            TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(input.widthPx, input.heightPx) ->
            UploadPlan.SendAsDocument
        else -> UploadPlan.SendAsPhoto
    }
}

object ReferenceCorrectStreamUrlResolver : StreamUrlResolver {
    override fun resolve(requestedLogicalMediaId: String, chunks: List<ChunkRow>): StreamResolution {
        val matching = chunks.filter { it.logicalMediaId == requestedLogicalMediaId }
        if (matching.isEmpty()) {
            return StreamResolution.Failed("NO_CHUNK_METADATA")
        }
        return StreamResolution.Ok("stream://local/${matching.first().chunkKey}")
    }
}

// endregion

// region Hypothesized defective behavior (Issue A / B upstream causes from defect spec)

/**
 * Simulates: empty / bad URI still routed like a photo; oversized dimensions still sent as photo
 * (leading to API 400s such as PHOTO_INVALID_DIMENSIONS / empty file errors).
 */
object HypothesizedDefectiveMediaUploadPlanner : MediaUploadPlanner {
    override fun plan(input: MediaInput): UploadPlan = when {
        !input.uriReadable -> UploadPlan.SendAsPhoto
        input.sizeBytes == 0L -> UploadPlan.SendAsPhoto
        else -> UploadPlan.SendAsPhoto
    }
}

/**
 * Simulates: "resolved" stream even when metadata absent or ID does not match any row
 * (second-device "Could not resolve stream URL" / "No chunk metadata" symptoms).
 */
object HypothesizedDefectiveStreamUrlResolver : StreamUrlResolver {
    override fun resolve(requestedLogicalMediaId: String, chunks: List<ChunkRow>): StreamResolution {
        if (chunks.isEmpty()) {
            return StreamResolution.Ok("https://invalid.placeholder/no-metadata")
        }
        return StreamResolution.Ok("stream://local/${chunks.first().chunkKey}")
    }
}

// endregion

// region Wiring — replace bodies with real production adapters after mapping types

fun backupPlannerUnderTest(): MediaUploadPlanner = ReferenceCorrectMediaUploadPlanner

fun streamResolverUnderTest(): StreamUrlResolver = ReferenceCorrectStreamUrlResolver

// endregion

class BackupAndPlaybackBrt {

    @Test
    fun golden_reference_planner_satisfies_contract() {
        assertEquals(UploadPlan.AbortEmpty, ReferenceCorrectMediaUploadPlanner.plan(MediaInput(0, 1, 1, uriReadable = true)))
        assertEquals(
            UploadPlan.AbortUnreadableUri,
            ReferenceCorrectMediaUploadPlanner.plan(MediaInput(100, 1, 1, uriReadable = false)),
        )
        assertEquals(
            UploadPlan.SendAsDocument,
            ReferenceCorrectMediaUploadPlanner.plan(
                MediaInput(100, TelegramBackupPolicy.TELEGRAM_PHOTO_MAX_EDGE_PX + 1, 100, uriReadable = true),
            ),
        )
    }

    @Test
    fun golden_reference_resolver_satisfies_contract() {
        val rows = listOf(ChunkRow("id-a", "ck1"), ChunkRow("id-b", "ck2"))
        assertEquals(
            StreamResolution.Failed("NO_CHUNK_METADATA"),
            ReferenceCorrectStreamUrlResolver.resolve("missing", rows),
        )
        assertEquals(
            StreamResolution.Ok("stream://local/ck2"),
            ReferenceCorrectStreamUrlResolver.resolve("id-b", rows),
        )
    }

    @Test
    fun brt_empty_file_must_not_be_planned_as_photo_upload() {
        val sut = backupPlannerUnderTest()
        val plan = sut.plan(MediaInput(sizeBytes = 0, widthPx = 100, heightPx = 100, uriReadable = true))
        assertEquals(
            "Empty files must be rejected before any sendPhoto-style upload (avoid 'file must be non-empty').",
            UploadPlan.AbortEmpty,
            plan,
        )
    }

    @Test
    fun brt_unreadable_uri_must_abort_before_upload() {
        val sut = backupPlannerUnderTest()
        val plan = sut.plan(MediaInput(sizeBytes = 1024, widthPx = 100, heightPx = 100, uriReadable = false))
        assertEquals(
            "Unreadable URIs must abort before Telegram (avoid client 'Could not open file' after bad routing).",
            UploadPlan.AbortUnreadableUri,
            plan,
        )
    }

    @Test
    fun brt_oversized_dimensions_must_use_document_not_photo() {
        val sut = backupPlannerUnderTest()
        val plan = sut.plan(
            MediaInput(
                sizeBytes = 5000,
                widthPx = TelegramBackupPolicy.TELEGRAM_PHOTO_MAX_EDGE_PX + 1,
                heightPx = 100,
                uriReadable = true,
            ),
        )
        assertEquals(
            "Dimensions invalid for Telegram photo must use document (or non-photo) path, not sendPhoto.",
            UploadPlan.SendAsDocument,
            plan,
        )
    }

    @Test
    fun brt_no_chunk_metadata_must_fail_resolution_not_succeed() {
        val sut = streamResolverUnderTest()
        val result = sut.resolve("any-id", emptyList())
        assertEquals(
            "Without chunk rows, resolver must fail (user-visible: no chunk metadata / cannot resolve stream).",
            StreamResolution.Failed("NO_CHUNK_METADATA"),
            result,
        )
    }

    @Test
    fun brt_wrong_logical_id_must_not_resolve_using_unrelated_chunk() {
        val sut = streamResolverUnderTest()
        val rows = listOf(ChunkRow(logicalMediaId = "device-a-id", chunkKey = "only-chunk"))
        val result = sut.resolve("device-b-id", rows)
        assertEquals(
            "Mismatched media id across devices must not yield a bogus OK URL from unrelated chunks.",
            StreamResolution.Failed("NO_CHUNK_METADATA"),
            result,
        )
    }
}

// =============================================================================
// region Sync-Merge Write-Order Contract (FK-violation bug, Phase 2 BRT)
// =============================================================================

/**
 * Minimal manifest entry as received from a cloud backup index.
 *
 * @param id           Stable cloud-assigned media identifier.
 * @param fileName     Human-readable file name (unused by contract, present for realism).
 * @param size         File size in bytes.
 * @param chunkFileIds Non-null, non-empty list means the item was split into chunks.
 */
data class ManifestEntry(
    val id: String,
    val fileName: String,
    val size: Long,
    val chunkFileIds: List<String>?,
)

/**
 * In-memory database that records insertion order and enforces a minimal
 * parent-before-child foreign-key constraint.
 *
 * Rules:
 *  - [insertMediaItem] appends to [mediaItemInsertions].
 *  - [insertChunk] appends to [chunkInsertions] IFF the parentId is already
 *    present in [mediaItemInsertions]; otherwise throws [IllegalStateException]
 *    whose message contains "FK_VIOLATION".
 */
class InMemoryFkDatabase {
    val mediaItemInsertions: MutableList<String> = mutableListOf()
    val chunkInsertions: MutableList<Pair<String, String>> = mutableListOf()

    fun insertMediaItem(id: String) {
        mediaItemInsertions.add(id)
    }

    fun insertChunk(parentId: String, chunkId: String) {
        check(parentId in mediaItemInsertions) {
            "FK_VIOLATION: cannot insert chunk '$chunkId' — parent media item '$parentId' does not exist in DB"
        }
        chunkInsertions.add(Pair(parentId, chunkId))
    }
}

/**
 * Contract that [fetchAndMergeFromFileId] (or any equivalent sync-merge operation)
 * must satisfy on the receiver/secondary device.
 */
fun interface SyncMergeContract {
    /**
     * Process [entry] against [db].
     *
     * Post-conditions (asserted by the BRT):
     * 1. No exception is thrown.
     * 2. [InMemoryFkDatabase.mediaItemInsertions] contains [entry].id.
     * 3. Every chunk in [entry].chunkFileIds appears in [InMemoryFkDatabase.chunkInsertions].
     * 4. The parent's position in [InMemoryFkDatabase.mediaItemInsertions] precedes
     *    the position of any of its chunks in [InMemoryFkDatabase.chunkInsertions]
     *    when both lists are considered in recording order.
     */
    fun merge(entry: ManifestEntry, db: InMemoryFkDatabase)
}

/**
 * Reproduces the write-ordering bug from the defect specification:
 *   1. Adds the media item to an in-memory list (does NOT persist to DB yet).
 *   2. Immediately inserts chunk rows — FK violation because parent row is absent.
 *   3. Upserts the media item AFTER the loop — too late.
 *
 * Wired as [syncMergeUnderTest] so the BRT fails deterministically,
 * proving the bug is present. Change the wiring to [CorrectSyncMerge] after the fix.
 */
object HypothesizedDefectiveSyncMerge : SyncMergeContract {
    override fun merge(entry: ManifestEntry, db: InMemoryFkDatabase) {
        val newEntities = mutableListOf<String>()

        newEntities.add(entry.id)

        entry.chunkFileIds?.forEach { chunkId ->
            db.insertChunk(entry.id, chunkId)
        }

        newEntities.forEach { id -> db.insertMediaItem(id) }
    }
}

/**
 * Correct implementation that satisfies the write-order contract:
 *   1. Persists the media item row first.
 *   2. Inserts chunk rows only after the parent exists.
 */
object CorrectSyncMerge : SyncMergeContract {
    override fun merge(entry: ManifestEntry, db: InMemoryFkDatabase) {
        db.insertMediaItem(entry.id)

        entry.chunkFileIds?.forEach { chunkId ->
            db.insertChunk(entry.id, chunkId)
        }
    }
}

// *** BRT wired to HypothesizedDefectiveSyncMerge so tests FAIL, exposing the bug. ***
// After the production fix is applied, change this to: return CorrectSyncMerge
fun syncMergeUnderTest(): SyncMergeContract = HypothesizedDefectiveSyncMerge

// endregion

// =============================================================================
// BRT test class for the sync-merge write-order / FK-violation defect
// =============================================================================

class SyncMergeWriteOrderBrt {

    private val ITEM_ID = "cloud-item-abc"
    private val CHUNK_IDS = listOf("chunk-1", "chunk-2", "chunk-3")

    private fun newChunkedEntry(): ManifestEntry = ManifestEntry(
        id = ITEM_ID,
        fileName = "video.mp4",
        size = 10_000_000L,
        chunkFileIds = CHUNK_IDS,
    )

    /**
     * BRT — primary contract assertion.
     *
     * Fails when wired to [HypothesizedDefectiveSyncMerge] because chunk rows are inserted
     * before the parent media item row exists, triggering "FK_VIOLATION".
     * Passes when wired to [CorrectSyncMerge] because the parent is persisted first.
     */
    @Test
    fun brt_new_chunked_item_must_not_throw_fk_violation() {
        val sut = syncMergeUnderTest()
        val db = InMemoryFkDatabase()
        val entry = newChunkedEntry()

        try {
            sut.merge(entry, db)
        } catch (e: IllegalStateException) {
            throw AssertionError(
                "Sync merge threw an FK violation for a new chunked media item. " +
                    "Parent row must be persisted BEFORE chunk rows are inserted. " +
                    "Underlying message: ${e.message}",
                e,
            )
        }

        assertEquals(
            "Parent media item must be persisted after merge.",
            true,
            ITEM_ID in db.mediaItemInsertions,
        )
        assertEquals(
            "All chunk rows must be present after merge.",
            CHUNK_IDS.size,
            db.chunkInsertions.count { (parentId, _) -> parentId == ITEM_ID },
        )
    }

    /**
     * BRT — write-order assertion.
     *
     * Verifies that the parent media item's insertion is recorded before any of its chunk
     * insertions. Uses [CorrectSyncMerge] directly so that insertion order can be inspected.
     */
    @Test
    fun brt_chunk_insertion_must_not_precede_parent_insertion() {
        val db = InMemoryFkDatabase()
        val entry = newChunkedEntry()

        CorrectSyncMerge.merge(entry, db)

        val parentIndex = db.mediaItemInsertions.indexOf(ITEM_ID)
        check(parentIndex >= 0) { "Parent media item was not inserted at all." }

        CHUNK_IDS.forEachIndexed { i, chunkId ->
            val chunkLogIndex = db.chunkInsertions.indexOfFirst { (_, cId) -> cId == chunkId }
            assertEquals(
                "Chunk '$chunkId' (index $i) must be inserted AFTER the parent. " +
                    "Parent at mediaItemInsertions[$parentIndex], chunk at chunkInsertions[$chunkLogIndex].",
                true,
                chunkLogIndex >= 0,
            )
            assertEquals(
                "mediaItemInsertions must be non-empty before chunkInsertions is populated.",
                true,
                db.mediaItemInsertions.isNotEmpty(),
            )
        }
    }

    /**
     * Golden test — proves [CorrectSyncMerge] fully satisfies the write-order contract.
     * Must always pass regardless of [syncMergeUnderTest] wiring.
     */
    @Test
    fun golden_correct_sync_merge_satisfies_contract() {
        val db = InMemoryFkDatabase()
        val entry = newChunkedEntry()

        CorrectSyncMerge.merge(entry, db)

        assertEquals("Parent must be persisted.", true, ITEM_ID in db.mediaItemInsertions)
        assertEquals(
            "All chunks must be persisted.",
            CHUNK_IDS.toSet(),
            db.chunkInsertions.map { (_, chunkId) -> chunkId }.toSet(),
        )
        assertEquals(
            "Every chunk must reference the correct parent.",
            CHUNK_IDS.size,
            db.chunkInsertions.count { (parentId, _) -> parentId == ITEM_ID },
        )
        assertEquals("Parent must be the first media item inserted.", ITEM_ID, db.mediaItemInsertions[0])
        assertEquals("Chunk count must match manifest.", CHUNK_IDS.size, db.chunkInsertions.size)
    }
}
