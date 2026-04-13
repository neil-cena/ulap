package com.ulap

import com.ulap.data.remote.CHUNK_UPLOAD_SIZE
import com.ulap.data.remote.CHUNKED_FILE_ID_PREFIX
import com.ulap.data.remote.ChunkMetadataLayout
import com.ulap.data.remote.FAST_START_CHUNK_SIZE // does not exist yet — compile error until the constant is added
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bug Reproduction Tests — ChunkMetadataLayout fast-start first-chunk feature.
 *
 * ## Defect
 *
 * `byteLengthsForChunkedFile` allocates the same [CHUNK_UPLOAD_SIZE] to every non-last chunk,
 * including the first. There is no way to request a smaller "fast-start" first chunk that
 * lets the media player begin streaming while the large trailing chunks are still uploading.
 *
 * ## Required contract (these tests encode)
 *
 * An optional `fastStartChunkSize: Long? = null` parameter must be added to
 * `byteLengthsForChunkedFile`. When non-null:
 *   - chunk at index 0 → exactly `fastStartChunkSize` bytes (not `CHUNK_UPLOAD_SIZE`)
 *   - all subsequent non-last chunks → `CHUNK_UPLOAD_SIZE` bytes (unchanged)
 *   - last chunk → remaining bytes (unchanged)
 *   - sum of all chunks == `totalSize` (invariant preserved)
 * When null, behaviour is identical to the current implementation (backward compatible).
 *
 * A new constant `FAST_START_CHUNK_SIZE = 512L * 1024` must be defined in
 * `com.ulap.data.remote`.
 *
 * ## Why these tests FAIL against current code
 *
 *  1. `FAST_START_CHUNK_SIZE` does not exist → the whole file fails to compile.
 *  2. `byteLengthsForChunkedFile` does not accept a `fastStartChunkSize` parameter →
 *     every call-site that passes the named argument fails to compile.
 *
 * Deterministic: no network, no I/O, no clocks, no randomness.
 */
class ChunkMetadataLayoutFastStartBrt {

    // ─────────────────────────────────────────────────────────────────────────
    // Contract 1 — multi-chunk file: first=FAST_START_CHUNK_SIZE, mid=CHUNK_UPLOAD_SIZE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Three-chunk file where fast-start is enabled.
     *
     * Arrangement:  [FAST_START_CHUNK_SIZE | CHUNK_UPLOAD_SIZE | 100 bytes]
     *
     * FAILS: `fastStartChunkSize` parameter does not exist on `byteLengthsForChunkedFile`.
     * PASSES: once the parameter is added and chunk-0 is sized to `fastStartChunkSize`.
     */
    @Test
    fun byteLengths_fastStart_firstChunkEqualsFastStartSize_midChunkEqualsChunkUploadSize() {
        val total = FAST_START_CHUNK_SIZE + CHUNK_UPLOAD_SIZE + 100L

        val lens = ChunkMetadataLayout.byteLengthsForChunkedFile(
            totalSize = total,
            chunkCount = 3,
            fastStartChunkSize = FAST_START_CHUNK_SIZE,
        )

        assertEquals(
            "First chunk must equal FAST_START_CHUNK_SIZE when fastStartChunkSize is set",
            FAST_START_CHUNK_SIZE.toInt(),
            lens[0],
        )
        assertEquals(
            "Middle (non-last) chunk must equal CHUNK_UPLOAD_SIZE regardless of fastStartChunkSize",
            CHUNK_UPLOAD_SIZE.toInt(),
            lens[1],
        )
        assertEquals(
            "Last chunk must hold the remainder (totalSize - fastStartChunkSize - CHUNK_UPLOAD_SIZE)",
            100,
            lens[2],
        )
        assertEquals(
            "Sum of all chunk byte-lengths must equal totalSize",
            total,
            lens.sumOf { it.toLong() },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Contract 2 — backward compatibility: null fastStartChunkSize leaves behaviour unchanged
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * When `fastStartChunkSize` is null the function must behave exactly as it does today:
     * first non-last chunk == CHUNK_UPLOAD_SIZE.
     *
     * FAILS: `fastStartChunkSize` parameter does not exist.
     * PASSES: once the parameter is added as nullable with default null and null branch
     *         falls through to the existing algorithm.
     */
    @Test
    fun byteLengths_nullFastStart_backwardCompatible_allMiddleChunksEqualChunkUploadSize() {
        val total = CHUNK_UPLOAD_SIZE + 5L

        val lens = ChunkMetadataLayout.byteLengthsForChunkedFile(
            totalSize = total,
            chunkCount = 2,
            fastStartChunkSize = null,
        )

        assertEquals(
            "Without fastStartChunkSize (null), first non-last chunk must equal CHUNK_UPLOAD_SIZE",
            CHUNK_UPLOAD_SIZE.toInt(),
            lens[0],
        )
        assertEquals(
            "Without fastStartChunkSize (null), last chunk must equal the 5-byte remainder",
            5,
            lens[1],
        )
        assertEquals(
            "Sum must equal totalSize (backward-compat invariant)",
            total,
            lens.sumOf { it.toLong() },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Contract 3 — single-chunk file: fast-start must NOT truncate to fastStartChunkSize
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A single-chunk file cannot be split, so the one chunk must always span the full
     * `totalSize` — even when `fastStartChunkSize` is set to a smaller value.
     *
     * This guards against a naive implementation that applies `fastStartChunkSize` to
     * index-0 unconditionally, including when it is also the last chunk.
     *
     * FAILS: `fastStartChunkSize` parameter does not exist.
     * PASSES: once the implementation treats a single-chunk file's only chunk as the
     *         "last chunk" and uses `totalSize` (clamped) rather than `fastStartChunkSize`.
     */
    @Test
    fun byteLengths_fastStart_singleChunkFileCoversTotalSize_notFastStartSize() {
        val total = 100L // much smaller than FAST_START_CHUNK_SIZE

        val lens = ChunkMetadataLayout.byteLengthsForChunkedFile(
            totalSize = total,
            chunkCount = 1,
            fastStartChunkSize = FAST_START_CHUNK_SIZE,
        )

        assertEquals("Single-chunk file must have exactly one entry", 1, lens.size)
        assertEquals(
            "Single-chunk file's only chunk must span totalSize, not fastStartChunkSize — " +
                "a 1-chunk file cannot be split",
            total.toInt(),
            lens[0],
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Contract 4 — two-chunk file with explicit fastStartChunkSize = 64 KB
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Two-chunk file: first = 64 KB (fastStartChunkSize), second = remainder.
     * Verifies correctness at a size smaller than FAST_START_CHUNK_SIZE.
     *
     * FAILS: `fastStartChunkSize` parameter does not exist.
     * PASSES: once the first-chunk override logic is present.
     */
    @Test
    fun byteLengths_fastStart_twoChunkFile_firstIs64KB_secondIsRemainder() {
        val fastStart = 64L * 1024 // 64 KB
        val remainder = 500L
        val total = fastStart + remainder

        val lens = ChunkMetadataLayout.byteLengthsForChunkedFile(
            totalSize = total,
            chunkCount = 2,
            fastStartChunkSize = fastStart,
        )

        assertEquals("Two-chunk file must have exactly two entries", 2, lens.size)
        assertEquals(
            "First chunk of a two-chunk file must equal fastStartChunkSize (64 KB)",
            fastStart.toInt(),
            lens[0],
        )
        assertEquals(
            "Second (last) chunk must equal the remainder (totalSize - 64 KB)",
            remainder.toInt(),
            lens[1],
        )
        assertEquals(
            "Sum of both chunks must equal totalSize",
            total,
            lens.sumOf { it.toLong() },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Contract 5 — fastStartChunkSize must not affect totalChunksFromSentinel
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The `totalChunksFromSentinel` path is unrelated to chunk sizing and must keep
     * working unchanged after the fast-start feature is introduced.
     *
     * This test exercises existing sentinel behaviour as a regression guard.
     * It will FAIL to compile (along with the rest of the file) until
     * `FAST_START_CHUNK_SIZE` is defined. Once the constant exists it should PASS
     * immediately since `totalChunksFromSentinel` is not modified by the feature.
     */
    @Test
    fun totalChunksFromSentinel_unaffectedByFastStartFeature() {
        assertEquals(5, ChunkMetadataLayout.totalChunksFromSentinel("${CHUNKED_FILE_ID_PREFIX}5"))
        assertEquals(1, ChunkMetadataLayout.totalChunksFromSentinel("${CHUNKED_FILE_ID_PREFIX}1"))
        assertNull(ChunkMetadataLayout.totalChunksFromSentinel("other"))
        assertNull(ChunkMetadataLayout.totalChunksFromSentinel("${CHUNKED_FILE_ID_PREFIX}0"))
        assertNull(ChunkMetadataLayout.totalChunksFromSentinel(null))
    }
}
