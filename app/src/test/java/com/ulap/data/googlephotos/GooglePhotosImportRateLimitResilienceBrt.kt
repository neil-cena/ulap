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
 * Bug Reproduction Tests — Telegram rate-limit resilience for Google Photos import.
 *
 * ## Defects
 *
 * BUG-002c: With 1 bot and hardcoded concurrency=3, the single bot gets 429'd rapidly.
 * The image retry loop burns through 12 attempts without waiting for cooldown. Video
 * uploads have zero retry on 429.
 *
 * ## Required contracts
 *
 * 1. **Adaptive concurrency** — `recommendedConcurrency()` returns min(botCount, 3).
 *    1 bot → 1 (sequential), 2 bots → 2, 3+ bots → 3 (capped).
 *
 * 2. **Image retry backoff** — When a bot returns 429, the retry loop must wait for
 *    the cooldown to expire before retrying, not immediately re-select the same bot.
 *
 * 3. **Video retry** — Video imports must retry on TelegramRateLimitException with the
 *    same backoff pattern as images, not fail immediately.
 *
 * Deterministic: no real network; delay() uses virtual time via runTest.
 */
class GooglePhotosImportRateLimitResilienceBrt {

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

    // ── Contract 1: Adaptive concurrency ────────────────────────────────────

    @Test
    fun recommendedConcurrency_oneBotReturnsOne() {
        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(listOf(BotCredential(0, "tok")))

        val manager = buildManager(botPool = botPool)
        assertEquals(
            "1 bot should produce concurrency=1 (sequential uploads, no self-contention)",
            1,
            manager.recommendedConcurrency(),
        )
    }

    @Test
    fun recommendedConcurrency_twoBotsReturnsTwo() {
        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(
            listOf(BotCredential(0, "tok0"), BotCredential(1, "tok1")),
        )

        val manager = buildManager(botPool = botPool)
        assertEquals(
            "2 bots should produce concurrency=2",
            2,
            manager.recommendedConcurrency(),
        )
    }

    @Test
    fun recommendedConcurrency_fiveBotsReturnsFive() {
        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(
            (0..4).map { BotCredential(it, "tok$it") },
        )

        val manager = buildManager(botPool = botPool)
        assertEquals(
            "5 bots should produce concurrency=5 (hard cap is now 6)",
            5,
            manager.recommendedConcurrency(),
        )
    }

    @Test
    fun recommendedConcurrency_zeroBotsReturnsOne() {
        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(emptyList())

        val manager = buildManager(botPool = botPool)
        assertEquals(
            "0 bots (edge case) should produce concurrency=1 (minimum)",
            1,
            manager.recommendedConcurrency(),
        )
    }

    // ── Contract 2: Image retry waits for cooldown ──────────────────────────

    @Test
    fun imageImport_rateLimited_retriesAfterCooldownAndSucceeds() = runTest {
        val item = imageItem("retry-img")
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
                if (attempt == 1) {
                    throw TelegramRateLimitException(1_000L)
                }
                telegramOkResponse()
            }

        val rateLimiter = mockPassthroughRateLimiter()
        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
            rateLimiter = rateLimiter,
        )

        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")
        assertTrue(
            "Import must succeed after rate-limit retry (was: ${result.exceptionOrNull()?.message})",
            result.isSuccess,
        )
        assertTrue(
            "Must have retried at least once (attempt count: ${callCount.get()})",
            callCount.get() >= 2,
        )
    }

    // ── Contract 3: Video retry on rate limiting ────────────────────────────

    @Test
    fun videoImport_rateLimited_retriesAndSucceeds() = runTest {
        val item = videoItem("retry-vid")
        val bot = BotCredential(0, "tok")

        val smallVideoBytes = ByteArray(1024) { 0x00 }
        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer {
            Response.success(smallVideoBytes.toResponseBody("video/mp4".toMediaType()))
        }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)
        val chunkDao = mock<ChunkMetadataDao>()

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
                if (attempt == 1) {
                    throw TelegramRateLimitException(1_000L)
                }
                telegramOkResponse()
            }

        val rateLimiter = mockPassthroughRateLimiter()
        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            chunkDao = chunkDao,
            creds = creds,
            botPool = botPool,
            rateLimiter = rateLimiter,
        )

        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")
        assertTrue(
            "Video import must succeed after rate-limit retry (was: ${result.exceptionOrNull()?.message})",
            result.isSuccess,
        )
        assertTrue(
            "Video must have retried at least once (attempt count: ${callCount.get()})",
            callCount.get() >= 2,
        )
    }

    @Test
    fun videoImport_nonRateLimitError_failsImmediately() = runTest {
        val item = videoItem("fail-vid")
        val bot = BotCredential(0, "tok")

        val smallVideoBytes = ByteArray(1024) { 0x00 }
        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer {
            Response.success(smallVideoBytes.toResponseBody("video/mp4".toMediaType()))
        }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(bot)
        whenever(botPool.minTempCooldownExpiryMs()).thenReturn(0L)

        val sendDocCallCount = AtomicInteger(0)
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .doAnswer {
                sendDocCallCount.incrementAndGet()
                TelegramResponse(
                    ok = false,
                    result = null,
                    description = "Bad Request: file is too big",
                    errorCode = 400,
                    parameters = null,
                )
            }

        val rateLimiter = mockPassthroughRateLimiter()
        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
            rateLimiter = rateLimiter,
        )

        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")
        assertTrue(
            "Video import must fail gracefully on non-rate-limit error",
            result.isFailure,
        )
        assertEquals(
            "Should only attempt sendDocument once (no retry for non-429 errors)",
            1,
            sendDocCallCount.get(),
        )
    }

    // ── Contract 2 (extended): Adaptive concurrency limits peak in-flight ───

    @Test
    fun importBatch_oneBotAimdStartsAtOne_peakBoundedByAimdMax() = runTest {
        val items = (1..5).map { imageItem("adap-$it") }

        val currentConcurrency = AtomicInteger(0)
        val peakConcurrency = AtomicInteger(0)

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doSuspendableAnswer {
            val current = currentConcurrency.incrementAndGet()
            peakConcurrency.updateAndGet { maxOf(it, current) }
            delay(10L)
            currentConcurrency.decrementAndGet()
            successImageResponse()
        }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(telegramOkResponse())

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.allBots()).thenReturn(listOf(BotCredential(0, "tok")))
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "tok"))

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val results = manager.importBatch(
            items = items,
            sessionId = "session",
        )

        assertEquals("All 5 items must complete", 5, results.size)
        assertTrue(
            "Peak concurrent calls (${peakConcurrency.get()}) must not exceed AIMD max for 1 bot (2)",
            peakConcurrency.get() <= 2,
        )
    }
}
