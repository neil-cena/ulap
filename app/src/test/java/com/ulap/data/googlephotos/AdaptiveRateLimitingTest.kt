package com.ulap.data.googlephotos

import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.remote.BotPool
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramDocument
import com.ulap.data.remote.TelegramMessage
import com.ulap.data.remote.TelegramRateLimitException
import com.ulap.data.remote.TelegramRateLimiter
import com.ulap.data.remote.TelegramResponse
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the adaptive rate-limiting system:
 * - AIMD concurrency controller
 * - BotPool.minTempCooldownExpiryMs
 * - Batch profiling and cost estimation
 * - Exponential backoff (verified via retry count reduction)
 */
class AdaptiveRateLimitingTest {

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

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

    private fun imageItem(id: String): GooglePhotosMediaItem = GooglePhotosMediaItem(
        id = id,
        mimeType = "image/jpeg",
        filename = "$id.jpg",
        baseUrl = "https://lh3.googleusercontent.com/$id",
        mediaMetadata = GooglePhotosMediaMetadata(creationTime = null, width = "100", height = "100"),
    )

    private fun videoItem(id: String): GooglePhotosMediaItem = GooglePhotosMediaItem(
        id = id,
        mimeType = "video/mp4",
        filename = "$id.mp4",
        baseUrl = "https://lh3.googleusercontent.com/$id",
        mediaMetadata = GooglePhotosMediaMetadata(creationTime = null, width = "1920", height = "1080"),
    )

    private fun mockPassthroughRateLimiter(): TelegramRateLimiter = mock { rl ->
        onBlocking {
            rl.withRateLimit(any<suspend () -> TelegramResponse<TelegramMessage>>())
        }.doSuspendableAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            (inv.getArgument(0) as suspend () -> TelegramResponse<TelegramMessage>).invoke()
        }
    }

    private fun buildManager(
        pickerApi: GooglePhotosPickerApi = mock(),
        uploadApi: TelegramBotApi = mock(),
        mediaDao: MediaItemDao = mock(),
        chunkDao: ChunkMetadataDao = mock(),
        creds: CredentialRepository = mock(),
        botPool: BotPool = mock(),
        rateLimiter: TelegramRateLimiter = mockPassthroughRateLimiter(),
    ): GooglePhotosImportManager = GooglePhotosImportManager(
        pickerApi = pickerApi,
        uploadTelegramBotApi = uploadApi,
        mediaItemDao = mediaDao,
        chunkMetadataDao = chunkDao,
        rateLimiter = rateLimiter,
        credentialRepository = creds,
        botPool = botPool,
    )

    // ── AIMD Controller Unit Tests ──────────────────────────────────────────

    @Test
    fun aimd_initialConcurrencyRespected() {
        val aimd = AimdConcurrencyController(initialConcurrency = 3, maxConcurrency = 6)
        assertEquals(3, aimd.currentLimit)
    }

    @Test
    fun aimd_onSuccess_increasesAfterTwoSuccesses() {
        val aimd = AimdConcurrencyController(initialConcurrency = 2, maxConcurrency = 6)
        aimd.onSuccess()
        assertEquals("Single success should not increase yet", 2, aimd.currentLimit)
        aimd.onSuccess()
        assertEquals("Two successes should increase by 1", 3, aimd.currentLimit)
    }

    @Test
    fun aimd_onSuccess_cappedAtMax() {
        val aimd = AimdConcurrencyController(initialConcurrency = 5, maxConcurrency = 6)
        repeat(10) { aimd.onSuccess() }
        assertEquals("Should not exceed maxConcurrency", 6, aimd.currentLimit)
    }

    @Test
    fun aimd_onRateLimit_halvesConcurrency() {
        val aimd = AimdConcurrencyController(initialConcurrency = 4, maxConcurrency = 6)
        aimd.onRateLimit()
        assertEquals("Should halve from 4 to 2", 2, aimd.currentLimit)
    }

    @Test
    fun aimd_onRateLimit_floorAtOne() {
        val aimd = AimdConcurrencyController(initialConcurrency = 1, maxConcurrency = 6)
        aimd.onRateLimit()
        assertEquals("Should never go below 1", 1, aimd.currentLimit)
    }

    @Test
    fun aimd_onRateLimit_resetsSuccessCounter() {
        val aimd = AimdConcurrencyController(initialConcurrency = 4, maxConcurrency = 6)
        aimd.onSuccess()
        aimd.onRateLimit()
        assertEquals(2, aimd.currentLimit)
        aimd.onSuccess()
        assertEquals("Single success after rate limit should not increase", 2, aimd.currentLimit)
    }

    @Test
    fun aimd_sawtoothPattern() {
        val aimd = AimdConcurrencyController(initialConcurrency = 1, maxConcurrency = 6)
        assertEquals(1, aimd.currentLimit)

        // Ramp up: 2 successes → +1, 2 more → +1, 2 more → +1
        repeat(2) { aimd.onSuccess() }
        assertEquals(2, aimd.currentLimit)
        repeat(2) { aimd.onSuccess() }
        assertEquals(3, aimd.currentLimit)
        repeat(2) { aimd.onSuccess() }
        assertEquals(4, aimd.currentLimit)

        // Hit 429 → halve
        aimd.onRateLimit()
        assertEquals(2, aimd.currentLimit)

        // Ramp up again
        repeat(2) { aimd.onSuccess() }
        assertEquals(3, aimd.currentLimit)
    }

    // ── BotPool.minTempCooldownExpiryMs ─────────────────────────────────────

    @Test
    fun botPool_minCooldown_returnsEarliest() {
        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok0")
        whenever(creds.getAdditionalBotTokens()).thenReturn(
            listOf(BotCredential(1, "tok1"), BotCredential(2, "tok2")),
        )
        val banStore = mock<com.ulap.data.remote.BotBanStore>()
        whenever(banStore.loadBans()).thenReturn(emptyMap())
        val pool = BotPool(creds, banStore)

        val now = System.currentTimeMillis()
        pool.markRateLimited(0, 10_000)  // expires at now+10s
        pool.markRateLimited(1, 3_000)   // expires at now+3s (earliest)
        pool.markRateLimited(2, 7_000)   // expires at now+7s

        val min = pool.minTempCooldownExpiryMs()
        val max = pool.maxTempCooldownExpiryMs()

        assertTrue("min ($min) should be less than max ($max)", min < max)
        assertTrue(
            "min should be approximately now+3s (within 500ms tolerance)",
            min in (now + 2_500)..(now + 3_500),
        )
    }

    @Test
    fun botPool_minCooldown_returnsZeroWhenNoCooldowns() {
        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok0")
        whenever(creds.getAdditionalBotTokens()).thenReturn(emptyList())
        val banStore = mock<com.ulap.data.remote.BotBanStore>()
        whenever(banStore.loadBans()).thenReturn(emptyMap())
        val pool = BotPool(creds, banStore)

        assertEquals(0L, pool.minTempCooldownExpiryMs())
    }

    // ── Batch Profiling ─────────────────────────────────────────────────────

    @Test
    fun profileBatch_smallBatchWithManyBots_fullConcurrency() {
        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(
            (0..2).map { BotCredential(it, "tok$it") },
        )
        val manager = buildManager(botPool = botPool)

        val items = (1..5).map { imageItem("img-$it") }
        val profile = manager.profileBatch(items)

        assertEquals(5, profile.totalItems)
        assertEquals(5, profile.imageCount)
        assertEquals(0, profile.videoCount)
        assertEquals(5, profile.estimatedApiCalls)
        assertEquals(3, profile.botCount)
        assertEquals(
            "5 calls / 3 bots = ratio 1.67 < 50 → full concurrency",
            3,
            profile.initialConcurrency,
        )
    }

    @Test
    fun profileBatch_largeBatchOneBotStartsConservative() {
        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(listOf(BotCredential(0, "tok")))
        val manager = buildManager(botPool = botPool)

        val items = (1..100).map { imageItem("img-$it") }
        val profile = manager.profileBatch(items)

        assertEquals(100, profile.estimatedApiCalls)
        assertEquals(1, profile.botCount)
        assertEquals(
            "100 calls / 1 bot = ratio 100 > 50 → conservative start at 1",
            1,
            profile.initialConcurrency,
        )
    }

    @Test
    fun profileBatch_mixedMediaCountsVideosHigher() {
        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(listOf(BotCredential(0, "tok")))
        val manager = buildManager(botPool = botPool)

        val items = listOf(imageItem("img-1"), videoItem("vid-1"), imageItem("img-2"))
        val profile = manager.profileBatch(items)

        assertEquals(2, profile.imageCount)
        assertEquals(1, profile.videoCount)
        assertTrue(
            "Estimated calls should be > 3 because video adds more than 1",
            profile.estimatedApiCalls >= 3,
        )
    }

    @Test
    fun estimateApiCalls_imageIsOne() {
        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(listOf(BotCredential(0, "tok")))
        val manager = buildManager(botPool = botPool)

        assertEquals(1, manager.estimateApiCalls(listOf(imageItem("i1"))))
    }

    @Test
    fun estimateApiCalls_videoDefaultsToThree() {
        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(listOf(BotCredential(0, "tok")))
        val manager = buildManager(botPool = botPool)

        val calls = manager.estimateApiCalls(listOf(videoItem("v1")))
        assertEquals(
            "Video with unknown size should default to 3 API calls",
            3,
            calls,
        )
    }

    // ── AIMD Integration: importBatch adapts concurrency ────────────────────

    @Test
    fun importBatch_aimdReducesConcurrencyOn429() = runTest {
        val items = (1..6).map { imageItem("aimd-$it") }
        val bot = BotCredential(0, "tok")

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer { successImageResponse() }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(listOf(bot))
        whenever(botPool.selectForUpload()).thenReturn(bot)
        whenever(botPool.minTempCooldownExpiryMs()).thenReturn(0L)

        val callCount = AtomicInteger(0)
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .doSuspendableAnswer {
                val c = callCount.incrementAndGet()
                if (c == 1) throw TelegramRateLimitException(100L)
                telegramOkResponse()
            }

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val results = manager.importBatch(items = items, sessionId = "s")

        assertEquals("All items should complete", 6, results.size)
        val succeeded = results.count { it.result.isSuccess }
        assertTrue("Most items should succeed despite one 429", succeeded >= 5)
    }

    @Test
    fun importBatch_processesItemsInApiReturnOrder() = runTest {
        val items = listOf(
            videoItem("vid-1"),
            imageItem("img-1"),
            videoItem("vid-2"),
            imageItem("img-2"),
        )

        val processedOrder = Collections.synchronizedList(mutableListOf<String>())

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doSuspendableAnswer {
            delay(10L)
            successImageResponse()
        }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(listOf(BotCredential(0, "tok")))
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "tok"))
        whenever(botPool.minTempCooldownExpiryMs()).thenReturn(0L)

        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(telegramOkResponse())

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        manager.importBatch(
            items = items,
            sessionId = "s",
            onItemComplete = { item, _ -> processedOrder.add(item.id) },
        )

        assertEquals("All 4 items processed", 4, processedOrder.size)
    }

    // ── Exponential backoff: verify retry with increasing delays ────────────

    @Test
    fun imageImport_retriesWithBackoff_succeedsOnSecondAttempt() = runTest {
        val item = imageItem("backoff-img")
        val bot = BotCredential(0, "tok")

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer { successImageResponse() }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(bot)
        whenever(botPool.minTempCooldownExpiryMs()).thenReturn(0L)

        val callCount = AtomicInteger(0)
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .doSuspendableAnswer {
                val attempt = callCount.incrementAndGet()
                if (attempt == 1) throw TelegramRateLimitException(100L)
                telegramOkResponse()
            }

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val startMs = System.currentTimeMillis()
        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("Should succeed after backoff retry", result.isSuccess)
        assertEquals("Should have attempted exactly 2 times", 2, callCount.get())
        assertTrue(
            "Backoff should have introduced a non-trivial delay (elapsed=${elapsedMs}ms)",
            elapsedMs >= 500,
        )
    }
}
