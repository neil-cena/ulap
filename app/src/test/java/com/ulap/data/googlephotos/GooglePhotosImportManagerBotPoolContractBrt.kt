package com.ulap.data.googlephotos

import android.app.Application
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
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * RED/GREEN contract: successful Google Photos **image** import must call [BotPool.selectForUpload].
 *
 * Traceability: xb-brt-forge | tdd/task_1_red_spec.md | generalPurpose
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GooglePhotosImportManagerBotPoolContractBrt {

    private val trace =
        "xb-brt-forge | tdd/task_1_red_spec.md | BotPool.selectForUpload (generalPurpose)"

    @Test
    fun imageImport_success_invokesSelectForUploadOnBotPool() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        runBlocking {
            val pickerApi = mock<GooglePhotosPickerApi>()
            whenever(pickerApi.streamMedia(any())).thenReturn(
                Response.success(jpegHeader.toResponseBody("image/jpeg".toMediaType())),
            )

            val mediaDao = mock<MediaItemDao>()
            whenever(
                mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any()),
            ).thenReturn(0)

            val chunkDao = mock<ChunkMetadataDao>()
            val creds = mock<CredentialRepository>()
            whenever(creds.getBotToken()).thenReturn("primary-token")
            whenever(creds.getChatId()).thenReturn("99")

            val botPool = mock<BotPool>()
            whenever(botPool.selectForUpload()).thenReturn(BotCredential(0, "pool-token"))

            val telegramResponse = TelegramResponse(
                ok = true,
                result = TelegramMessage(
                    messageId = 1L,
                    document = TelegramDocument(
                        fileId = "fid",
                        fileSize = 3L,
                        fileName = "x.jpg",
                        mimeType = "image/jpeg",
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

            val uploadApi = mock<TelegramBotApi>()
            whenever(
                uploadApi.sendDocument(
                    token = any(),
                    chatId = any(),
                    document = any(),
                    caption = anyOrNull(),
                    thumbnail = anyOrNull(),
                ),
            ).thenReturn(telegramResponse)

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
                uploadTelegramBotApi = uploadApi,
                mediaItemDao = mediaDao,
                chunkMetadataDao = chunkDao,
                rateLimiter = rateLimiter,
                credentialRepository = creds,
                botPool = botPool,
            )

            val item = GooglePhotosMediaItem(
                id = "botpool-1",
                mimeType = "image/jpeg",
                filename = "botpool-1.jpg",
                baseUrl = "https://lh3.googleusercontent.com/abc",
                mediaMetadata = GooglePhotosMediaMetadata(
                    creationTime = "2020-01-01T00:00:00Z",
                    width = "100",
                    height = "100",
                ),
            )

            val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")
            assertTrue("$trace import ok", result.isSuccess)
            verify(botPool, atLeast(1)).selectForUpload()
        }
    }
}
