package com.ulap.data.remote

import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.debug.DebugLogBuffer
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Bug Reproduction Test — BackupIndexManager must survive transport-level failures
 * from the Telegram Bot API without throwing.
 *
 * ## Defect
 *
 * When `TelegramBotApi.getChat` returns a non-2xx HTTP response, Retrofit throws
 * `retrofit2.HttpException`.  `TelegramRateLimiter.withRateLimit` only catches
 * `TelegramRateLimitException`, so `HttpException` propagates up uncaught out of
 * both `fetchAndMerge` and `loadPinnedIndexEntries`.  Because `SyncEngine.runUploadPipeline`
 * wraps these calls in a `try { … } finally { … }` with no `catch`, and `engineScope`
 * has no `CoroutineExceptionHandler`, the exception kills the process with:
 *
 *     FATAL EXCEPTION: DefaultDispatcher-worker-4
 *     retrofit2.HttpException: HTTP 400
 *
 * Similarly, any `IOException` (ConnectionReset, SocketTimeout, UnknownHost, DNS failure)
 * escaping from the Retrofit call has the same crash path.
 *
 * ## Contract encoded by this test
 *
 * 1. `fetchAndMerge` must NEVER throw for transport exceptions from `api.getChat`.
 *    It must return `Result.failure` so the caller can log-and-continue.
 * 2. `loadMessageIdsFromPinnedIndex` already swallows `Result.failure` into an
 *    empty list; it must also not propagate raw transport exceptions that leak
 *    out of the underlying `loadPinnedIndexEntries` call.
 *
 * Deterministic: no network — `TelegramBotApi` and `TelegramRateLimiter` are mocked.
 */
class BackupIndexManagerHttpExceptionBrt {

    private val mediaItemDao: MediaItemDao = mock()
    private val chunkMetadataDao: ChunkMetadataDao = mock()
    private val downloader: TelegramDownloader = mock()
    private val debugLog: DebugLogBuffer = mock()

    private fun buildManager(api: TelegramBotApi): BackupIndexManager =
        BackupIndexManager(
            mediaItemDao = mediaItemDao,
            chunkMetadataDao = chunkMetadataDao,
            api = api,
            rateLimiter = passthroughRateLimiter(),
            downloader = downloader,
            debugLog = debugLog,
        )

    /**
     * A rate limiter that just invokes the provided block — same pattern used in every
     * other test in this module.  Generic erasure forces the `any<suspend () -> Any?>`
     * matcher; cast the argument back inside the answer.
     */
    @Suppress("UNCHECKED_CAST")
    private fun passthroughRateLimiter(): TelegramRateLimiter = mock { rl ->
        onBlocking {
            rl.withRateLimit(any<suspend () -> Any?>())
        }.doSuspendableAnswer { inv ->
            (inv.getArgument(0) as suspend () -> Any?).invoke()
        }
    }

    private fun http400(): HttpException =
        HttpException(
            Response.error<Any>(
                400,
                "Bad Request".toResponseBody(null),
            ),
        )

    // ── fetchAndMerge — the exact crash site from the Redmi 25053RT47C report ──────────

    @Test
    fun fetchAndMerge_whenGetChatThrowsHttpException400_returnsFailure_doesNotThrow() = runTest {
        val api: TelegramBotApi = mock()
        whenever(api.getChat(any(), any())).doSuspendableAnswer { throw http400() }

        val manager = buildManager(api)

        val result = runCatching { manager.fetchAndMerge(token = "123:fake", chatId = "-100") }
        assertTrue(
            "fetchAndMerge must not throw HttpException — it must return Result.failure " +
                "(crashed at retrofit2.KotlinExtensions\$await\$2\$2.onResponse).  Got exception=${result.exceptionOrNull()}",
            result.isSuccess,
        )

        val inner = result.getOrNull()!!
        assertTrue(
            "fetchAndMerge must return Result.failure when the underlying Retrofit call fails with HttpException 400",
            inner.isFailure,
        )
    }

    @Test
    fun fetchAndMerge_whenGetChatThrowsIOException_returnsFailure_doesNotThrow() = runTest {
        val api: TelegramBotApi = mock()
        whenever(api.getChat(any(), any())).doSuspendableAnswer {
            throw IOException("Connection reset")
        }

        val manager = buildManager(api)

        val result = runCatching { manager.fetchAndMerge(token = "123:fake", chatId = "-100") }
        assertTrue(
            "fetchAndMerge must not propagate IOException (e.g. ConnectionReset / SocketTimeout) — " +
                "it must return Result.failure.  Got exception=${result.exceptionOrNull()}",
            result.isSuccess,
        )

        assertTrue(
            "fetchAndMerge must return Result.failure when the underlying Retrofit call fails with IOException",
            result.getOrNull()!!.isFailure,
        )
    }

    // ── loadMessageIdsFromPinnedIndex — the "delete all backups" entry point ───────────

    @Test
    fun loadMessageIdsFromPinnedIndex_whenGetChatThrowsHttpException_returnsEmptyList_doesNotThrow() = runTest {
        val api: TelegramBotApi = mock()
        whenever(api.getChat(any(), any())).doSuspendableAnswer { throw http400() }

        val manager = buildManager(api)

        val result = runCatching { manager.loadMessageIdsFromPinnedIndex(token = "123:fake", chatId = "-100") }
        assertTrue(
            "loadMessageIdsFromPinnedIndex must swallow HttpException and return an empty list " +
                "so 'delete all backups' never crashes the app.  Got exception=${result.exceptionOrNull()}",
            result.isSuccess,
        )
        assertTrue(
            "expected empty id list when pinned index cannot be loaded",
            result.getOrNull()!!.isEmpty(),
        )
    }

    @Test
    fun loadMessageIdsFromPinnedIndex_whenGetChatThrowsIOException_returnsEmptyList_doesNotThrow() = runTest {
        val api: TelegramBotApi = mock()
        whenever(api.getChat(any(), any())).doSuspendableAnswer {
            throw IOException("socket timed out")
        }

        val manager = buildManager(api)

        val result = runCatching { manager.loadMessageIdsFromPinnedIndex(token = "123:fake", chatId = "-100") }
        assertTrue(
            "loadMessageIdsFromPinnedIndex must swallow IOException — got exception=${result.exceptionOrNull()}",
            result.isSuccess,
        )
        assertFalse(
            "after catching IOException, an empty list is the correct outcome",
            result.getOrNull()!!.isNotEmpty(),
        )
    }
}
