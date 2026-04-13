package com.ulap.data.googlephotos

import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.remote.BotPool
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramDocument
import com.ulap.data.remote.TelegramMessage
import com.ulap.data.remote.TelegramRateLimiter
import com.ulap.data.remote.TelegramResponse
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bug Reproduction Tests — `GooglePhotosImportManager.importBatch`.
 *
 * ## Defect
 *
 * `GooglePhotosImportWorker` processes items sequentially in a for-loop. With N=100 items, items
 * near the end of the batch may wait >60 minutes to begin processing, by which time Google Photos
 * `baseUrl` values have expired (TTL ~60 min). A concurrent batch processor solves this by
 * starting downloads in parallel so all URLs are consumed while still valid.
 *
 * ## Required contracts (encoded by these tests)
 *
 * 1. **All items processed:** All [List.size] `BatchItemResult` entries are returned; no item is
 *    silently dropped regardless of the [concurrency] value.
 * 2. **Bounded concurrency:** Peak simultaneous in-flight calls to `streamMedia` never exceeds
 *    [concurrency]. Verified via [AtomicInteger] increment/decrement inside a delayed mock.
 * 3. **Isolation on failure:** One item failing must not prevent the others from completing. The
 *    failed item's `BatchItemResult.result.isFailure` is `true`; all others succeed.
 * 4. **`onItemComplete` callback:** Called exactly once per item, for every item.
 *
 * ## Why these tests FAIL against current code
 *
 * 1. `BatchItemResult` data class does not exist → entire file fails to compile.
 * 2. `importBatch` method does not exist on `GooglePhotosImportManager` → compile error.
 *
 * Deterministic: no real network, no I/O; `delay()` uses virtual time via [runTest].
 */
class GooglePhotosImportBatchBrt {

    // ── Constants ──────────────────────────────────────────────────────────────

    private val testSessionId = "session-batch-001"
    private val jpegBytes     = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun successImageResponse(): Response<okhttp3.ResponseBody> =
        Response.success(jpegBytes.toResponseBody("image/jpeg".toMediaType()))

    private fun telegramOkResponse(fileId: String = "tg-fid"): TelegramResponse<TelegramMessage> =
        TelegramResponse(
            ok = true,
            result = TelegramMessage(
                messageId = 42L,
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

    private fun mockPassthroughRateLimiter(): TelegramRateLimiter = mock { rl ->
        onBlocking {
            rl.withRateLimit(any<suspend () -> TelegramResponse<TelegramMessage>>())
        }.doSuspendableAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            (inv.getArgument(0) as suspend () -> TelegramResponse<TelegramMessage>).invoke()
        }
    }

    private fun imageItem(id: String): GooglePhotosMediaItem = GooglePhotosMediaItem(
        id = id,
        mimeType = "image/jpeg",
        filename = "$id.jpg",
        baseUrl = "https://lh3.googleusercontent.com/$id",
        mediaMetadata = GooglePhotosMediaMetadata(creationTime = null, width = "100", height = "100"),
    )

    private fun buildManager(
        pickerApi: GooglePhotosPickerApi,
        uploadApi: TelegramBotApi,
        mediaDao: MediaItemDao,
        chunkDao: ChunkMetadataDao,
        creds: CredentialRepository,
        botPool: BotPool,
        rateLimiter: TelegramRateLimiter,
    ): GooglePhotosImportManager = GooglePhotosImportManager(
        pickerApi = pickerApi,
        uploadTelegramBotApi = uploadApi,
        mediaItemDao = mediaDao,
        chunkMetadataDao = chunkDao,
        rateLimiter = rateLimiter,
        credentialRepository = creds,
        botPool = botPool,
    )

    private suspend fun defaultDependencies(
        pickerApi: GooglePhotosPickerApi,
    ): Triple<TelegramBotApi, CredentialRepository, BotPool> {
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(telegramOkResponse())
        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")
        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "pool-token"))
        return Triple(uploadApi, creds, botPool)
    }

    // ── Contract 1 — all items processed, none dropped ────────────────────────

    /**
     * Given 5 image items and `concurrency=3`, `importBatch` must return exactly 5
     * [BatchItemResult] entries. No item may be silently dropped or skipped.
     *
     * FAILS NOW (compile): `importBatch` and `BatchItemResult` do not exist.
     * PASSES AFTER: `importBatch` completes all items and collects every result.
     */
    @Test
    fun importBatch_fiveItems_allFiveResultsReturned_noneDropped() = runTest {
        val items = (1..5).map { imageItem("item-$it") }

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer { successImageResponse() }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)
        val (uploadApi, creds, botPool) = defaultDependencies(pickerApi)

        val manager = buildManager(
            pickerApi, uploadApi, mediaDao, mock(), creds, botPool, mockPassthroughRateLimiter(),
        )

        // importBatch does NOT exist on GooglePhotosImportManager yet → compile error
        // BatchItemResult does NOT exist yet → compile error
        val results: List<BatchItemResult> = manager.importBatch(
            items = items,
            sessionId = testSessionId,
            concurrency = 3,
        )

        assertEquals(
            "importBatch must return exactly one BatchItemResult per input item (5 items → 5 results)",
            5,
            results.size,
        )
        assertTrue(
            "All 5 results must be success for a healthy import",
            results.all { it.result.isSuccess },
        )
    }

    // ── Contract 2 — bounded concurrency ─────────────────────────────────────

    /**
     * Given 6 items and `concurrency=2`, the peak number of simultaneously in-flight
     * `streamMedia` calls must never exceed 2.
     *
     * The mock increments [currentConcurrency] on entry, records the peak in [peakConcurrency],
     * suspends briefly (virtual time via [delay]), then decrements. The assertion checks that the
     * semaphore in `importBatch` correctly limits concurrent execution.
     *
     * FAILS NOW (compile): `importBatch` and `BatchItemResult` do not exist.
     * PASSES AFTER: `importBatch` uses a semaphore (or equivalent) capped at [concurrency].
     */
    @Test
    fun importBatch_concurrencyTwo_peakInFlightNeverExceedsTwo() = runTest {
        val concurrencyLimit = 2
        val items = (1..6).map { imageItem("conc-item-$it") }

        val currentConcurrency = AtomicInteger(0)
        val peakConcurrency    = AtomicInteger(0)

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doSuspendableAnswer {
            val current = currentConcurrency.incrementAndGet()
            peakConcurrency.updateAndGet { maxOf(it, current) }
            delay(50L)
            currentConcurrency.decrementAndGet()
            successImageResponse()
        }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)
        val (uploadApi, creds, botPool) = defaultDependencies(pickerApi)

        val manager = buildManager(
            pickerApi, uploadApi, mediaDao, mock(), creds, botPool, mockPassthroughRateLimiter(),
        )

        // importBatch does NOT exist on GooglePhotosImportManager yet → compile error
        val results: List<BatchItemResult> = manager.importBatch(
            items = items,
            sessionId = testSessionId,
            concurrency = concurrencyLimit,
        )

        assertEquals("All 6 items must complete despite bounded concurrency", 6, results.size)
        assertTrue(
            "Peak concurrent streamMedia calls (${ peakConcurrency.get() }) must not exceed concurrency=$concurrencyLimit",
            peakConcurrency.get() <= concurrencyLimit,
        )
    }

    // ── Contract 3 — one item failing does not block the rest ─────────────────

    /**
     * If one item's `streamMedia` call throws an exception, `importBatch` must:
     * - set `BatchItemResult.result.isFailure = true` for that item
     * - still complete all remaining items successfully (isolation guarantee)
     *
     * FAILS NOW (compile): `importBatch` and `BatchItemResult` do not exist.
     * PASSES AFTER: `importBatch` catches per-item exceptions and stores them as
     * `Result.failure` without propagating to sibling coroutines.
     */
    @Test
    fun importBatch_oneItemFails_otherItemsCompleteSuccessfully() = runTest {
        val failItemId   = "item-will-fail"
        val failItem     = imageItem(failItemId)
        val successItems = (1..4).map { imageItem("ok-item-$it") }
        val allItems     = listOf(failItem) + successItems

        val failUrl = GooglePhotosUrls.fullResolutionImageUrl(
            "https://lh3.googleusercontent.com/$failItemId",
        )

        val pickerApi = mock<GooglePhotosPickerApi>()
        // All items succeed by default
        whenever(pickerApi.streamMedia(any())).doAnswer { successImageResponse() }
        // The failing item throws on streamMedia
        whenever(pickerApi.streamMedia(eq(failUrl))).doSuspendableAnswer {
            throw RuntimeException("injected streamMedia failure for $failItemId")
        }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)
        val (uploadApi, creds, botPool) = defaultDependencies(pickerApi)

        val manager = buildManager(
            pickerApi, uploadApi, mediaDao, mock(), creds, botPool, mockPassthroughRateLimiter(),
        )

        // importBatch does NOT exist on GooglePhotosImportManager yet → compile error
        val results: List<BatchItemResult> = manager.importBatch(
            items = allItems,
            sessionId = testSessionId,
            concurrency = 3,
        )

        assertEquals("All 5 items must have a BatchItemResult even when one fails", 5, results.size)

        val failedResult = results.find { it.item.id == failItemId }
        assertFalse(
            "The failing item must have BatchItemResult.result.isFailure = true",
            failedResult!!.result.isSuccess,
        )

        val successResults = results.filter { it.item.id != failItemId }
        assertTrue(
            "The 4 healthy items must all have BatchItemResult.result.isSuccess = true",
            successResults.all { it.result.isSuccess },
        )
    }

    // ── Contract 4 — onItemComplete callback invoked for every item ───────────

    /**
     * The `onItemComplete` lambda must be called exactly once per item, in any order, regardless
     * of success or failure.
     *
     * FAILS NOW (compile): `importBatch` and `BatchItemResult` do not exist.
     * PASSES AFTER: `importBatch` calls `onItemComplete(item, result)` after each item finishes.
     */
    @Test
    fun importBatch_threeItems_onItemCompleteCalledExactlyThreeTimes() = runTest {
        val items = (1..3).map { imageItem("cb-item-$it") }

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer { successImageResponse() }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)
        val (uploadApi, creds, botPool) = defaultDependencies(pickerApi)

        val manager = buildManager(
            pickerApi, uploadApi, mediaDao, mock(), creds, botPool, mockPassthroughRateLimiter(),
        )

        val callbackInvocationCount = AtomicInteger(0)

        // importBatch does NOT exist on GooglePhotosImportManager yet → compile error
        val results: List<BatchItemResult> = manager.importBatch(
            items = items,
            sessionId = testSessionId,
            concurrency = 3,
            onItemComplete = { _, _ -> callbackInvocationCount.incrementAndGet() },
        )

        assertEquals("3 items imported → 3 BatchItemResults", 3, results.size)
        assertEquals(
            "onItemComplete must be called exactly once per item (3 items → 3 invocations)",
            3,
            callbackInvocationCount.get(),
        )
    }
}
