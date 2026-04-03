package com.ulap.ui.gallery

import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
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

/**
 * BRT suite for the ordering contract of
 * [MediaViewerViewModel.startPrefetchingChunkedDownload].
 *
 * **Bug:** The current implementation calls `resolveStreamUrl` (or
 * `resolveStreamUrlsBatched`) synchronously BEFORE emitting
 * [StreamUrlsState.ReadyProgressive]. ExoPlayer cannot start buffering until
 * all chunk URL resolutions complete, causing 5+ minute startup delays.
 *
 * **Fix contract:** [StreamUrlsState.ReadyProgressive] must be emitted
 * immediately after DB chunks are loaded — BEFORE any `urlResolver` invocation.
 * The `urlResolver` lambda is only invoked lazily by [ChunkPrefetchEngine] in a
 * background coroutine after `ReadyProgressive` is already visible to observers.
 *
 * Because `MediaViewerViewModel` requires Hilt / AndroidX infrastructure that
 * is unavailable in a plain JVM test, these tests verify the ordering contract
 * through [ChunkPrefetchEngine] directly — the component responsible for lazy
 * resolution.  Both tests **fail to compile** against the current
 * `ChunkPrefetchEngine` (whose constructor takes `resolvedUrls: List<String?>`
 * rather than a `urlResolver` suspend lambda).  They **pass** once the new
 * lazy-resolver constructor is in place, which is the prerequisite for the
 * ViewModel fix.
 */
class MediaViewerViewModelReadyProgressiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Fixture helpers (mirrors ChunkPrefetchEngineTest conventions)
    // -------------------------------------------------------------------------

    private fun chunk(index: Int) = ChunkMetadataEntity(
        id = index.toLong(),
        mediaItemId = "media-vm-brt",
        chunkIndex = index,
        telegramFileId = "tg-vm-$index",
        telegramMessageId = 2000L + index,
        byteOffset = index.toLong() * 1024,
        byteLength = 1024,
        status = ChunkStatus.UPLOADED,
    )

    /**
     * OkHttpClient whose every `execute()` call throws [IOException] immediately.
     * Downloads will always fail, but the resolver-invocation timing — the
     * property these tests measure — is unaffected by download success.
     */
    private fun failingClient(): OkHttpClient {
        val client = mock<OkHttpClient>()
        val call = mock<Call>()
        val response = mock<Response>()
        whenever(client.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenThrow(IOException("Simulated network failure"))
        whenever(response.isSuccessful).thenReturn(false)
        return client
    }

    // =========================================================================
    // Test 1 — Construction must not invoke urlResolver synchronously
    // =========================================================================

    /**
     * RED: The current [ChunkPrefetchEngine] constructor takes
     * `resolvedUrls: List<String?>` — the named parameter `urlResolver` does
     * not exist, so this test does **not compile** against the current code.
     *
     * GREEN: Compiles and passes once the constructor is changed to accept a
     * `urlResolver: suspend (Int) -> String` lambda.  At that point the engine
     * must not invoke the lambda during object construction — establishing the
     * non-blocking setup path that lets [MediaViewerViewModel] emit
     * `ReadyProgressive` before any network activity begins.
     *
     * **Why this reflects the ViewModel bug:** With the OLD ViewModel, URL
     * resolution happens synchronously during setup, blocking `ReadyProgressive`
     * from being emitted.  With the FIX, construction of the engine (the setup
     * step) must be instant and resolver-free.
     */
    @Test
    fun `urlResolver is NOT invoked during ChunkPrefetchEngine construction`() = runTest {
        val resolverCalledDuringConstruction = AtomicBoolean(false)

        // RED: `urlResolver = { ... }` does not compile against the old constructor.
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = listOf(chunk(0), chunk(1), chunk(2)),
            urlResolver = { index ->
                resolverCalledDuringConstruction.set(true)
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = failingClient(),
        )

        // Construction must be synchronous and resolver-free so that
        // ReadyProgressive can be emitted immediately afterward.
        assertFalse(
            "urlResolver was invoked during ChunkPrefetchEngine construction. " +
                "The engine must be constructable without any resolver call, mirroring " +
                "the requirement that MediaViewerViewModel emits ReadyProgressive BEFORE " +
                "any resolveStreamUrl invocation.",
            resolverCalledDuringConstruction.get(),
        )

        engine.release()
    }

    // =========================================================================
    // Test 2 — setPrefetchOrigin must return immediately; resolver is async
    // =========================================================================

    /**
     * RED: The current [ChunkPrefetchEngine] constructor takes
     * `resolvedUrls: List<String?>` — the named parameter `urlResolver` does
     * not exist, so this test does **not compile** against the current code.
     *
     * GREEN: Compiles and passes once the new constructor and lazy-resolver
     * background coroutine are implemented.  `setPrefetchOrigin` must return in
     * well under 2 seconds (it must not block on network I/O), AND the resolver
     * must eventually be invoked in the background within 5 seconds (proving
     * the background prefetch was actually launched, not silently skipped).
     *
     * **Why this reflects the ViewModel bug:** The old ViewModel called URL
     * resolution synchronously, so `setPrefetchOrigin` (or its equivalent
     * setup call) would block the coroutine that is also responsible for
     * emitting `ReadyProgressive`.  With the fix, setup is instant so
     * `ReadyProgressive` can precede any network call.
     */
    @Test(timeout = 15_000)
    fun `setPrefetchOrigin returns immediately and invokes urlResolver asynchronously`() {
        val resolverLatch = CountDownLatch(1)

        // RED: `urlResolver = { ... }` does not compile against the old constructor.
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = listOf(chunk(0)),
            urlResolver = { index ->
                resolverLatch.countDown()
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = failingClient(),
        )

        // Call setPrefetchOrigin on a separate thread so we can impose a wall-clock
        // timeout without blocking the test thread.
        val setPrefetchOriginReturned = AtomicBoolean(false)
        val setOriginThread = Thread {
            engine.setPrefetchOrigin(0)
            setPrefetchOriginReturned.set(true)
        }
        setOriginThread.start()
        setOriginThread.join(2_000)

        assertTrue(
            "setPrefetchOrigin did not return within 2 seconds. It must return immediately " +
                "without waiting for urlResolver. This mirrors the requirement that " +
                "MediaViewerViewModel must emit ReadyProgressive before any resolver " +
                "invocation — blocking setup prevents that ordering.",
            setPrefetchOriginReturned.get(),
        )

        // Verify the background prefetch actually started: resolver must fire eventually.
        val resolverEventuallyCalled = resolverLatch.await(5, TimeUnit.SECONDS)
        assertTrue(
            "urlResolver was never invoked after setPrefetchOrigin returned. " +
                "The engine must schedule background prefetch so that chunk URLs are " +
                "resolved lazily after ReadyProgressive has been emitted.",
            resolverEventuallyCalled,
        )

        engine.release()
    }
}
