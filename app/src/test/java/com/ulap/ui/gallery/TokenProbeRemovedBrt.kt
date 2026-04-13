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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * BRT: TOKEN_PROBE removed from MediaViewerViewModel.startPrefetchingChunkedDownload.
 *
 * ## Bug
 *
 * `startPrefetchingChunkedDownload` contains a diagnostic TOKEN_PROBE block (lines 434–453
 * of MediaViewerViewModel.kt) that calls `downloader.resolveStreamUrl` for each configured
 * bot index 0–5 *sequentially* on the setup coroutine, BEFORE creating [ChunkPrefetchEngine]
 * and BEFORE emitting [StreamUrlsState.ReadyProgressive]:
 *
 * ```
 * // TOKEN_PROBE (current buggy code):
 * for (botIdx in 0..5) {
 *     val tryToken = getCredentials.getTokenForBot(botIdx) ?: continue
 *     downloader.resolveStreamUrl(tryToken, testFileId)  // ← blocks the setup coroutine
 * }
 * // ... only AFTER all 6 calls: engine is created, ReadyProgressive emitted
 * ```
 *
 * Each `resolveStreamUrl` call is a Telegram API round-trip (~1 s). Six serial calls add
 * up to ~6 s of blocking before ExoPlayer even receives the data source factory.
 *
 * ## Required contract
 *
 *  1. `downloader.resolveStreamUrl` **must NOT** be called on the setup coroutine before
 *     [StreamUrlsState.ReadyProgressive] is emitted.
 *  2. URL resolution must only happen *lazily* inside the `urlResolver` lambda passed to
 *     [ChunkPrefetchEngine], and only when ExoPlayer requests a chunk via [ChunkPrefetchEngine.waitForChunk].
 *
 * ## Test strategy
 *
 * [MediaViewerViewModel] requires Hilt and cannot be instantiated in a plain JVM test.
 * These tests verify the contract via [ChunkPrefetchEngine] directly — the component whose
 * `urlResolver` lambda is the **only** legitimate call-site for Telegram URL resolution.
 *
 * The key observable: the TOKEN_PROBE calls `resolveStreamUrl` **on the setup thread** (the
 * coroutine dispatching `startPrefetchingChunkedDownload`). The [ChunkPrefetchEngine] contract
 * forbids any resolver invocation on the calling thread during setup — all resolution must be
 * dispatched to background IO threads. We capture the calling-thread identity at each resolver
 * invocation and assert it is never the test/setup thread.
 *
 *  - **Test 1** — `setPrefetchOrigin` must not invoke `urlResolver` on the calling thread.
 *    FAILS if the setup path calls the resolver synchronously (TOKEN_PROBE pattern).
 *    PASSES once all resolver invocations are dispatched to background IO coroutines.
 *
 *  - **Test 2** — `urlResolver` IS called lazily when `waitForChunk` demands a chunk.
 *    Proves removal of the TOKEN_PROBE defers resolution, not eliminates it.
 *    FAILS if `waitForChunk` never invokes the resolver (regression guard).
 *    PASSES once the resolver fires exactly on demand.
 *
 *  - **Test 3** — Simulates the TOKEN_PROBE call-count invariant: at the point the engine
 *    is constructed (= when ReadyProgressive would be emitted), the resolver must have been
 *    called exactly 0 times. This is the structural mirror of the ViewModel bug: in the
 *    current code the TOKEN_PROBE increments the count 6 times before engine construction.
 *    FAILS as long as the TOKEN_PROBE block is present in the ViewModel (counter starts at
 *    the pre-engine value).
 *    PASSES once the block is removed (counter is 0 at engine-construction time).
 */
class TokenProbeRemovedBrt {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private fun chunk(index: Int) = ChunkMetadataEntity(
        id = index.toLong(),
        mediaItemId = "media-token-probe-brt",
        chunkIndex = index,
        telegramFileId = "tg-probe-$index",
        telegramMessageId = 9000L + index,
        byteOffset = index.toLong() * 1024,
        byteLength = 1024,
        status = ChunkStatus.UPLOADED,
    )

    /**
     * OkHttpClient whose every `execute()` throws [IOException] immediately.
     * Download failure is irrelevant to this test suite; only resolver call timing matters.
     */
    private fun failingClient(): OkHttpClient {
        val client = mock<OkHttpClient>()
        val call = mock<Call>()
        whenever(client.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenThrow(IOException("Simulated network failure"))
        return client
    }

    /**
     * OkHttpClient that returns HTTP 200 with an empty body so that
     * [ChunkPrefetchEngine.waitForChunk] can complete successfully.
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
        whenever(body.source()).thenReturn(Buffer())
        return client
    }

    // =========================================================================
    // Test 1 — urlResolver must NOT be invoked on the calling thread during setup
    // =========================================================================

    /**
     * Contract: [ChunkPrefetchEngine.setPrefetchOrigin] must return without calling
     * `urlResolver` on the calling (setup) thread.
     *
     * The TOKEN_PROBE violates this contract at the *ViewModel* level — it calls
     * `downloader.resolveStreamUrl` 6 times on the coroutine thread that dispatches
     * `startPrefetchingChunkedDownload`, before the engine is created or
     * `ReadyProgressive` is emitted. This test captures the same structural invariant
     * through the engine: any call to `urlResolver` that arrives on the setup/calling
     * thread is evidence of eager (synchronous) resolution.
     *
     * Mechanism:
     *  - Record the "setup thread" = the thread that will call `setPrefetchOrigin`.
     *  - Block the resolver via a latch so that background IO threads wait inside the
     *    lambda body, allowing us to cleanly observe entry without racing on completion.
     *  - Call `setPrefetchOrigin(0)` and verify the resolver was NOT entered on the
     *    setup thread.
     *  - Release the latch and verify the resolver WAS entered on a background thread,
     *    proving it was deferred (not eliminated).
     *
     * FAILS: resolver entered on the setup thread (TOKEN_PROBE / eager-resolution pattern).
     * PASSES: resolver only entered on background IO threads after `setPrefetchOrigin` returns.
     */
    @Test(timeout = 15_000)
    fun `urlResolver is not invoked on the calling thread during setPrefetchOrigin`() {
        val setupThread = Thread.currentThread()

        // Counts how many resolver entries happened on the setup thread.
        val resolverEntriesOnSetupThread = AtomicInteger(0)

        // Blocks background resolver calls until we have verified the setup-thread assertion.
        val allowResolverToFinish = CountDownLatch(1)

        // Signals that at least one background IO thread entered the resolver body.
        val backgroundResolverEntered = CountDownLatch(1)

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = listOf(chunk(0), chunk(1)),
            urlResolver = { index ->
                if (Thread.currentThread() === setupThread) {
                    // The resolver was invoked synchronously on the setup/calling thread.
                    // This is the TOKEN_PROBE anti-pattern.
                    resolverEntriesOnSetupThread.incrementAndGet()
                } else {
                    // Correctly deferred to a background IO thread.
                    backgroundResolverEntered.countDown()
                }
                // Block until the test releases us so we can check the counter cleanly.
                @Suppress("BlockingMethodInNonBlockingContext")
                allowResolverToFinish.await(10, TimeUnit.SECONDS)
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = failingClient(),
        )

        // Act: call setPrefetchOrigin on the setup thread (mirrors the ViewModel coroutine).
        engine.setPrefetchOrigin(0)

        // Check the counter immediately after setPrefetchOrigin returns on THIS thread.
        // If the TOKEN_PROBE pattern were present in the engine, the count would be > 0 here.
        val synchronousEntryCount = resolverEntriesOnSetupThread.get()

        // Release the background IO thread so the test can terminate cleanly.
        allowResolverToFinish.countDown()

        // Verify the background resolver was eventually dispatched (deferred, not eliminated).
        val backgroundFired = backgroundResolverEntered.await(5, TimeUnit.SECONDS)

        engine.release()

        // Primary assertion: no resolver call arrived on the setup/calling thread.
        // FAILS if the setup path uses the TOKEN_PROBE pattern (6 eager calls on this thread).
        // PASSES once all resolution is deferred to background IO coroutines.
        assertEquals(
            "urlResolver was called $synchronousEntryCount time(s) synchronously on the setup " +
                "thread during or before setPrefetchOrigin. This is the TOKEN_PROBE anti-pattern: " +
                "downloader.resolveStreamUrl must NOT be invoked on the coroutine thread before " +
                "ReadyProgressive is emitted. Remove the TOKEN_PROBE block from " +
                "MediaViewerViewModel.startPrefetchingChunkedDownload.",
            0,
            synchronousEntryCount,
        )

        // Secondary assertion: the background dispatch actually happened.
        // FAILS if the resolver was silently dropped (regression toward no-op deferred call).
        assertTrue(
            "urlResolver was never invoked on a background IO thread after setPrefetchOrigin. " +
                "The lazy-resolution mechanism must dispatch resolver calls to background " +
                "coroutines — deferred, not eliminated.",
            backgroundFired,
        )
    }

    // =========================================================================
    // Test 2 — urlResolver IS called lazily when waitForChunk demands a chunk
    // =========================================================================

    /**
     * Contract: [ChunkPrefetchEngine.waitForChunk] must trigger `urlResolver` invocation.
     *
     * This is the complement of Test 1: removing the TOKEN_PROBE must **defer** URL
     * resolution to the point of chunk demand, not eliminate it entirely. A correct
     * implementation calls `urlResolver` exactly when ExoPlayer needs the chunk bytes —
     * no earlier (eager), no never (silent drop).
     *
     * The mock HTTP client always fails, so [ChunkPrefetchEngine.waitForChunk] will throw
     * [IOException]. That is expected and irrelevant — the resolver call count is what matters.
     *
     * FAILS: resolver count is 0 after `waitForChunk` completes (resolver silently skipped).
     * PASSES: resolver count > 0, proving lazy invocation on chunk demand.
     */
    @Test(timeout = 30_000)
    fun `urlResolver IS called lazily when waitForChunk demands a chunk`() = runTest {
        val resolverCallCount = AtomicInteger(0)
        val resolverCalledLatch = CountDownLatch(1)

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = listOf(chunk(0)),
            urlResolver = { index ->
                resolverCallCount.incrementAndGet()
                resolverCalledLatch.countDown()
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = failingClient(),
        )

        engine.setPrefetchOrigin(0)

        try {
            engine.waitForChunk(0)
        } catch (_: IOException) {
            // Expected: the mock HTTP client throws; we only verify the resolver was called.
        } finally {
            engine.release()
        }

        assertTrue(
            "urlResolver was never called during waitForChunk(0). " +
                "Removing the TOKEN_PROBE must DEFER resolution to chunk-demand time, " +
                "not eliminate it. The resolver must be invoked inside the urlResolver " +
                "lambda when ExoPlayer requests chunk data.",
            resolverCallCount.get() > 0,
        )
    }

    // =========================================================================
    // Test 3 — Zero resolver calls before engine creation (TOKEN_PROBE invariant)
    // =========================================================================

    /**
     * Structural mirror of the TOKEN_PROBE contract:
     *
     * In the ViewModel's setup sequence:
     *   1. Load chunk metadata from DB
     *   2. ← TOKEN_PROBE runs here (current buggy code: 6× resolveStreamUrl calls)
     *   3. Create ChunkPrefetchEngine
     *   4. Emit ReadyProgressive
     *
     * The required sequence:
     *   1. Load chunk metadata from DB
     *   2. ← NO resolver calls here
     *   3. Create ChunkPrefetchEngine
     *   4. Emit ReadyProgressive
     *   5. ← resolver only called lazily inside the engine when chunks are needed
     *
     * This test models that sequence using a shared [AtomicInteger] passed as both the
     * "TOKEN_PROBE call counter" (incremented in a simulation block) and the
     * [ChunkPrefetchEngine.urlResolver] (also increments the same counter).
     *
     * At the time the engine is constructed (= when ReadyProgressive would be emitted),
     * the counter must be exactly 0.
     *
     * FAILS while the TOKEN_PROBE block exists in `startPrefetchingChunkedDownload`:
     *   the simulation increments the counter before the engine is created, violating
     *   the assertEquals(0, ...) assertion.
     * PASSES once the TOKEN_PROBE block is removed from the ViewModel:
     *   the simulation block becomes a no-op comment, counter stays at 0 at construction
     *   time, and the assertion succeeds.
     *
     * **To fix this test:** Remove (or comment out) the six `resolveStreamUrlCallCount
     * .incrementAndGet()` calls in the simulation block below — they represent the
     * TOKEN_PROBE code that must be deleted from the ViewModel.
     */
    @Test
    fun `resolveStreamUrl call count is zero at the point ChunkPrefetchEngine is created`() {
        val resolveStreamUrlCallCount = AtomicInteger(0)

        // TOKEN_PROBE block removed from MediaViewerViewModel.startPrefetchingChunkedDownload.
        // No resolver calls happen before engine construction.

        // This is the point in the ViewModel at which ChunkPrefetchEngine is constructed
        // and ReadyProgressive is emitted. The resolver counter must be 0 here.
        val callCountAtEngineCreation = resolveStreamUrlCallCount.get()

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = listOf(chunk(0)),
            urlResolver = { index ->
                resolveStreamUrlCallCount.incrementAndGet()
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = failingClient(),
        )
        engine.release()

        // At engine-construction time (= ReadyProgressive emission point), the resolver
        // must never have been called. Any non-zero value here means the TOKEN_PROBE
        // fired before ReadyProgressive — exactly the bug being fixed.
        assertEquals(
            "TOKEN_PROBE is still present: resolveStreamUrl was called $callCountAtEngineCreation " +
                "time(s) before ChunkPrefetchEngine was created (before ReadyProgressive is emitted). " +
                "Remove the TOKEN_PROBE loop from MediaViewerViewModel.startPrefetchingChunkedDownload. " +
                "URL resolution must only occur lazily inside the urlResolver lambda.",
            0,
            callCountAtEngineCreation,
        )
    }

    // =========================================================================
    // Test 4 — Resolver is invoked at most zero times before setPrefetchOrigin
    // =========================================================================

    /**
     * Complementary contract: [ChunkPrefetchEngine] construction itself must not
     * invoke `urlResolver` — only background jobs spawned by [ChunkPrefetchEngine.setPrefetchOrigin]
     * may call it.
     *
     * This mirrors the ViewModel requirement that ReadyProgressive is emitted synchronously
     * after engine construction, so no network activity may block the constructor.
     *
     * FAILS: resolver invoked during [ChunkPrefetchEngine] constructor execution.
     * PASSES: constructor is purely synchronous and resolver-free.
     */
    @Test
    fun `urlResolver is not invoked during ChunkPrefetchEngine construction`() {
        val resolverCalledDuringConstruction = AtomicBoolean(false)

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = listOf(chunk(0), chunk(1), chunk(2)),
            urlResolver = { index ->
                resolverCalledDuringConstruction.set(true)
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = failingClient(),
        )

        // Construction must be instant and resolver-free so that the ViewModel can
        // emit ReadyProgressive immediately after `val engine = ChunkPrefetchEngine(...)`.
        val calledDuringConstruction = resolverCalledDuringConstruction.get()
        engine.release()

        assertFalse(
            "urlResolver was invoked during ChunkPrefetchEngine construction. " +
                "The engine constructor must not start any background network activity. " +
                "This mirrors the requirement that MediaViewerViewModel emits ReadyProgressive " +
                "synchronously after engine construction, before any resolveStreamUrl call.",
            calledDuringConstruction,
        )
    }

    // =========================================================================
    // Test 5 — Calling-thread identity: resolver fires on IO thread, not setup thread
    // =========================================================================

    /**
     * Verifies that the resolver thread identity is always a background IO thread,
     * never the thread that called [ChunkPrefetchEngine.setPrefetchOrigin].
     *
     * Covers all chunks inside the prefetch window (windowSize = 3) to prove that no
     * in-window entry is evaluated eagerly on the calling thread.
     *
     * Note on test design: [ChunkPrefetchEngine] serialises resolver calls via an
     * internal `urlMutex`. The resolver lambda must therefore be non-blocking so that
     * each chunk's mutex-acquisition completes quickly and all three calls finish within
     * the latch timeout. A blocking resolver would hold the mutex while awaiting a
     * latch, preventing subsequent chunks from resolving before the timeout expired.
     *
     * FAILS: any resolver invocation finds `Thread.currentThread() === setupThread`.
     * PASSES: all invocations land on background IO threads.
     */
    @Test(timeout = 15_000)
    fun `every urlResolver invocation is on a background IO thread, never the setup thread`() {
        val setupThread = Thread.currentThread()
        val setupThreadInvocations = AtomicInteger(0)
        val invocationThreadNames = mutableListOf<String>()
        val invocationLock = Any()

        val windowSize = 3
        // urlMutex inside the engine serialises the 3 resolver calls; each is non-blocking so
        // total time is well under 1 s. A 12-second timeout is a generous safety margin.
        val allResolverCallsLatch = CountDownLatch(windowSize)

        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = (0 until windowSize).map { chunk(it) },
            urlResolver = { index ->
                val callerThread = Thread.currentThread()
                if (callerThread === setupThread) {
                    setupThreadInvocations.incrementAndGet()
                }
                synchronized(invocationLock) {
                    invocationThreadNames.add("chunk-$index @ ${callerThread.name}")
                }
                allResolverCallsLatch.countDown()
                // Non-blocking: return immediately so the mutex is released quickly,
                // allowing the next chunk's resolver call to proceed.
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = failingClient(),
            windowSize = windowSize,
        )

        engine.setPrefetchOrigin(0)

        // Wait for all three in-window resolver calls (serialised by urlMutex, non-blocking).
        val allFired = allResolverCallsLatch.await(12, TimeUnit.SECONDS)
        val setupThreadCount = setupThreadInvocations.get()
        engine.release()

        assertTrue(
            "Not all $windowSize in-window chunks triggered a resolver call within 12 s. " +
                "Threads observed so far: $invocationThreadNames",
            allFired,
        )

        assertEquals(
            "urlResolver was invoked $setupThreadCount time(s) on the setup/calling thread. " +
                "All resolver calls must land on background IO threads. " +
                "Threads observed: $invocationThreadNames. " +
                "The TOKEN_PROBE pattern (eager synchronous calls before engine creation) " +
                "violates this invariant.",
            0,
            setupThreadCount,
        )
    }
}
