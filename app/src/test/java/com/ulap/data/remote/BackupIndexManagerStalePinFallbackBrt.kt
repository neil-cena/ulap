package com.ulap.data.remote

import com.google.gson.Gson
import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.debug.DebugLogBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.OutputStream

/**
 * Bug Reproduction Test — fetchAndMerge must fall back to the locally cached
 * lastIndexFileId when the Telegram chat has no valid pinned index.
 *
 * ## Defect
 *
 * When `pinChatMessage` fails silently after a backup, the index document is
 * uploaded but not pinned.  The next device to call `fetchAndMerge` reads the
 * chat's `pinnedMessage`, finds either nothing or a stale doc, and returns
 * `Result.success(0)` — silently skipping all recently backed-up items.
 *
 * `SyncEngine` already persists the last successfully uploaded index `file_id`
 * via `credentialRepository.setLastIndexFileId(fileId)`, but `fetchAndMerge`
 * never consults it, so this valuable local knowledge is wasted.
 *
 * ## Contract encoded by this test
 *
 * 1. When the pinned message is missing/invalid AND a `fallbackFileId` is
 *    supplied, `fetchAndMerge` must download from that `fallbackFileId` and
 *    return the merged item count (> 0).
 * 2. When the pinned message is missing AND no `fallbackFileId` is supplied,
 *    `fetchAndMerge` must return `Result.success(0)` without touching the
 *    downloader (safe no-op, backward compatible).
 * 3. When the pinned message IS valid, `fetchAndMerge` must use the pinned
 *    doc's `file_id`; the downloader stub only succeeds for the pinned fileId —
 *    so `Result.success(1)` proves the correct path was taken.
 *
 * Assertion strategy: behavior-based (result value / side effects) rather than
 * `verify` matchers on suspend functions, to avoid `InvalidUseOfMatchersException`
 * from Mockito's implicit Continuation parameter.
 */
class BackupIndexManagerStalePinFallbackBrt {

    private val mediaItemDao: MediaItemDao = mock {
        onBlocking { findExistingTelegramFileIds(any()) } doReturn emptyList()
        onBlocking { findExistingIds(any()) } doReturn emptyList()
    }
    private val chunkMetadataDao: ChunkMetadataDao = mock {
        onBlocking { hasChunks(any()) } doReturn 0
    }
    private val downloader: TelegramDownloader = mock()
    private val debugLog: DebugLogBuffer = mock()
    private val gson = Gson()

    private fun buildManager(api: TelegramBotApi): BackupIndexManager =
        BackupIndexManager(
            mediaItemDao = mediaItemDao,
            chunkMetadataDao = chunkMetadataDao,
            api = api,
            rateLimiter = passthroughRateLimiter(),
            downloader = downloader,
            debugLog = debugLog,
        )

    @Suppress("UNCHECKED_CAST")
    private fun passthroughRateLimiter(): TelegramRateLimiter = mock { rl ->
        onBlocking {
            rl.withRateLimit(any<suspend () -> Any?>())
        }.doSuspendableAnswer { inv ->
            (inv.getArgument(0) as suspend () -> Any?).invoke()
        }
    }

    /** `TelegramResponse` carrying a chat with no pinned message. */
    private fun chatWithNoPin(): TelegramResponse<TelegramChatInfo> =
        TelegramResponse(
            ok = true,
            result = TelegramChatInfo(id = -1001234567890L, type = "supergroup", pinnedMessage = null),
            description = null,
            errorCode = null,
            parameters = null,
        )

    /** `TelegramResponse` carrying a chat whose pinned message is a valid index doc. */
    private fun chatWithPin(pinnedFileId: String): TelegramResponse<TelegramChatInfo> =
        TelegramResponse(
            ok = true,
            result = TelegramChatInfo(
                id = -1001234567890L,
                type = "supergroup",
                pinnedMessage = TelegramMessage(
                    messageId = 42L,
                    document = TelegramDocument(
                        fileId = pinnedFileId,
                        fileSize = null,
                        fileName = "ulap_index_latest.json",
                        mimeType = "application/json",
                        thumbnail = null,
                    ),
                    video = null,
                    photo = null,
                    caption = "[ulap-backup-index]",
                ),
            ),
            description = null,
            errorCode = null,
            parameters = null,
        )

    /**
     * Builds a single-entry manifest JSON for [expectedFileId] and stubs the downloader:
     * - success + writes manifest bytes → when the received fileId == [expectedFileId]
     * - DownloadResult.Error → for any other fileId
     *
     * This allows tests to prove WHICH path was taken purely by inspecting the result value.
     */
    private suspend fun stubDownloaderForFileId(expectedFileId: String) {
        val entry = IndexEntry(
            id = "test-id-1",
            telegramFileId = expectedFileId,
            telegramMessageId = 100L,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            size = 1024L,
            dateTaken = 1_700_000_000_000L,
            bucketName = "Camera",
            mediaType = "IMAGE",
            durationMs = null,
        )
        val json = gson.toJson(IndexManifest(items = listOf(entry))).toByteArray()

        // download(token, fileId, outputStream, onProgress) — four params.
        // any() for the Function2 onProgress parameter is safe since it has a default value.
        whenever(downloader.download(any(), any(), any<OutputStream>(), any())).doSuspendableAnswer { inv ->
            val receivedFileId = inv.getArgument<String>(1)
            if (receivedFileId == expectedFileId) {
                inv.getArgument<OutputStream>(2).write(json)
                DownloadResult.Success
            } else {
                DownloadResult.Error(
                    IllegalStateException(
                        "downloader called with wrong fileId: expected=$expectedFileId received=$receivedFileId",
                    ),
                )
            }
        }
    }

    // ── Test 1: no pinned message + fallback provided → merge from fallback ──

    @Test
    fun fetchAndMerge_whenPinnedMessageMissing_andFallbackProvided_mergesFromFallback() = runTest {
        val fallbackFileId = "FALLBACK_FILE_ID_001"
        val api: TelegramBotApi = mock()
        whenever(api.getChat(any(), any())).doSuspendableAnswer { chatWithNoPin() }
        stubDownloaderForFileId(fallbackFileId)

        val manager = buildManager(api)
        val result = manager.fetchAndMerge(
            token = "123:fake",
            chatId = "-100",
            fallbackFileId = fallbackFileId,
        )

        assertTrue(
            "fetchAndMerge must succeed when fallback fileId is available, got: $result",
            result.isSuccess,
        )
        assertEquals(
            "fetchAndMerge must merge the 1 entry from the fallback index",
            1,
            result.getOrThrow(),
        )
    }

    // ── Test 2: no pinned message + no fallback → success(0), no download ────

    @Test
    fun fetchAndMerge_whenPinnedMessageMissing_andNoFallback_returnsZero() = runTest {
        val api: TelegramBotApi = mock()
        whenever(api.getChat(any(), any())).doSuspendableAnswer { chatWithNoPin() }

        val manager = buildManager(api)
        val result = manager.fetchAndMerge(
            token = "123:fake",
            chatId = "-100",
            fallbackFileId = null,
        )

        assertTrue(
            "fetchAndMerge must succeed (not throw) when no pin and no fallback; got: $result",
            result.isSuccess,
        )
        assertEquals(
            "fetchAndMerge must return 0 when no pinned index and no fallback",
            0,
            result.getOrThrow(),
        )
        // Downloader must not be called when there is nothing to fall back to
        verifyNoInteractions(downloader)
    }

    // ── Test 3: valid pinned message → use pinned fileId, ignore fallback ────
    //
    // The downloader stub writes a manifest ONLY for pinnedFileId.
    // If the implementation incorrectly called fallbackFileId, download returns Error
    // and the result would be Result.failure — proving the wrong path was taken.

    @Test
    fun fetchAndMerge_whenPinnedMessageValid_usesPinnedFileId_ignoresFallback() = runTest {
        val pinnedFileId = "PINNED_FILE_ID_ABC"
        val fallbackFileId = "FALLBACK_FILE_ID_XYZ"
        val api: TelegramBotApi = mock()
        whenever(api.getChat(any(), any())).doSuspendableAnswer { chatWithPin(pinnedFileId) }
        stubDownloaderForFileId(pinnedFileId) // fallbackFileId → DownloadResult.Error

        val manager = buildManager(api)
        val result = manager.fetchAndMerge(
            token = "123:fake",
            chatId = "-100",
            fallbackFileId = fallbackFileId,
        )

        assertTrue(
            "fetchAndMerge must succeed using the pinned file_id; " +
                "fallback would return DownloadResult.Error and produce Result.failure. Got: $result",
            result.isSuccess,
        )
        assertEquals("must merge the 1 entry from the pinned index", 1, result.getOrThrow())
    }
}
