package com.ulap.ui.gallery

import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * BRT suite for the NEW ChunkPrefetchEngine interface that replaces the
 * `resolvedUrls: List<String?>` constructor parameter with a lazy
 * `urlResolver: suspend (Int) -> String` lambda.
 *
 * Every test in this file FAILS against the current implementation (wrong
 * constructor signature / missing IOException propagation / no generation
 * counter) and PASSES only once the new contract is fully in place.
 */
class ChunkPrefetchEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private fun chunk(index: Int) = ChunkMetadataEntity(
        id = index.toLong(),
        mediaItemId = "media-1",
        chunkIndex = index,
        telegramFileId = "tg-file-$index",
        telegramMessageId = 1000L + index,
        byteOffset = index.toLong() * 1024,
        byteLength = 1024,
        status = ChunkStatus.UPLOADED,
    )

    private fun meta(count: Int) = (0 until count).map { chunk(it) }

    /**
     * OkHttpClient whose every `execute()` call throws IOException immediately,
     * simulating a persistent network error across all retry attempts.
     */
    private fun failingClient(): OkHttpClient {
        val client = mock<OkHttpClient>()
        val call = mock<Call>()
        whenever(client.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenThrow(IOException("Simulated network failure"))
        return client
    }

    /**
     * OkHttpClient that returns HTTP 200 with an empty body.
     * Mocks [ResponseBody.bytes], [ResponseBody.byteStream], and
     * [ResponseBody.source] to cover whichever body-reading strategy the
     * implementation uses.
     */
    private fun successClient(): OkHttpClient {
        val client = mock<OkHttpClient>()
        val call = mock<Call>()
        val response = mock<Response>()
        val body = mock<ResponseBody>()
        whenever(client.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)
        whenever(response.isSuccessful).thenReturn(true)
        whenever(response.code).thenReturn(200)
        whenever(response.body).thenReturn(body)
        whenever(body.bytes()).thenReturn(byteArrayOf())
        whenever(body.byteStream()).thenReturn(byteArrayOf().inputStream())
        whenever(body.contentLength()).thenReturn(0L)
        // okio.Buffer is an empty, immediately-exhausted BufferedSource
        whenever(body.source()).thenReturn(Buffer())
        return client
    }

    // =========================================================================
    // Test 1 — constructor accepts urlResolver suspend lambda, NOT a URL list
    // =========================================================================

    /**
     * RED: The current constructor takes `resolvedUrls: List<String?>`.
     * This test will NOT compile against the current code because the named
     * parameter `urlResolver` does not exist.
     *
     * GREEN: Compiles and constructs without error once the new constructor
     * signature is in place.
     */
    @Test
    fun `constructor accepts suspend urlResolver lambda instead of resolved URL list`() = runTest {
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(2),
            urlResolver = { index -> "https://cdn4.cdn-telegram.org/file/chunk-$index.dat" },
            okHttpClient = failingClient(),
        )
        assertNotNull("Engine must be constructable with a urlResolver lambda", engine)
        engine.release()
    }

    // =========================================================================
    // Test 2 — non-HTTPS URL fails CDN validation → waitForChunk throws IOException
    // =========================================================================

    /**
     * RED: The current implementation does not validate the URL scheme, so
     * `waitForChunk` returns silently even for a plaintext `http://` URL.
     *
     * GREEN: The engine rejects non-HTTPS URLs as invalid CDN endpoints,
     * marks chunk 0 as permanently failed, and `waitForChunk(0)` throws
     * [IOException].
     */
    @Test(timeout = 10_000)
    fun `waitForChunk throws IOException when urlResolver returns a non-HTTPS URL`() = runTest {
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(1),
            urlResolver = { "http://cdn4.cdn-telegram.org/file/abc.dat" }, // plain HTTP — invalid
            okHttpClient = mock<OkHttpClient>(), // must never be reached
        )
        engine.setPrefetchOrigin(0)
        try {
            engine.waitForChunk(0)
            fail(
                "Expected IOException — a non-HTTPS CDN URL must be treated as a permanent " +
                    "failure and waitForChunk must throw, not return silently.",
            )
        } catch (e: IOException) {
            // Expected: CDN validation correctly rejected plain HTTP.
        } finally {
            engine.release()
        }
    }

    // =========================================================================
    // Test 3 — all HTTP attempts throw IOException → waitForChunk throws IOException
    // =========================================================================

    /**
     * RED: After exhausting all retry attempts the current `waitForChunk`
     * returns without throwing, leaving callers unable to detect permanent
     * failure.
     *
     * GREEN: `waitForChunk` propagates the failure as [IOException] once
     * every retry attempt has been exhausted.
     */
    @Test(timeout = 30_000)
    fun `waitForChunk throws IOException after all download retries are exhausted`() = runTest {
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(1),
            urlResolver = { "https://cdn4.cdn-telegram.org/file/abc.dat" },
            okHttpClient = failingClient(),
        )
        engine.setPrefetchOrigin(0)
        try {
            engine.waitForChunk(0)
            fail(
                "Expected IOException — the engine must propagate permanent download failure " +
                    "via IOException, not silently return. The current code is missing this " +
                    "IOException throw in waitForChunk.",
            )
        } catch (e: IOException) {
            // Expected: permanent failure surfaced correctly.
        } finally {
            engine.release()
        }
    }

    // =========================================================================
    // Test 4 — generation counter: setPrefetchOrigin clears stale failed state
    // =========================================================================

    /**
     * RED: The current code has no generation counter. A chunk that failed in
     * one prefetch round stays in the "permanently failed" state even after a
     * new `setPrefetchOrigin` call, so the second `waitForChunk(0)` immediately
     * rethrows the stale IOException.
     *
     * GREEN: Each `setPrefetchOrigin` call increments an internal generation
     * counter that wipes all previously-cached failure states. With the stale
     * failure cleared, the new download attempt uses the generation-1 resolver
     * (which returns a valid HTTPS URL) and the mock HTTP client succeeds, so
     * `waitForChunk(0)` completes without throwing.
     */
    @Test(timeout = 30_000)
    fun `setPrefetchOrigin clears stale failed state for a new generation`() = runTest {
        val generation = AtomicInteger(0)

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(1),
            urlResolver = { _ ->
                // Generation 0: plain HTTP → rejected by CDN validation before any HTTP call.
                // Generation 1: valid HTTPS → the successClient mock will serve HTTP 200.
                if (generation.get() == 0) "http://cdn4.cdn-telegram.org/file/abc.dat"
                else "https://cdn4.cdn-telegram.org/file/abc.dat"
            },
            okHttpClient = successClient(), // ready to succeed once a valid URL is resolved
        )

        // --- Generation 0: chunk 0 must enter permanently-failed state ---
        engine.setPrefetchOrigin(0)
        var gen0Failed = false
        try {
            engine.waitForChunk(0)
        } catch (e: IOException) {
            gen0Failed = true
        }
        assertTrue("Chunk 0 must have failed in generation 0 (non-HTTPS URL)", gen0Failed)

        // --- Advance resolver to generation 1 and reset the prefetch origin ---
        generation.set(1)
        engine.setPrefetchOrigin(0) // must clear the generation-0 failure and start fresh

        // --- Generation 1: state cleared, valid URL, mock succeeds — must NOT throw ---
        try {
            engine.waitForChunk(0)
            // Green path: stale failure was cleared by the generation counter.
        } catch (e: IOException) {
            fail(
                "waitForChunk threw IOException in generation 1. The stale failed state was " +
                    "NOT cleared by the second setPrefetchOrigin call. The current code " +
                    "lacks a generation counter.",
            )
        } finally {
            engine.release()
        }
    }

    // =========================================================================
    // Test 5 — urlResolver called lazily: only chunks inside the prefetch window
    // =========================================================================

    /**
     * RED: The current constructor takes a pre-resolved `List<String?>`, so
     * there is no lazy resolver call-site to verify. This test will not compile
     * (same constructor mismatch as Test 1).
     *
     * GREEN: `urlResolver` is invoked on-demand, exclusively for chunks that
     * fall inside the prefetch window `[origin, origin + windowSize)`. Chunks
     * outside that window must not trigger a resolver invocation.
     */
    @Test(timeout = 10_000)
    fun `urlResolver is invoked only for chunks inside the prefetch window`() {
        val totalChunks = 5
        val windowSize = 2
        val callCounts = Array(totalChunks) { AtomicInteger(0) }

        // Countdown fires as soon as the background prefetch for chunk 0 invokes its resolver.
        val chunk0Latch = CountDownLatch(1)

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(totalChunks),
            urlResolver = { index ->
                callCounts[index].incrementAndGet()
                if (index == 0) chunk0Latch.countDown()
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = failingClient(), // downloads fail; resolver invocation is what we measure
            windowSize = windowSize,
        )

        // Window = [0, 2): should only prefetch chunks 0 and 1.
        engine.setPrefetchOrigin(0)

        val chunk0Started = chunk0Latch.await(5, TimeUnit.SECONDS)
        assertTrue(
            "urlResolver for chunk 0 was never called — setPrefetchOrigin must trigger " +
                "lazy prefetch for all chunks inside the window",
            chunk0Started,
        )

        // Short grace period so both in-window resolvers (indices 0 and 1) can register.
        Thread.sleep(400)

        assertTrue(
            "urlResolver must be called for chunk 0 (origin=0, windowSize=$windowSize)",
            callCounts[0].get() > 0,
        )
        assertTrue(
            "urlResolver must be called for chunk 1 (inside window [0, $windowSize))",
            callCounts[1].get() > 0,
        )

        // Chunks outside the window must never have their resolver invoked.
        assertEquals(
            "urlResolver must NOT be called for chunk 2 (outside window)",
            0, callCounts[2].get(),
        )
        assertEquals(
            "urlResolver must NOT be called for chunk 3 (outside window)",
            0, callCounts[3].get(),
        )
        assertEquals(
            "urlResolver must NOT be called for chunk 4 (outside window)",
            0, callCounts[4].get(),
        )

        engine.release()
    }
}
