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
import org.mockito.kotlin.whenever
import java.io.OutputStream

/**
 * Bug Reproduction Test — fetchAndMerge must detect a STALE pinned index
 * (pin file_id differs from fallbackFileId) and re-pin + use the fallback.
 *
 * ## Defect
 *
 * The previous fix (BackupIndexManagerStalePinFallbackBrt) only handles the
 * case where the pinned message is ABSENT.  A second failure mode exists:
 *
 *   1. Primary device backs up N items → uploads index (fileId = NEW, msgId = 99)
 *   2. `pinChatMessage(NEW)` fails silently → pin still points to fileId = OLD
 *   3. Primary device stores `lastIndexFileId = NEW`, `lastIndexMessageId = 99`
 *   4. Any device calls `fetchAndMerge` → finds a valid-looking pin (fileId = OLD)
 *   5. Because the pin is "valid", the fallback is ignored → stale 6109-item index
 *      is downloaded instead of the correct 6311-item one
 *
 * ## Contract encoded by this test
 *
 * 1. When the pinned message IS present but its file_id != fallbackFileId,
 *    `fetchAndMerge` must use `fallbackFileId` for the actual merge
 *    (the pin is considered stale).
 * 2. When the stale pin is detected AND `fallbackMessageId` is provided,
 *    `fetchAndMerge` must call `pinChatMessage` with `fallbackMessageId`
 *    so that subsequent syncs on other devices read the correct pin.
 * 3. When the stale pin is detected but `fallbackMessageId` is null (secondary
 *    device that has never uploaded), re-pin is skipped but the fallback
 *    file_id is still used.
 * 4. When pin file_id == fallbackFileId (pin is up-to-date), no re-pin call
 *    is made.
 */
class BackupIndexManagerStalePinRepinBrt {

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

    private fun chatWithPin(pinnedFileId: String, pinnedMsgId: Long = 42L): TelegramResponse<TelegramChatInfo> =
        TelegramResponse(
            ok = true,
            result = TelegramChatInfo(
                id = -1001234567890L,
                type = "supergroup",
                pinnedMessage = TelegramMessage(
                    messageId = pinnedMsgId,
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

    private fun okPinResponse(): TelegramResponse<Boolean> =
        TelegramResponse(ok = true, result = true, description = null, errorCode = null, parameters = null)

    /**
     * Builds a [TelegramBotApi] that delegates all calls to a Mockito mock (stubs [getChat])
     * and overrides [pinChatMessage] with a tracking implementation that invokes [onPin].
     *
     * This avoids InvalidUseOfMatchersException that Mockito produces when matching the implicit
     * Continuation parameter of a suspend function with a Long argument via any() matchers.
     */
    private fun buildSpyApi(
        chatResponse: TelegramResponse<TelegramChatInfo>,
        onPin: (token: String, chatId: String, messageId: Long) -> Unit = { _, _, _ -> },
    ): TelegramBotApi {
        val baseMock: TelegramBotApi = mock {
            onBlocking { getChat(any(), any()) } doReturn chatResponse
        }
        return object : TelegramBotApi by baseMock {
            override suspend fun pinChatMessage(
                token: String,
                chatId: String,
                messageId: Long,
                disableNotification: Boolean,
            ): TelegramResponse<Boolean> {
                onPin(token, chatId, messageId)
                return okPinResponse()
            }
        }
    }

    /**
     * Stubs the downloader so that:
     * - download with [expectedFileId] → writes a 1-entry manifest, returns Success
     * - download with any other fileId → returns DownloadResult.Error
     *
     * This lets tests infer WHICH fileId was actually used, purely from the result.
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

    // ── Test 1: stale pin (pin fileId != fallbackFileId) → use fallbackFileId ──
    //
    // The downloader only succeeds for NEW_FILE_ID.  If the code uses the pinned
    // (stale) OLD_FILE_ID, download fails → Result.failure.  Success proves
    // the fallback path was taken.

    @Test
    fun fetchAndMerge_whenPinIsStale_usesFallbackFileId() = runTest {
        val oldPinnedFileId = "OLD_FILE_ID_STALE"
        val newFallbackFileId = "NEW_FILE_ID_LATEST"
        val api = buildSpyApi(chatWithPin(oldPinnedFileId))
        stubDownloaderForFileId(newFallbackFileId) // old pin → DownloadResult.Error

        val manager = buildManager(api)
        val result = manager.fetchAndMerge(
            token = "123:fake",
            chatId = "-100",
            fallbackFileId = newFallbackFileId,
            fallbackMessageId = 99L,
        )

        assertTrue(
            "Expected success using fallbackFileId; old pin would give DownloadResult.Error. Got: $result",
            result.isSuccess,
        )
        assertEquals(
            "Must merge 1 entry from the new fallback index",
            1,
            result.getOrThrow(),
        )
    }

    // ── Test 2: stale pin + fallbackMessageId provided → calls pinChatMessage ──
    //
    // Uses a hand-rolled delegate (buildSpyApi) instead of Mockito verify() to avoid
    // InvalidUseOfMatchersException caused by the implicit Continuation parameter.

    @Test
    fun fetchAndMerge_whenPinIsStale_andMessageIdProvided_callsPinChatMessage() = runTest {
        val oldPinnedFileId = "OLD_FILE_ID_STALE"
        val newFallbackFileId = "NEW_FILE_ID_LATEST"
        val fallbackMessageId = 99L
        var pinCallCount = 0
        var lastPinnedMsgId: Long? = null
        val api = buildSpyApi(chatWithPin(oldPinnedFileId)) { _, _, msgId ->
            pinCallCount++
            lastPinnedMsgId = msgId
        }
        stubDownloaderForFileId(newFallbackFileId)

        val manager = buildManager(api)
        manager.fetchAndMerge(
            token = "123:fake",
            chatId = "-100",
            fallbackFileId = newFallbackFileId,
            fallbackMessageId = fallbackMessageId,
        )

        assertEquals("pinChatMessage must be called exactly once", 1, pinCallCount)
        assertEquals("pinChatMessage must be called with the fallback messageId", fallbackMessageId, lastPinnedMsgId)
    }

    // ── Test 3: stale pin + no fallbackMessageId → skip re-pin, still use fallback ──

    @Test
    fun fetchAndMerge_whenPinIsStale_andNoMessageId_skipsPinButUsesFallback() = runTest {
        val oldPinnedFileId = "OLD_FILE_ID_STALE"
        val newFallbackFileId = "NEW_FILE_ID_LATEST"
        var pinCallCount = 0
        val api = buildSpyApi(chatWithPin(oldPinnedFileId)) { _, _, _ -> pinCallCount++ }
        stubDownloaderForFileId(newFallbackFileId)

        val manager = buildManager(api)
        val result = manager.fetchAndMerge(
            token = "123:fake",
            chatId = "-100",
            fallbackFileId = newFallbackFileId,
            fallbackMessageId = null, // no message id — secondary device, never uploaded
        )

        assertEquals("pinChatMessage must NOT be called when no messageId is provided", 0, pinCallCount)
        assertTrue("Must succeed even without message id; got: $result", result.isSuccess)
        assertEquals("Must merge 1 entry from fallback", 1, result.getOrThrow())
    }

    // ── Test 4: pin matches fallbackFileId (up-to-date) → no re-pin call ────

    @Test
    fun fetchAndMerge_whenPinMatchesFallback_doesNotCallPinChatMessage() = runTest {
        val currentFileId = "CURRENT_FILE_ID"
        var pinCallCount = 0
        val api = buildSpyApi(chatWithPin(currentFileId)) { _, _, _ -> pinCallCount++ }
        stubDownloaderForFileId(currentFileId)

        val manager = buildManager(api)
        manager.fetchAndMerge(
            token = "123:fake",
            chatId = "-100",
            fallbackFileId = currentFileId, // same as pin → no re-pin needed
            fallbackMessageId = 99L,
        )

        assertEquals("pinChatMessage must NOT be called when pin is already up-to-date", 0, pinCallCount)
    }
}
