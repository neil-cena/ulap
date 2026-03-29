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

// region Deterministic “Telegram photo” threshold (contract constant, not API truth)

private const val TELEGRAM_PHOTO_MAX_EDGE_PX = 10_000

private fun dimensionsAllowedForPhoto(width: Int?, height: Int?): Boolean {
    if (width == null || height == null) return true
    if (width <= 0 || height <= 0) return false
    return width <= TELEGRAM_PHOTO_MAX_EDGE_PX && height <= TELEGRAM_PHOTO_MAX_EDGE_PX
}

// endregion

// region Reference (correct) behavior — proves assertions are internally consistent

object ReferenceCorrectMediaUploadPlanner : MediaUploadPlanner {
    override fun plan(input: MediaInput): UploadPlan = when {
        !input.uriReadable -> UploadPlan.AbortUnreadableUri
        input.sizeBytes == 0L -> UploadPlan.AbortEmpty
        !dimensionsAllowedForPhoto(input.widthPx, input.heightPx) -> UploadPlan.SendAsDocument
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
 * Simulates: “resolved” stream even when metadata absent or ID does not match any row
 * (second-device “Could not resolve stream URL” / “No chunk metadata” symptoms).
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
                MediaInput(100, TELEGRAM_PHOTO_MAX_EDGE_PX + 1, 100, uriReadable = true),
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
                widthPx = TELEGRAM_PHOTO_MAX_EDGE_PX + 1,
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
