package com.ulap.data.googlephotos

import android.app.Application
import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.remote.BotPool
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramMessage
import com.ulap.data.remote.TelegramRateLimiter
import com.ulap.data.remote.TelegramResponse
import com.ulap.domain.repository.CredentialRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * RED area C: relay success persists [BackupStatus.CLOUD_ONLY], empty contentUri, telegramFileId from
 * Telegram response — [tdd/task_1_red_spec.md].
 *
 * Traceability: xb-brt-forge
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GooglePhotosImportRelayIntegrationBrt {

    private val trace = "xb-brt-forge tdd/task_1_red_spec.md"

    private lateinit var server: MockWebServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun areaC_imageImport_success_persistsCloudOnlyEntity() {
        server = MockWebServer()
        server.start()

        val telegramApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TelegramBotApi::class.java)

        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"result":{"message_id":42,"document":{"file_id":"test-file-id-123"}}}""",
            ),
        )

        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        runBlocking {
            val pickerApi = mock<GooglePhotosPickerApi>()
            whenever(pickerApi.streamMedia(any())).thenReturn(
                Response.success(jpegHeader.toResponseBody("image/jpeg".toMediaType())),
            )

            val mediaDao = mock<MediaItemDao>()
            whenever(
                mediaDao.countItemsMatchingImportFingerprint(
                    any(),
                    any(),
                    any(),
                    any(),
                ),
            ).thenReturn(0)

            val chunkDao = mock<ChunkMetadataDao>()
            val creds = mock<CredentialRepository>()
            whenever(creds.getBotToken()).thenReturn("test-token")
            whenever(creds.getChatId()).thenReturn("99")
            whenever(creds.getAdditionalBotTokens()).thenReturn(emptyList())

            val botPool = BotPool(creds)
            val rateLimiter = mock<TelegramRateLimiter> { rl ->
                onBlocking {
                    rl.withRateLimit(any<suspend () -> TelegramResponse<TelegramMessage>>())
                }.doSuspendableAnswer { inv ->
                    @Suppress("UNCHECKED_CAST")
                    (inv.getArgument(0) as suspend () -> TelegramResponse<TelegramMessage>).invoke()
                }
            }

            val manager = GooglePhotosImportManager(
                pickerApi = pickerApi,
                uploadTelegramBotApi = telegramApi,
                mediaItemDao = mediaDao,
                chunkMetadataDao = chunkDao,
                rateLimiter = rateLimiter,
                credentialRepository = creds,
                botPool = botPool,
            )

            val item = GooglePhotosMediaItem(
                id = "relay-int-1",
                mimeType = "image/jpeg",
                filename = "relay-int-1.jpg",
                baseUrl = "https://lh3.googleusercontent.com/abc",
                mediaMetadata = GooglePhotosMediaMetadata(
                    creationTime = "2020-01-01T00:00:00Z",
                    width = "100",
                    height = "100",
                ),
            )

            val result = manager.importGooglePhotosMediaItem(item)
            assertTrue("$trace import ok", result.isSuccess)

            val captor = argumentCaptor<MediaItemEntity>()
            verify(mediaDao).upsert(captor.capture())
            val saved = captor.firstValue
            assertEquals("$trace backupStatus CLOUD_ONLY", BackupStatus.CLOUD_ONLY, saved.backupStatus)
            assertEquals("$trace contentUri empty", "", saved.contentUri)
            assertEquals("$trace telegramFileId", "test-file-id-123", saved.telegramFileId)
        }

        val req = server.takeRequest()
        assertEquals("$trace POST", "POST", req.method)
        assertTrue("$trace sendDocument path", req.path.orEmpty().contains("sendDocument"))
    }
}
