package com.ulap.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bug Reproduction Tests — TelegramUploader fast-start first-chunk upload.
 *
 * ## Defect
 *
 * `uploadChunked` allocates a single `ByteArray(CHUNK_UPLOAD_SIZE.toInt())` buffer and reads
 * every chunk — including the very first — at the same 19 MB size. There is no way for the
 * first chunk to be smaller, which prevents a media player from beginning playback while the
 * large trailing chunks are still in-flight.
 *
 * ```
 * // Current code (defective):
 * val chunkSize = CHUNK_UPLOAD_SIZE.toInt()
 * val buf = ByteArray(chunkSize)            // fixed-size: same for chunk-0 and all others
 * while (readFully(inputStream, buf).also { read = it } != -1) { ... }
 * ```
 *
 * ## Required contract (these tests encode)
 *
 * When uploading a chunked file, the first chunk (`chunkIndex == 0`) must consume exactly
 * `FAST_START_CHUNK_SIZE` bytes from the stream, and all subsequent non-last chunks must
 * consume `CHUNK_UPLOAD_SIZE` bytes. The `byteLength` reported via `onChunkUploaded` must
 * equal the actual bytes read per chunk. The total chunk count reported via
 * `onTotalChunksKnown` must account for the smaller first chunk.
 *
 * ## Why these tests FAIL against current code
 *
 *  1. `FAST_START_CHUNK_SIZE` does not exist → the whole file fails to compile.
 *  2. Once the constant is added, `uploadChunked` still reads every chunk at
 *     `CHUNK_UPLOAD_SIZE`, so:
 *     - Test 1 fails: `onChunkUploaded` for chunk 0 reports `CHUNK_UPLOAD_SIZE.toInt()`, not
 *       `FAST_START_CHUNK_SIZE.toInt()`.
 *     - Test 2 fails: `onChunkUploaded` for chunk 1 reports the remaining tail bytes, not
 *       `CHUNK_UPLOAD_SIZE.toInt()`.
 *     - Test 3 fails: `onTotalChunksKnown` is called with the smaller count derived from
 *       the current uniform-chunk formula, not the correct count that accounts for the
 *       reduced first chunk.
 *
 * Deterministic: no network — `TelegramBotApi` and `TelegramRateLimiter` are mocked.
 * The InputStream is a lazy zero-byte generator so no large heap allocation is needed.
 */
class TelegramUploaderFastStartBrt {

    // ─────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lazy InputStream that produces [size] zero bytes without allocating the full array.
     * Deterministic: no file I/O, no randomness, no external state.
     */
    private fun zeroBytesStream(size: Long): InputStream = object : InputStream() {
        private var remaining = size

        override fun read(): Int {
            if (remaining <= 0L) return -1
            remaining--
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            b.fill(0, off, off + n)
            remaining -= n
            return n
        }
    }

    private fun successResponse(fileId: String, messageId: Long) = TelegramResponse(
        ok = true,
        result = TelegramMessage(
            messageId = messageId,
            document = TelegramDocument(
                fileId = fileId,
                fileSize = null,
                fileName = null,
                mimeType = null,
                thumbnail = null,
            ),
            video = null,
            photo = null,
            caption = null,
        ),
        description = null,
        errorCode = null,
        parameters = null,
    )

    /**
     * Mocked [TelegramRateLimiter] that bypasses all rate-limiting logic and immediately
     * invokes the supplied block.
     *
     * [TelegramRateLimiter] has an `@Inject constructor(UserPreferencesRepository)` which
     * requires Android context; mocking it avoids that dependency in a plain JVM test.
     */
    private fun noOpRateLimiter(): TelegramRateLimiter = mock { rl ->
        onBlocking {
            @Suppress("UNCHECKED_CAST")
            rl.withRateLimit(any<suspend () -> Any>())
        }.doSuspendableAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            (inv.getArgument(0) as suspend () -> Any).invoke()
        }
    }

    /**
     * Mocked [TelegramBotApi] where every `sendDocument` call returns a successful response.
     * Each call receives a unique monotonically-increasing message ID so chunks can be
     * distinguished if needed, without introducing shared state between tests.
     */
    private fun successApi(): TelegramBotApi {
        val msgId = AtomicInteger(1)
        return mock {
            onBlocking {
                sendDocument(
                    token = any(),
                    chatId = any(),
                    document = any(),
                    caption = anyOrNull(),
                    thumbnail = anyOrNull(),
                )
            }.doSuspendableAnswer {
                successResponse("fid-${msgId.get()}", msgId.getAndIncrement().toLong())
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — chunk 0 byteLength == FAST_START_CHUNK_SIZE, not CHUNK_UPLOAD_SIZE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * For a 21 MB file (> MAX_SINGLE_UPLOAD_SIZE = 20 MB), the first chunk uploaded to
     * Telegram must be exactly [FAST_START_CHUNK_SIZE] bytes, not [CHUNK_UPLOAD_SIZE].
     *
     * Current behaviour: chunk-0 reads [CHUNK_UPLOAD_SIZE] bytes → assertion fails.
     * After fix: chunk-0 reads [FAST_START_CHUNK_SIZE] bytes → assertion passes.
     *
     * FAILS: compile error (`FAST_START_CHUNK_SIZE` undefined) and, once the constant
     *        is added, runtime assertion (byteLength == CHUNK_UPLOAD_SIZE, not FAST_START_CHUNK_SIZE).
     * PASSES: once `uploadChunked` uses `FAST_START_CHUNK_SIZE` for chunk index 0.
     */
    @Test
    fun uploadMedia_chunkedFile_firstChunkByteLength_equals_FAST_START_CHUNK_SIZE() = runTest {
        // 21 MB > MAX_SINGLE_UPLOAD_SIZE (20 MB) → goes through uploadChunked
        val fileSize = 21L * 1024 * 1024

        val uploadApi = successApi()
        val uploader = TelegramUploader(
            api = mock(),
            uploadApi = uploadApi,
            rateLimiter = noOpRateLimiter(),
        )

        data class ChunkRecord(val chunkIndex: Int, val byteLength: Int)
        val chunks = mutableListOf<ChunkRecord>()

        uploader.uploadMedia(
            token = "test:token",
            chatId = "12345",
            inputStream = zeroBytesStream(fileSize),
            fileName = "test.mp4",
            mimeType = "video/mp4",
            fileSize = fileSize,
            onChunkUploaded = { chunkIndex, _, _, _, byteLength ->
                chunks.add(ChunkRecord(chunkIndex, byteLength))
            },
        )

        val chunk0 = chunks.firstOrNull { it.chunkIndex == 0 }
        assertEquals(
            "onChunkUploaded must be called at least once (chunk 0 must exist)",
            true,
            chunk0 != null,
        )
        assertEquals(
            "First chunk (chunkIndex=0) byteLength must equal FAST_START_CHUNK_SIZE " +
                "(${FAST_START_CHUNK_SIZE}) — NOT CHUNK_UPLOAD_SIZE (${CHUNK_UPLOAD_SIZE}). " +
                "uploadChunked currently reads every chunk at CHUNK_UPLOAD_SIZE, " +
                "including the first.",
            FAST_START_CHUNK_SIZE.toInt(),
            chunk0!!.byteLength,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — chunk 1 byteLength == CHUNK_UPLOAD_SIZE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * After the first 512 KB fast-start chunk, all subsequent non-last chunks must still
     * use the full [CHUNK_UPLOAD_SIZE].
     *
     * For a 21 MB file, the current code produces only 2 chunks (both incorrectly sized);
     * the corrected code produces 3 chunks where chunk-1 = CHUNK_UPLOAD_SIZE.
     *
     * Current behaviour with 21 MB:
     *   chunk-0: CHUNK_UPLOAD_SIZE (19 MB) — wrong size for chunk 0
     *   chunk-1: 21 MB - 19 MB = 2 MB — NOT CHUNK_UPLOAD_SIZE → assertion fails
     *
     * After fix:
     *   chunk-0: FAST_START_CHUNK_SIZE (512 KB)
     *   chunk-1: CHUNK_UPLOAD_SIZE (19 MB) ← assertion passes
     *   chunk-2: remaining
     *
     * FAILS: runtime — chunk-1 byteLength is 2097152 (2 MB), not CHUNK_UPLOAD_SIZE.
     * PASSES: once `uploadChunked` reads chunk-0 at `FAST_START_CHUNK_SIZE` and all
     *         subsequent non-last chunks at `CHUNK_UPLOAD_SIZE`.
     */
    @Test
    fun uploadMedia_chunkedFile_secondChunkByteLength_equals_CHUNK_UPLOAD_SIZE() = runTest {
        val fileSize = 21L * 1024 * 1024

        val uploadApi = successApi()
        val uploader = TelegramUploader(
            api = mock(),
            uploadApi = uploadApi,
            rateLimiter = noOpRateLimiter(),
        )

        data class ChunkRecord(val chunkIndex: Int, val byteLength: Int)
        val chunks = mutableListOf<ChunkRecord>()

        uploader.uploadMedia(
            token = "test:token",
            chatId = "12345",
            inputStream = zeroBytesStream(fileSize),
            fileName = "test.mp4",
            mimeType = "video/mp4",
            fileSize = fileSize,
            onChunkUploaded = { chunkIndex, _, _, _, byteLength ->
                chunks.add(ChunkRecord(chunkIndex, byteLength))
            },
        )

        val chunk1 = chunks.firstOrNull { it.chunkIndex == 1 }

        assertEquals(
            "onChunkUploaded must be called for chunkIndex=1",
            true,
            chunk1 != null,
        )
        assertEquals(
            "Second chunk (chunkIndex=1) byteLength must equal CHUNK_UPLOAD_SIZE " +
                "(${CHUNK_UPLOAD_SIZE}). After the fast-start chunk-0, all subsequent " +
                "non-last chunks must resume the full CHUNK_UPLOAD_SIZE. " +
                "Currently chunk-1 receives the leftover tail bytes (2 MB) because chunk-0 " +
                "consumed the first 19 MB at CHUNK_UPLOAD_SIZE.",
            CHUNK_UPLOAD_SIZE.toInt(),
            chunk1!!.byteLength,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — onTotalChunksKnown receives the correct count for the fast-start layout
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * `onTotalChunksKnown` is called once, before any chunk is uploaded, with the total
     * number of chunks for the file. When the first chunk uses `FAST_START_CHUNK_SIZE`
     * instead of `CHUNK_UPLOAD_SIZE`, the count changes.
     *
     * For a 21 MB file with `FAST_START_CHUNK_SIZE = 512 KB`:
     *   chunk-0: 512 KB
     *   remaining: 21 MB - 512 KB = ~20.5 MB → ceil(20.5 / 19) = 2 more chunks
     *   total: 3 chunks
     *
     * Current code uses the uniform formula:
     *   ceil(21 MB / 19 MB) = 2 chunks → wrong once fast-start is applied.
     *
     * FAILS: runtime — onTotalChunksKnown is called with 2, not 3.
     * PASSES: once the total-chunks formula accounts for the reduced first chunk.
     */
    @Test
    fun uploadMedia_chunkedFile_onTotalChunksKnown_reflects_fastStart_chunkCount() = runTest {
        val fileSize = 21L * 1024 * 1024

        val uploadApi = successApi()
        val uploader = TelegramUploader(
            api = mock(),
            uploadApi = uploadApi,
            rateLimiter = noOpRateLimiter(),
        )

        var reportedTotalChunks = -1

        uploader.uploadMedia(
            token = "test:token",
            chatId = "12345",
            inputStream = zeroBytesStream(fileSize),
            fileName = "test.mp4",
            mimeType = "video/mp4",
            fileSize = fileSize,
            onTotalChunksKnown = { n -> reportedTotalChunks = n },
        )

        // With FAST_START_CHUNK_SIZE = 512 KB and CHUNK_UPLOAD_SIZE = 19 MB:
        //   chunk-0 = 512 KB
        //   remaining = 21 MB - 512 KB = 21,495,808 bytes (~20.5 MB)
        //   ceil(20.5 MB / 19 MB) = 2 → 1 full chunk (19 MB) + 1 remainder chunk (~1.5 MB)
        //   total = 1 + 1 + 1 = 3
        val expectedTotalChunks =
            (1 + // fast-start chunk
                ((fileSize - FAST_START_CHUNK_SIZE + CHUNK_UPLOAD_SIZE - 1) / CHUNK_UPLOAD_SIZE)).toInt()

        assertEquals(
            "onTotalChunksKnown must be called with the chunk count computed using " +
                "FAST_START_CHUNK_SIZE for chunk-0 (expected $expectedTotalChunks). " +
                "Current code uses the uniform ceil(fileSize / CHUNK_UPLOAD_SIZE) formula " +
                "which yields 2 — one fewer than the correct fast-start layout requires.",
            expectedTotalChunks,
            reportedTotalChunks,
        )
    }
}
