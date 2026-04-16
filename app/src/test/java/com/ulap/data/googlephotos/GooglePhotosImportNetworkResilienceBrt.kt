package com.ulap.data.googlephotos

import android.content.Context
import com.ulap.data.auth.GoogleAuthManager
import com.ulap.data.auth.OAuthTokenStore
import com.ulap.data.auth.PkceTokenClient
import com.ulap.data.auth.TokenResult
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
import com.ulap.testutil.InMemorySharedPreferences
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.Response
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bug Reproduction Tests — network resilience, auth token handling, and concurrency safety
 * for Google Photos import.
 *
 * ## Defects covered
 *
 * BRT-1: tokenRefresher is not called before getMediaItem on HTTP 403.
 * BRT-2: SocketException("Connection reset") from sendDocument is not retried.
 * BRT-3: SocketTimeoutException from sendDocument is not retried.
 * BRT-4: clearAccessToken() wipes the refresh token (calls clearTokens instead of expireAccessToken).
 * BRT-5: Concurrent refreshToken() calls each make a separate HTTP round-trip (no Mutex guard).
 *
 * Deterministic: no real network, no I/O beyond temp cache dir, no real clocks, no randomness.
 */
class GooglePhotosImportNetworkResilienceBrt {

    private lateinit var tempCacheDir: File
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        tempCacheDir = File(
            System.getProperty("java.io.tmpdir"),
            "net-resilience-brt-${System.nanoTime()}",
        )
        tempCacheDir.mkdirs()
        mockContext = mock()
        whenever(mockContext.cacheDir).thenReturn(tempCacheDir)
    }

    @After
    fun tearDown() {
        tempCacheDir.deleteRecursively()
    }

    // ── Response / data builders ───────────────────────────────────────────────

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    private fun successImageResponse(): Response<okhttp3.ResponseBody> =
        Response.success(jpegBytes.toResponseBody("image/jpeg".toMediaType()))

    private fun http403Response(): Response<okhttp3.ResponseBody> =
        Response.error(403, "".toResponseBody("application/json".toMediaTypeOrNull()))

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

    private fun imageItem(
        id: String,
        baseUrl: String = "https://lh3.googleusercontent.com/$id",
    ): GooglePhotosMediaItem = GooglePhotosMediaItem(
        id = id,
        mimeType = "image/jpeg",
        filename = "$id.jpg",
        baseUrl = baseUrl,
        mediaMetadata = GooglePhotosMediaMetadata(creationTime = null, width = "640", height = "480"),
    )

    private fun freshPickedItem(
        id: String,
        freshBaseUrl: String,
        mimeType: String = "image/jpeg",
    ): PickedMediaItem = PickedMediaItem(
        id = id,
        type = "PHOTO",
        mediaFile = PickedMediaFile(
            baseUrl = freshBaseUrl,
            mimeType = mimeType,
            filename = "$id.jpg",
            mediaFileMetadata = null,
        ),
        createTime = null,
    )

    // ── Shared mock helpers ────────────────────────────────────────────────────

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
        appContext = mockContext,
    )

    // ── BRT-1: tokenRefresher is called before getMediaItem on HTTP 403 ───────

    /**
     * HTTP 403 on `streamMedia(expiredImageUrl)` signals an expired Google Photos baseUrl.
     * A `tokenRefresher` lambda is provided to re-authenticate before re-fetching the item.
     *
     * After the fix, the manager must:
     *   1. Detect the 403.
     *   2. Call `tokenRefresher()` to obtain a fresh Google OAuth access token.
     *   3. Call `pickerApi.getMediaItem(id, sessionId)` to get a new baseUrl.
     *   4. Retry `streamMedia(freshImageUrl)` and upload successfully.
     *
     * FAILS NOW (compile): `importGooglePhotosMediaItem` has no `tokenRefresher` parameter.
     * FAILS after compile fix (logic): manager calls `getMediaItem` without first calling
     *   `tokenRefresher` → Google API returns 403 → freshBaseUrl is null → import fails.
     * PASSES AFTER: manager invokes `tokenRefresher()` before the `getMediaItem` call.
     */
    @Test
    fun imageItem_http403_tokenRefresherCalledBeforeGetMediaItem_importSucceeds() = runTest {
        val itemId = "img-403-refresh"
        val expiredBaseUrl = "https://lh3.googleusercontent.com/expired"
        val freshBaseUrl = "https://lh3.googleusercontent.com/fresh"
        val expiredImageUrl = GooglePhotosUrls.fullResolutionImageUrl(expiredBaseUrl)
        val freshImageUrl = GooglePhotosUrls.fullResolutionImageUrl(freshBaseUrl)
        val sessionId = "session-brt-001"

        val tokenRefresherCallCount = AtomicInteger(0)
        val tokenRefresher: suspend () -> Boolean = {
            tokenRefresherCallCount.incrementAndGet()
            true
        }

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(expiredImageUrl)).thenReturn(http403Response())
        whenever(pickerApi.getMediaItem(itemId, sessionId))
            .thenReturn(freshPickedItem(itemId, freshBaseUrl))
        whenever(pickerApi.streamMedia(freshImageUrl)).thenReturn(successImageResponse())

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any()))
            .thenReturn(0)

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "tok"))

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

        val result = manager.importGooglePhotosMediaItem(
            item = imageItem(itemId, expiredBaseUrl),
            sessionId = sessionId,
            tokenRefresher = tokenRefresher,
        )

        assertTrue(
            "HTTP 403 + tokenRefresher: import must succeed after token refresh + URL re-fetch " +
                "(was: ${result.exceptionOrNull()?.message})",
            result.isSuccess,
        )
        assertTrue(
            "tokenRefresher must have been called at least once " +
                "(actual callCount: ${tokenRefresherCallCount.get()})",
            tokenRefresherCallCount.get() >= 1,
        )
    }

    // ── BRT-2: image ConnectionResetException is retried ──────────────────────

    /**
     * `sendDocument` throws `SocketException("Connection reset")` on the first attempt,
     * then returns `telegramOkResponse()` on the second attempt.
     *
     * FAILS NOW: the retry loop only catches `TelegramRateLimitException`; a `SocketException`
     *   propagates immediately as `Result.failure` and `sendDocument` is called only once.
     * PASSES AFTER: `SocketException` (IOException subtype) is added to the set of
     *   retry-eligible exceptions so the manager retries the upload and eventually succeeds.
     */
    @Test
    fun imageImport_connectionResetException_isRetried_andSucceeds() = runTest {
        val item = imageItem("img-conn-reset")

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).thenReturn(successImageResponse())

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any()))
            .thenReturn(0)

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "tok"))
        whenever(botPool.minTempCooldownExpiryMs()).thenReturn(0L)

        val sendDocCallCount = AtomicInteger(0)
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .doSuspendableAnswer {
                val attempt = sendDocCallCount.incrementAndGet()
                if (attempt == 1) {
                    throw SocketException("Connection reset")
                }
                telegramOkResponse()
            }

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")

        assertTrue(
            "SocketException('Connection reset') must be retried; import must succeed " +
                "(was: ${result.exceptionOrNull()?.message})",
            result.isSuccess,
        )
        assertTrue(
            "sendDocument must have been called at least twice (actual: ${sendDocCallCount.get()})",
            sendDocCallCount.get() >= 2,
        )
    }

    // ── BRT-3: image SocketTimeoutException is retried ────────────────────────

    /**
     * `sendDocument` throws `SocketTimeoutException("timeout")` on the first attempt,
     * then returns `telegramOkResponse()` on the second attempt.
     *
     * FAILS NOW: the retry loop only catches `TelegramRateLimitException`; a
     *   `SocketTimeoutException` propagates immediately as `Result.failure` and `sendDocument`
     *   is called only once.
     * PASSES AFTER: `SocketTimeoutException` (IOException subtype) is added to the set of
     *   retry-eligible exceptions so the manager retries the upload and eventually succeeds.
     */
    @Test
    fun imageImport_socketTimeoutException_isRetried_andSucceeds() = runTest {
        val item = imageItem("img-sock-timeout")

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).thenReturn(successImageResponse())

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any()))
            .thenReturn(0)

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "tok"))
        whenever(botPool.minTempCooldownExpiryMs()).thenReturn(0L)

        val sendDocCallCount = AtomicInteger(0)
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .doSuspendableAnswer {
                val attempt = sendDocCallCount.incrementAndGet()
                if (attempt == 1) {
                    throw SocketTimeoutException("timeout")
                }
                telegramOkResponse()
            }

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")

        assertTrue(
            "SocketTimeoutException must be retried; import must succeed " +
                "(was: ${result.exceptionOrNull()?.message})",
            result.isSuccess,
        )
        assertTrue(
            "sendDocument must have been called at least twice (actual: ${sendDocCallCount.get()})",
            sendDocCallCount.get() >= 2,
        )
    }

    // ── BRT-4: clearAccessToken preserves refresh token ───────────────────────

    /**
     * `GoogleAuthManager.clearAccessToken()` currently delegates to
     * `OAuthTokenStore.clearTokens()`, which removes KEY_REFRESH_TOKEN from SharedPreferences.
     *
     * After the fix, `clearAccessToken()` must call the new `OAuthTokenStore.expireAccessToken()`
     * (which sets KEY_EXPIRES_AT = 0L without touching KEY_REFRESH_TOKEN), so that a subsequent
     * `refreshToken()` call can still read the refresh token and obtain a new access token.
     *
     * FAILS NOW: `clearTokens()` deletes KEY_REFRESH_TOKEN → `getRefreshToken()` returns null.
     * PASSES AFTER: `clearAccessToken()` only expires the access token; refresh token survives.
     */
    @Test
    fun clearAccessToken_doesNotWipeRefreshToken_refreshTokenStillUsable() {
        val tokenStore = OAuthTokenStore(InMemorySharedPreferences())
        val tokenClient = mock<PkceTokenClient>()
        val googleAuthManager = GoogleAuthManager(tokenStore, tokenClient)

        tokenStore.saveTokens(
            accessToken = "access-tok",
            refreshToken = "refresh-tok",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
        )

        googleAuthManager.clearAccessToken()

        val survivingRefreshToken = tokenStore.getRefreshToken()
        assertNotNull(
            "clearAccessToken() must NOT wipe the refresh token; " +
                "getRefreshToken() returned null after clearAccessToken()",
            survivingRefreshToken,
        )
        assertEquals(
            "Refresh token value must be exactly 'refresh-tok' after clearAccessToken()",
            "refresh-tok",
            survivingRefreshToken,
        )
    }

    // ── BRT-5: concurrent refreshToken calls result in only one network request ─

    /**
     * Six coroutines all call `googleAuthManager.refreshToken(clientId, clientSecret)`
     * concurrently under the same coroutineScope. A mock `PkceTokenClient` records how many
     * times `refreshToken()` is called.
     *
     * After the Mutex + double-check fix, only the coroutine that first acquires the lock
     * performs the HTTP call; the remaining five observe that the token is already fresh and
     * skip the network round-trip entirely.
     *
     * FAILS NOW: no Mutex in `refreshToken()` → all 6 coroutines race to call
     *   `tokenClient.refreshToken(...)` in parallel → verify(tokenClient, times(1)) fails.
     * PASSES AFTER: Mutex + double-check (isAccessTokenExpired() re-check inside the lock)
     *   ensures exactly one HTTP call is made regardless of concurrency.
     */
    @Test
    fun refreshToken_concurrent6Calls_makesExactlyOneNetworkRequest() = runTest {
        val tokenStore = OAuthTokenStore(InMemorySharedPreferences())
        tokenStore.saveTokens(
            accessToken = "old-access",
            refreshToken = "valid-refresh",
            expiresAtMillis = System.currentTimeMillis() - 1_000L,
        )

        val tokenClient = mock<PkceTokenClient>()
        whenever(tokenClient.refreshToken(any(), any(), any())).thenReturn(
            TokenResult.Success(
                accessToken = "new-access-token",
                refreshToken = null,
                expiresInSeconds = 3600,
            ),
        )

        val googleAuthManager = GoogleAuthManager(tokenStore, tokenClient)

        val results = mutableListOf<Boolean>()
        coroutineScope {
            repeat(6) {
                launch {
                    val ok = googleAuthManager.refreshToken("client-id", "client-secret")
                    synchronized(results) { results.add(ok) }
                }
            }
        }

        assertEquals(
            "All 6 concurrent refreshToken() calls must return true",
            6,
            results.count { it },
        )
        verify(tokenClient, times(1)).refreshToken(any(), any(), any())
    }
}
