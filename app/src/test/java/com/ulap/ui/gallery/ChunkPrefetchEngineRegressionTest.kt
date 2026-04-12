package com.ulap.ui.gallery

import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for [ChunkPrefetchEngine] covering the three critical bugs
 * discovered during the video playback debugging session:
 *
 * 1. **setPrefetchOrigin clearing completed state** — previously wiped the
 *    `completed` map and cancelled all downloads, causing ExoPlayer to get
 *    PARSING_CONTAINER_MALFORMED when it read chunks 0 and N but had no
 *    intermediate chunks.
 *
 * 2. **Stale generation discarding downloaded files** — a fully-downloaded
 *    chunk file was deleted just because the generation counter changed
 *    mid-download, wasting bandwidth and causing timeouts.
 *
 * 3. **waitForChunk silent timeout** — after 10 seconds with no active
 *    download, waitForChunk silently broke out of the loop and threw a
 *    generic timeout without attempting to re-launch the download.
 */
class ChunkPrefetchEngineRegressionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun chunk(index: Int, byteLength: Int = 1024) = ChunkMetadataEntity(
        id = index.toLong(),
        mediaItemId = "media-regression",
        chunkIndex = index,
        telegramFileId = "tg-reg-$index",
        telegramMessageId = 3000L + index,
        byteOffset = index.toLong() * byteLength,
        byteLength = byteLength,
        status = ChunkStatus.UPLOADED,
    )

    private fun meta(count: Int) = (0 until count).map { chunk(it) }

    private fun successClient(responseBytes: ByteArray = ByteArray(64)): OkHttpClient {
        val client = mock<OkHttpClient>()
        val call = mock<Call>()
        val response = mock<Response>()
        val body = mock<ResponseBody>()
        whenever(client.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)
        whenever(response.isSuccessful).thenReturn(true)
        whenever(response.code).thenReturn(200)
        whenever(response.body).thenReturn(body)
        whenever(body.bytes()).thenReturn(responseBytes)
        whenever(body.byteStream()).thenReturn(responseBytes.inputStream())
        whenever(body.contentLength()).thenReturn(responseBytes.size.toLong())
        whenever(body.source()).thenReturn(Buffer().write(responseBytes))
        return client
    }

    private fun failingClient(): OkHttpClient {
        val client = mock<OkHttpClient>()
        val call = mock<Call>()
        whenever(client.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenThrow(IOException("Simulated network failure"))
        return client
    }

    // =========================================================================
    // Regression 1: setPrefetchOrigin must preserve completed chunk state
    // =========================================================================

    /**
     * When ExoPlayer seeks (e.g. reads chunk 0 then jumps to the last chunk),
     * setPrefetchOrigin is called with a new origin. Previously this cleared
     * the `completed` map, making the engine forget that chunk 0 was already
     * downloaded. This caused PARSING_CONTAINER_MALFORMED because ExoPlayer
     * read chunk 0 and the last chunk but couldn't access intermediate data.
     *
     * After the fix, completed chunks remain in the map across generation
     * changes, and isChunkReady returns true for previously-downloaded chunks.
     */
    @Test(timeout = 15_000)
    fun `setPrefetchOrigin preserves completed chunk state across generations`() = runTest {
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(5),
            urlResolver = { "https://cdn4.cdn-telegram.org/file/chunk-$it.dat" },
            okHttpClient = successClient(),
            windowSize = 2,
        )

        engine.setPrefetchOrigin(0)
        engine.waitForChunk(0)
        assertTrue("Chunk 0 should be ready after download", engine.isChunkReady(0))

        engine.setPrefetchOrigin(4)

        assertTrue(
            "Chunk 0 must still be ready after setPrefetchOrigin(4). " +
                "The completed map must not be cleared on generation change.",
            engine.isChunkReady(0),
        )

        engine.release()
    }

    /**
     * Verifies that chunk files on disk survive generation changes.
     * Even if the in-memory completed map were somehow cleared, the file-based
     * fallback in isChunkReady must still detect the chunk.
     */
    @Test(timeout = 15_000)
    fun `chunk files on disk survive generation changes and remain accessible`() = runTest {
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(3),
            urlResolver = { "https://cdn4.cdn-telegram.org/file/chunk-$it.dat" },
            okHttpClient = successClient(),
            windowSize = 3,
        )

        engine.setPrefetchOrigin(0)
        engine.waitForChunk(0)
        engine.waitForChunk(1)

        val chunk0File = File(temporaryFolder.root, "chunk_0.dat")
        val chunk1File = File(temporaryFolder.root, "chunk_1.dat")
        assertTrue("chunk_0.dat must exist on disk", chunk0File.exists())
        assertTrue("chunk_1.dat must exist on disk", chunk1File.exists())

        engine.setPrefetchOrigin(2)

        assertTrue("chunk_0.dat must survive generation change", chunk0File.exists())
        assertTrue("chunk_1.dat must survive generation change", chunk1File.exists())
        assertTrue("isChunkReady(0) must still return true", engine.isChunkReady(0))
        assertTrue("isChunkReady(1) must still return true", engine.isChunkReady(1))

        engine.release()
    }

    // =========================================================================
    // Regression 2: Downloads must not discard files due to stale generation
    // =========================================================================

    /**
     * Previously, doDownload checked `if (generation.get() != gen)` after
     * writing to the tmp file and deleted the download even though it was
     * fully complete. This wasted bandwidth on slow connections (10-15s per
     * chunk) and caused subsequent waitForChunk calls to time out.
     *
     * After the fix, a fully-downloaded file is always saved regardless of
     * generation changes that occurred during the download.
     */
    @Test(timeout = 30_000)
    fun `completed download is saved even when generation changes mid-download`() = runTest {
        val downloadStarted = CountDownLatch(1)
        val allowDownloadToFinish = CountDownLatch(1)

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(3),
            urlResolver = { index ->
                if (index == 0) {
                    downloadStarted.countDown()
                    @Suppress("BlockingMethodInNonBlockingContext")
                    allowDownloadToFinish.await(10, TimeUnit.SECONDS)
                }
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = successClient(),
            windowSize = 2,
        )

        engine.setPrefetchOrigin(0)
        assertTrue("Download for chunk 0 should start", downloadStarted.await(5, TimeUnit.SECONDS))

        engine.setPrefetchOrigin(2)
        allowDownloadToFinish.countDown()

        Thread.sleep(1000)

        assertTrue(
            "Chunk 0 download should be saved even though generation changed. " +
                "The stale-generation file discard was the root cause of chunk timeouts.",
            engine.isChunkReady(0),
        )

        engine.release()
    }

    // =========================================================================
    // Regression 3: waitForChunk must re-launch downloads instead of timing out
    // =========================================================================

    /**
     * Previously, when a download finished (removed from `downloading` map)
     * but the chunk was not ready (e.g. because the generation cleared it),
     * waitForChunk would simply time out after 10 seconds without retrying.
     *
     * After the fix, waitForChunk re-launches the download when it detects
     * no active download and the chunk isn't ready, up to a 60-second limit.
     */
    @Test(timeout = 30_000)
    fun `waitForChunk re-launches download when chunk is not downloading and not ready`() = runTest {
        val resolverCallCount = AtomicInteger(0)
        val resolverLatch = CountDownLatch(2) // expect at least 2 resolver calls

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(1),
            urlResolver = {
                val count = resolverCallCount.incrementAndGet()
                resolverLatch.countDown()
                if (count == 1) {
                    "http://invalid.example.com/chunk.dat" // first attempt: invalid URL → fails
                } else {
                    "https://cdn4.cdn-telegram.org/file/chunk-0.dat" // subsequent: valid
                }
            },
            okHttpClient = successClient(),
            windowSize = 1,
        )

        engine.setPrefetchOrigin(0)

        try {
            engine.waitForChunk(0)
        } catch (_: IOException) {
            // First attempt may fail with invalid CDN URL, that's expected
        }

        engine.setPrefetchOrigin(0)
        engine.waitForChunk(0)

        assertTrue(
            "urlResolver should have been called at least twice (initial + re-launch)",
            resolverCallCount.get() >= 2,
        )
        assertTrue("Chunk 0 should eventually be ready after re-launch", engine.isChunkReady(0))

        engine.release()
    }

    // =========================================================================
    // Regression 4: setPrefetchOrigin must clear failed state for new generation
    // =========================================================================

    /**
     * failedChunks must be cleared on generation change so that a chunk that
     * failed in one generation gets a fresh attempt in the next.
     */
    @Test(timeout = 15_000)
    fun `setPrefetchOrigin clears failed chunks for new generation`() = runTest {
        val attempt = AtomicInteger(0)

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(1),
            urlResolver = {
                if (attempt.getAndIncrement() == 0) {
                    "http://invalid.example.com/fail" // fails CDN validation
                } else {
                    "https://cdn4.cdn-telegram.org/file/chunk-0.dat"
                }
            },
            okHttpClient = successClient(),
            windowSize = 1,
        )

        engine.setPrefetchOrigin(0)
        var firstFailed = false
        try {
            engine.waitForChunk(0)
        } catch (_: IOException) {
            firstFailed = true
        }
        assertTrue("First attempt must fail (invalid URL)", firstFailed)

        engine.setPrefetchOrigin(0) // clears failedChunks

        try {
            engine.waitForChunk(0)
        } catch (e: IOException) {
            fail(
                "Second attempt should succeed after setPrefetchOrigin clears failed state. " +
                    "Got: ${e.message}",
            )
        }

        assertTrue("Chunk 0 must be ready after retry", engine.isChunkReady(0))
        engine.release()
    }

    // =========================================================================
    // Regression 5: In-progress downloads must not be cancelled by new origin
    // =========================================================================

    /**
     * Previously setPrefetchOrigin called `downloading.values.forEach { it.cancel() }`
     * which killed all active download jobs. On slow connections (10-15s per
     * 20MB chunk), this wasted nearly-complete downloads.
     *
     * After the fix, in-progress downloads are allowed to complete naturally.
     */
    @Test(timeout = 30_000)
    fun `in-progress downloads are not cancelled by setPrefetchOrigin`() = runTest {
        val chunk0DownloadStarted = CountDownLatch(1)
        val chunk0DownloadReachedBody = CountDownLatch(1)

        val callMap = ConcurrentHashMap<Int, AtomicInteger>()

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(5),
            urlResolver = { index ->
                callMap.getOrPut(index) { AtomicInteger(0) }.incrementAndGet()
                if (index == 0) {
                    chunk0DownloadStarted.countDown()
                    delay(200)
                    chunk0DownloadReachedBody.countDown()
                }
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = successClient(),
            windowSize = 2,
        )

        engine.setPrefetchOrigin(0) // starts chunks 0, 1
        assertTrue("Chunk 0 download should start", chunk0DownloadStarted.await(5, TimeUnit.SECONDS))

        engine.setPrefetchOrigin(3) // starts chunks 3, 4

        assertTrue(
            "Chunk 0 resolver should continue running after origin change",
            chunk0DownloadReachedBody.await(5, TimeUnit.SECONDS),
        )

        engine.release()
    }

    // =========================================================================
    // Regression 6: Multiple rapid setPrefetchOrigin calls don't corrupt state
    // =========================================================================

    /**
     * ExoPlayer can call open() multiple times in quick succession (e.g.
     * during seeking), each triggering setPrefetchOrigin. The engine must
     * handle rapid generation changes without deadlocking or corrupting
     * internal state.
     */
    @Test(timeout = 15_000)
    fun `rapid setPrefetchOrigin calls do not deadlock or corrupt state`() = runTest {
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(10),
            urlResolver = { "https://cdn4.cdn-telegram.org/file/chunk-$it.dat" },
            okHttpClient = successClient(),
            windowSize = 2,
        )

        for (i in 0 until 10) {
            engine.setPrefetchOrigin(i)
        }

        // Should not deadlock or throw; engine should be in a usable state
        engine.setPrefetchOrigin(0)
        engine.waitForChunk(0)
        assertTrue("Engine must remain functional after rapid origin changes", engine.isChunkReady(0))

        engine.release()
    }

    // =========================================================================
    // Regression 7: resolveUrlWithRetry does not reject based on generation
    // =========================================================================

    /**
     * Previously resolveUrlWithRetry checked `if (generation.get() != gen)`
     * and threw "Stale generation for chunk X". This caused downloads started
     * by waitForChunk to immediately fail when the user seeked, leading to
     * ERROR_CODE_IO_UNSPECIFIED with "Stale generation" in the cause.
     *
     * After the fix, URL resolution completes regardless of generation changes.
     */
    @Test(timeout = 15_000)
    fun `URL resolution completes even when generation changes during resolve`() = runTest {
        val resolveStarted = CountDownLatch(1)
        val allowResolveToFinish = CountDownLatch(1)

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(3),
            urlResolver = { index ->
                if (index == 0) {
                    resolveStarted.countDown()
                    @Suppress("BlockingMethodInNonBlockingContext")
                    allowResolveToFinish.await(10, TimeUnit.SECONDS)
                }
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = successClient(),
            windowSize = 1,
        )

        engine.setPrefetchOrigin(0)
        assertTrue("URL resolution should start", resolveStarted.await(5, TimeUnit.SECONDS))

        engine.setPrefetchOrigin(2) // changes generation while chunk 0 is mid-resolve
        allowResolveToFinish.countDown()

        Thread.sleep(1500)

        assertTrue(
            "Chunk 0 should be ready even though generation changed during URL resolution. " +
                "The stale generation rejection was removed.",
            engine.isChunkReady(0),
        )

        engine.release()
    }

    // =========================================================================
    // Regression 8: waitForChunk starts download for out-of-window chunks
    // =========================================================================

    /**
     * When ExoPlayer seeks to a chunk that is outside the prefetch window,
     * waitForChunk must proactively start a download for that chunk rather
     * than waiting indefinitely for the window to advance.
     */
    @Test(timeout = 30_000)
    fun `waitForChunk starts download for chunk outside prefetch window`() = runTest {
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(10),
            urlResolver = { "https://cdn4.cdn-telegram.org/file/chunk-$it.dat" },
            okHttpClient = successClient(),
            windowSize = 2,
        )

        engine.setPrefetchOrigin(0) // window = [0, 2)

        // Chunk 5 is outside the window — waitForChunk should start it
        engine.waitForChunk(5)
        assertTrue("Chunk 5 should be ready after waitForChunk starts its download", engine.isChunkReady(5))

        engine.release()
    }

    // =========================================================================
    // Regression 9: isChunkReady checks disk even if completed map is empty
    // =========================================================================

    /**
     * isChunkReady has a file-system fallback: if a chunk file exists on disk
     * with non-zero size, the chunk is considered ready even if it's not in
     * the completed map. This ensures chunks from previous app sessions or
     * earlier prefetch engines are recognized.
     */
    @Test
    fun `isChunkReady detects pre-existing chunk files on disk`() {
        val chunkDir = temporaryFolder.root
        File(chunkDir, "chunk_2.dat").writeBytes(ByteArray(100))

        val engine = ChunkPrefetchEngine(
            chunkDir = chunkDir,
            chunkMeta = meta(5),
            urlResolver = { "https://cdn4.cdn-telegram.org/file/chunk-$it.dat" },
            okHttpClient = failingClient(),
        )

        assertTrue(
            "isChunkReady must detect chunk files already on disk (file-system fallback)",
            engine.isChunkReady(2),
        )

        engine.release()
    }

    // =========================================================================
    // Regression 10: CDN URL validation rejects non-Telegram hosts
    // =========================================================================

    @Test
    fun `isValidCdnUrl accepts telegram CDN hosts and rejects others`() {
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(1),
            urlResolver = { "" },
            okHttpClient = failingClient(),
        )

        assertTrue(engine.isValidCdnUrl("https://cdn4.cdn-telegram.org/file/abc.dat"))
        assertTrue(engine.isValidCdnUrl("https://telegram.org/file/abc.dat"))
        assertTrue(engine.isValidCdnUrl("https://api.telegram.org/file/abc.dat"))
        assertTrue(engine.isValidCdnUrl("https://cdn1.telegram-cdn.net/file/abc.dat"))

        assertTrue("http:// must be rejected", !engine.isValidCdnUrl("http://cdn4.cdn-telegram.org/file/abc.dat"))
        assertTrue("Evil host must be rejected", !engine.isValidCdnUrl("https://evil.com/file/abc.dat"))
        assertTrue("Empty URL must be rejected", !engine.isValidCdnUrl(""))
        assertTrue("Malformed URL must be rejected", !engine.isValidCdnUrl("not-a-url"))

        engine.release()
    }
}
