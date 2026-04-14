package com.ulap.data.googlephotos

import android.content.Context
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
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.Response
import java.io.File

/**
 * Bug Reproduction Tests — GooglePhotosImportManager URL re-fetch on HTTP 401/403.
 *
 * ## Defect
 *
 * `importGooglePhotosMediaItem` permanently fails when `pickerApi.streamMedia` returns HTTP 401
 * or 403. These status codes indicate the Google Photos `baseUrl` has expired (valid ~60 min).
 * Items late in a large sequential batch reliably receive expired URLs.
 *
 * ## Required contracts (encoded by these tests)
 *
 * 1. HTTP 401 on an image stream → call `getMediaItem(id, sessionId)` for a fresh `baseUrl`,
 *    retry the download exactly once, return `Result.success(UPLOADED)` if the retry succeeds.
 * 2. HTTP 401 on a video stream → same re-fetch + retry → success.
 * 3. HTTP 403 on an image stream → same re-fetch + retry → success (403 also signals expiry).
 * 4. HTTP 401 on the first attempt, HTTP 401 on the retry → `Result.failure` (no infinite loop).
 * 5. The retry download uses the fresh `baseUrl` from `getMediaItem`, NOT the original expired URL.
 *
 * ## Why these tests FAIL against current code
 *
 * 1. `GooglePhotosPickerApi.getMediaItem` does not exist → entire file fails to compile.
 * 2. `importGooglePhotosMediaItem(item, sessionId)` does not accept `sessionId` → compile error.
 * 3. No URL re-fetch or retry logic exists in `GooglePhotosImportManager`.
 *
 * Deterministic: no real network, no I/O, no clocks, no randomness.
 */
class GooglePhotosImportManagerUrlRefetchBrt {

    private lateinit var tempCacheDir: File
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        tempCacheDir = File(System.getProperty("java.io.tmpdir"), "refetch-brt-${System.nanoTime()}")
        tempCacheDir.mkdirs()
        mockContext = mock()
        whenever(mockContext.cacheDir).thenReturn(tempCacheDir)
    }

    @After
    fun tearDown() {
        tempCacheDir.deleteRecursively()
    }

    // ── Test constants ─────────────────────────────────────────────────────────

    private val expiredBaseUrl = "https://lh3.googleusercontent.com/expired"
    private val freshBaseUrl   = "https://lh3.googleusercontent.com/fresh"
    private val testSessionId  = "session-brt-001"
    private val jpegBytes      = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val videoBytes     = byteArrayOf(0x00, 0x01, 0x02, 0x03)

    // ── Response builders ──────────────────────────────────────────────────────

    private fun httpErrorResponse(code: Int): Response<okhttp3.ResponseBody> =
        Response.error(code, "".toResponseBody("application/json".toMediaTypeOrNull()))

    private fun successImageResponse(): Response<okhttp3.ResponseBody> =
        Response.success(jpegBytes.toResponseBody("image/jpeg".toMediaType()))

    private fun successVideoResponse(): Response<okhttp3.ResponseBody> =
        Response.success(videoBytes.toResponseBody("video/mp4".toMediaType()))

    private fun telegramOkResponse(fileId: String = "tg-fid-001"): TelegramResponse<TelegramMessage> =
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

    // ── Shared mock helpers ────────────────────────────────────────────────────

    /**
     * Returns a mock [TelegramRateLimiter] that transparently invokes the block it is given,
     * identical to the pattern used in [GooglePhotosImportManagerBotPoolContractBrt].
     */
    private fun mockPassthroughRateLimiter(): TelegramRateLimiter = mock { rl ->
        onBlocking {
            rl.withRateLimit(any<suspend () -> TelegramResponse<TelegramMessage>>())
        }.doSuspendableAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            (inv.getArgument(0) as suspend () -> TelegramResponse<TelegramMessage>).invoke()
        }
    }

    /**
     * Returns a [PickedMediaItem] carrying [freshBaseUrl] — the value that `getMediaItem` would
     * return after a successful URL re-fetch from the Picker API.
     */
    private fun freshPickedMediaItem(
        itemId: String,
        type: String = "PHOTO",
        mimeType: String = "image/jpeg",
    ): PickedMediaItem = PickedMediaItem(
        id = itemId,
        type = type,
        mediaFile = PickedMediaFile(
            baseUrl = freshBaseUrl,
            mimeType = mimeType,
            filename = "$itemId.dat",
            mediaFileMetadata = null,
        ),
        createTime = null,
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
        appContext = mockContext,
    )

    // ── Contract 1 — image HTTP 401 → re-fetch → retry succeeds ──────────────

    /**
     * Arrangement: `streamMedia(expiredImageUrl)` returns 401.
     * Expected: `getMediaItem(id, sessionId)` is called; `streamMedia(freshImageUrl)` is called;
     * import returns `Result.success`.
     *
     * FAILS NOW (compile): `getMediaItem` does not exist on `GooglePhotosPickerApi`.
     * FAILS NOW (compile): `importGooglePhotosMediaItem(item, sessionId)` missing `sessionId` param.
     * PASSES AFTER: both API additions + re-fetch + retry logic are implemented.
     */
    @Test
    fun imageItem_http401_refetchesBaseUrl_retrySucceeds_returnsSuccess() = runTest {
        val itemId = "img-item-401"
        val expiredImageUrl = GooglePhotosUrls.fullResolutionImageUrl(expiredBaseUrl)
        val freshImageUrl   = GooglePhotosUrls.fullResolutionImageUrl(freshBaseUrl)

        val pickerApi = mock<GooglePhotosPickerApi>()
        // Stub fallback last so specific eq() stubs registered after this take precedence
        whenever(pickerApi.streamMedia(eq(expiredImageUrl))).thenReturn(httpErrorResponse(401))
        // getMediaItem does NOT exist on GooglePhotosPickerApi yet → compile error
        whenever(pickerApi.getMediaItem(eq(itemId), eq(testSessionId)))
            .thenReturn(freshPickedMediaItem(itemId))
        whenever(pickerApi.streamMedia(eq(freshImageUrl))).thenReturn(successImageResponse())

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)
        val chunkDao = mock<ChunkMetadataDao>()
        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("primary-token")
        whenever(creds.getChatId()).thenReturn("99")
        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "pool-token"))
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(telegramOkResponse())

        val manager = buildManager(pickerApi, uploadApi, mediaDao, chunkDao, creds, botPool, mockPassthroughRateLimiter())
        val item = GooglePhotosMediaItem(
            id = itemId,
            mimeType = "image/jpeg",
            filename = "$itemId.jpg",
            baseUrl = expiredBaseUrl,
            mediaMetadata = GooglePhotosMediaMetadata(creationTime = null, width = "100", height = "100"),
        )

        // importGooglePhotosMediaItem does NOT accept sessionId yet → compile error
        val result = manager.importGooglePhotosMediaItem(item, testSessionId)

        assertTrue(
            "HTTP 401 image: import must return success after re-fetching baseUrl and retrying",
            result.isSuccess,
        )
    }

    // ── Contract 2 — video HTTP 401 → re-fetch → retry succeeds ──────────────

    /**
     * Same contract as (1) applied to a video item. The `=dv` download stream receives 401,
     * triggering a re-fetch. The poster-frame stream (`=w400-h400-p`) is best-effort and its
     * result does not affect this contract.
     *
     * FAILS NOW (compile): same two missing APIs.
     * PASSES AFTER: re-fetch + retry logic handles video items.
     */
    @Test
    fun videoItem_http401_refetchesBaseUrl_retrySucceeds_returnsSuccess() = runTest {
        val itemId = "vid-item-401"
        val expiredVideoUrl  = GooglePhotosUrls.downloadVideoUrl(expiredBaseUrl)
        val expiredPosterUrl = GooglePhotosUrls.remoteThumbnailVideo(expiredBaseUrl)
        val freshVideoUrl    = GooglePhotosUrls.downloadVideoUrl(freshBaseUrl)
        val freshPosterUrl   = GooglePhotosUrls.remoteThumbnailVideo(freshBaseUrl)

        val pickerApi = mock<GooglePhotosPickerApi>()
        // Fallback for any URL the implementation may call that we do not explicitly stub
        whenever(pickerApi.streamMedia(any())).thenReturn(Response.success(null))
        // Poster frame on expired baseUrl — success or null body; does not affect the contract
        whenever(pickerApi.streamMedia(eq(expiredPosterUrl))).thenReturn(Response.success(null))
        // Main video stream on expired URL → 401 (triggers re-fetch)
        whenever(pickerApi.streamMedia(eq(expiredVideoUrl))).thenReturn(httpErrorResponse(401))
        // getMediaItem does NOT exist on GooglePhotosPickerApi yet → compile error
        whenever(pickerApi.getMediaItem(eq(itemId), eq(testSessionId)))
            .thenReturn(freshPickedMediaItem(itemId, type = "VIDEO", mimeType = "video/mp4"))
        // Poster frame on fresh baseUrl (optional, depends on implementation)
        whenever(pickerApi.streamMedia(eq(freshPosterUrl))).thenReturn(Response.success(null))
        // Video stream retry on fresh URL → 200
        whenever(pickerApi.streamMedia(eq(freshVideoUrl))).thenReturn(successVideoResponse())

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)
        val chunkDao = mock<ChunkMetadataDao>()
        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("primary-token")
        whenever(creds.getChatId()).thenReturn("99")
        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "pool-token"))
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(telegramOkResponse())

        val manager = buildManager(pickerApi, uploadApi, mediaDao, chunkDao, creds, botPool, mockPassthroughRateLimiter())
        val item = GooglePhotosMediaItem(
            id = itemId,
            mimeType = "video/mp4",
            filename = "$itemId.mp4",
            baseUrl = expiredBaseUrl,
            mediaMetadata = GooglePhotosMediaMetadata(creationTime = null, width = "1920", height = "1080"),
        )

        // importGooglePhotosMediaItem does NOT accept sessionId yet → compile error
        val result = manager.importGooglePhotosMediaItem(item, testSessionId)

        assertTrue(
            "HTTP 401 video: import must return success after re-fetching baseUrl and retrying",
            result.isSuccess,
        )
    }

    // ── Contract 3 — HTTP 403 is treated as URL expiry (same re-fetch + retry) ─

    /**
     * Google Photos returns 403 as well as 401 for expired baseUrls. The import manager must
     * treat both status codes as a signal to re-fetch and retry.
     *
     * FAILS NOW (compile): same two missing APIs.
     * PASSES AFTER: 403 is included in the expired-URL detection branch alongside 401.
     */
    @Test
    fun imageItem_http403_refetchesBaseUrl_retrySucceeds_returnsSuccess() = runTest {
        val itemId = "img-item-403"
        val expiredImageUrl = GooglePhotosUrls.fullResolutionImageUrl(expiredBaseUrl)
        val freshImageUrl   = GooglePhotosUrls.fullResolutionImageUrl(freshBaseUrl)

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(eq(expiredImageUrl))).thenReturn(httpErrorResponse(403))
        // getMediaItem does NOT exist on GooglePhotosPickerApi yet → compile error
        whenever(pickerApi.getMediaItem(eq(itemId), eq(testSessionId)))
            .thenReturn(freshPickedMediaItem(itemId))
        whenever(pickerApi.streamMedia(eq(freshImageUrl))).thenReturn(successImageResponse())

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)
        val chunkDao = mock<ChunkMetadataDao>()
        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("primary-token")
        whenever(creds.getChatId()).thenReturn("99")
        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "pool-token"))
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(telegramOkResponse())

        val manager = buildManager(pickerApi, uploadApi, mediaDao, chunkDao, creds, botPool, mockPassthroughRateLimiter())
        val item = GooglePhotosMediaItem(
            id = itemId,
            mimeType = "image/jpeg",
            filename = "$itemId.jpg",
            baseUrl = expiredBaseUrl,
            mediaMetadata = GooglePhotosMediaMetadata(creationTime = null, width = "640", height = "480"),
        )

        val result = manager.importGooglePhotosMediaItem(item, testSessionId)

        assertTrue(
            "HTTP 403 image: import must return success after re-fetching baseUrl and retrying",
            result.isSuccess,
        )
    }

    // ── Contract 4 — retry also returns 401 → Result.failure, no infinite loop ─

    /**
     * Even after re-fetching `baseUrl`, if `streamMedia` still returns 401, the import must
     * give up and return `Result.failure`. It must NOT retry again indefinitely.
     *
     * The mock returns 401 for BOTH the expired URL and the fresh URL. If the implementation
     * loops, the mock will keep returning 401 and the test will hang (or produce a failure via
     * [org.junit.Test] timeout if configured — the deterministic assertion is `isFailure`).
     *
     * FAILS NOW (compile): same two missing APIs.
     * PASSES AFTER: exactly one retry is performed; second 401 propagates as `Result.failure`.
     */
    @Test
    fun imageItem_http401_retryAlsoHttp401_returnsFailure_noInfiniteLoop() = runTest {
        val itemId = "img-item-401-both"
        val expiredImageUrl = GooglePhotosUrls.fullResolutionImageUrl(expiredBaseUrl)
        val freshImageUrl   = GooglePhotosUrls.fullResolutionImageUrl(freshBaseUrl)

        val pickerApi = mock<GooglePhotosPickerApi>()
        // First attempt → 401
        whenever(pickerApi.streamMedia(eq(expiredImageUrl))).thenReturn(httpErrorResponse(401))
        // getMediaItem does NOT exist on GooglePhotosPickerApi yet → compile error
        whenever(pickerApi.getMediaItem(eq(itemId), eq(testSessionId)))
            .thenReturn(freshPickedMediaItem(itemId))
        // Retry with fresh URL → also 401
        whenever(pickerApi.streamMedia(eq(freshImageUrl))).thenReturn(httpErrorResponse(401))

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)
        val chunkDao = mock<ChunkMetadataDao>()
        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("primary-token")
        whenever(creds.getChatId()).thenReturn("99")
        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "pool-token"))
        val uploadApi = mock<TelegramBotApi>()

        val manager = buildManager(pickerApi, uploadApi, mediaDao, chunkDao, creds, botPool, mockPassthroughRateLimiter())
        val item = GooglePhotosMediaItem(
            id = itemId,
            mimeType = "image/jpeg",
            filename = "$itemId.jpg",
            baseUrl = expiredBaseUrl,
            mediaMetadata = GooglePhotosMediaMetadata(creationTime = null, width = "100", height = "100"),
        )

        val result = manager.importGooglePhotosMediaItem(item, testSessionId)

        assertFalse(
            "Both attempts return HTTP 401: import must return Result.failure (no infinite retry)",
            result.isSuccess,
        )
    }

    // ── Contract 5 — retry uses the fresh baseUrl from getMediaItem, not the expired original ─

    /**
     * After a 401 triggers a re-fetch, the retry download must construct its URL from
     * `getMediaItem`'s returned `baseUrl` (i.e. [freshBaseUrl]), NOT the item's original
     * expired `baseUrl`.
     *
     * This is structurally enforced by the mock setup: if the implementation re-uses the expired
     * URL the second time, `streamMedia(expiredImageUrl)` returns 401 again, causing import to
     * fail and the `assertTrue(result.isSuccess)` assertion below to fail. The explicit
     * `verify(pickerApi).streamMedia(eq(freshImageUrl))` makes the contract self-documenting.
     *
     * FAILS NOW (compile): same two missing APIs.
     * PASSES AFTER: implementation reads `baseUrl` from the `PickedMediaItem` returned by
     * `getMediaItem` and constructs the retry URL from that fresh value.
     */
    @Test
    fun imageItem_http401_retryDownloadUsesFreshBaseUrl_notOriginalExpiredUrl() = runTest {
        val itemId = "img-url-verify"
        val expiredImageUrl = GooglePhotosUrls.fullResolutionImageUrl(expiredBaseUrl)
        val freshImageUrl   = GooglePhotosUrls.fullResolutionImageUrl(freshBaseUrl)

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(eq(expiredImageUrl))).thenReturn(httpErrorResponse(401))
        // getMediaItem does NOT exist on GooglePhotosPickerApi yet → compile error
        whenever(pickerApi.getMediaItem(eq(itemId), eq(testSessionId)))
            .thenReturn(freshPickedMediaItem(itemId))
        whenever(pickerApi.streamMedia(eq(freshImageUrl))).thenReturn(successImageResponse())

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)
        val chunkDao = mock<ChunkMetadataDao>()
        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("primary-token")
        whenever(creds.getChatId()).thenReturn("99")
        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "pool-token"))
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(telegramOkResponse())

        val manager = buildManager(pickerApi, uploadApi, mediaDao, chunkDao, creds, botPool, mockPassthroughRateLimiter())
        val item = GooglePhotosMediaItem(
            id = itemId,
            mimeType = "image/jpeg",
            filename = "$itemId.jpg",
            baseUrl = expiredBaseUrl,
            mediaMetadata = GooglePhotosMediaMetadata(creationTime = null, width = "100", height = "100"),
        )

        val result = manager.importGooglePhotosMediaItem(item, testSessionId)

        assertTrue(
            "Fresh URL contract: import must succeed (confirms fresh URL was used, not expired)",
            result.isSuccess,
        )
        // Explicit assertion: the fresh download URL must have been called
        verify(pickerApi).streamMedia(eq(freshImageUrl))
    }
}
