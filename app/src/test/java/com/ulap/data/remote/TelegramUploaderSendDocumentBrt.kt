package com.ulap.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.RequestBody
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bug Reproduction Tests — TelegramUploader.uploadSingle must never call sendVideo.
 *
 * ## Defect (BUG-005b / Error 18)
 *
 * `uploadSingle` currently routes `video/` MIME types through `api.sendVideo`:
 *
 * ```
 * val isVideo = mimeType.startsWith("video/")
 * if (isVideo) api.sendVideo(...) else api.sendDocument(...)
 * ```
 *
 * Telegram's `sendVideo` API does not support WebM. When a `.webm` file is sent,
 * Telegram reclassifies it internally as an `animation` and returns a response where
 * both `document` and `video` fields are `null`. Our `fileId` extraction then fails:
 *
 * ```
 * val fileId = msg.document?.fileId ?: msg.video?.fileId
 *     ?: return UploadResult.Error(Exception("No file_id in response"))
 * ```
 *
 * ## Required contract (these tests encode)
 *
 * `uploadSingle` must ALWAYS use `sendDocument` regardless of MIME type.
 * `sendVideo` must never be called. The returned `fileId` must come from the
 * `document` field of the response.
 *
 * ## Why these tests FAIL against current code
 *
 * The mocked `api` has `sendDocument` wired to return a success response and `sendVideo`
 * wired to throw `AssertionError`. With the current defective code, `api.sendVideo` is
 * invoked for `video/` MIME types, the mock throws, and the upload result is
 * `UploadResult.Error` instead of `UploadResult.Success` — failing all three assertions.
 *
 * Deterministic: no network — `TelegramBotApi` and `TelegramRateLimiter` are mocked.
 */
class TelegramUploaderSendDocumentBrt {

    // ─────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun tinyStream(): InputStream = "hello".byteInputStream()

    /**
     * Mocked [TelegramRateLimiter] that bypasses all rate-limiting logic and immediately
     * invokes the supplied block.
     */
    private fun noOpRateLimiter(): TelegramRateLimiter = mock { rl ->
        onBlocking {
            @Suppress("UNCHECKED_CAST")
            rl.withRateLimit(any<suspend () -> Any>())
        }.doSuspendableAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            (inv.getArgument(0) as suspend () -> Any).invoke()
        }
    }

    private fun successResponse(fileId: String, messageId: Long): TelegramResponse<TelegramMessage> =
        TelegramResponse(
            ok = true,
            result = TelegramMessage(
                messageId = messageId,
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

    /**
     * Builds an [TelegramBotApi] mock where:
     * - `sendDocument` returns a success response with the given [fileId].
     * - `sendVideo` throws [AssertionError] — it must NEVER be called.
     */
    private fun apiWithDocumentSuccess(fileId: String): TelegramBotApi {
        val msgId = AtomicInteger(1)
        return mock {
            onBlocking {
                sendDocument(
                    token = any(),
                    chatId = any(),
                    document = any(),
                    caption = anyOrNull(),
                    thumbnail = anyOrNull(),
                )
            }.doSuspendableAnswer {
                successResponse(fileId, msgId.getAndIncrement().toLong())
            }

            onBlocking {
                sendVideo(
                    token = any(),
                    chatId = any(),
                    video = any(),
                    caption = anyOrNull(),
                    supportsStreaming = anyOrNull(),
                )
            }.doSuspendableAnswer {
                throw AssertionError("sendVideo must not be called — use sendDocument for all small uploads")
            }
        }
    }

    private fun buildUploader(api: TelegramBotApi): TelegramUploader =
        TelegramUploader(
            api = api,
            uploadApi = mock(),
            rateLimiter = noOpRateLimiter(),
        )

    // ─────────────────────────────────────────────────────────────────────────
    // BRTs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A small `video/mp4` file must use `sendDocument`, not `sendVideo`.
     * Fails with current code because `sendVideo` is called and the mock throws.
     */
    @Test
    fun uploadSingle_videoMp4_callsSendDocument_neverSendVideo() = runTest {
        val expectedFileId = "file-mp4-001"
        val api = apiWithDocumentSuccess(expectedFileId)
        val uploader = buildUploader(api)

        val result = uploader.uploadMedia(
            token = "1234567890:AAFakeToken",
            chatId = "-1001234567890",
            inputStream = tinyStream(),
            fileName = "clip.mp4",
            mimeType = "video/mp4",
            fileSize = 5L,
        )

        assertTrue(
            "Uploading a video/mp4 file via sendDocument must succeed (sendVideo must not be called).",
            result is UploadResult.Success,
        )
        assertEquals(
            "fileId must come from the document field of the response.",
            expectedFileId,
            (result as UploadResult.Success).fileId,
        )
    }

    /**
     * A small `video/webm` file (the original crash-triggering format) must use
     * `sendDocument`, not `sendVideo`.
     * Fails with current code because `sendVideo` is called and the mock throws.
     */
    @Test
    fun uploadSingle_videoWebm_callsSendDocument_neverSendVideo() = runTest {
        val expectedFileId = "file-webm-001"
        val api = apiWithDocumentSuccess(expectedFileId)
        val uploader = buildUploader(api)

        val result = uploader.uploadMedia(
            token = "1234567890:AAFakeToken",
            chatId = "-1001234567890",
            inputStream = tinyStream(),
            fileName = "screen_record.webm",
            mimeType = "video/webm",
            fileSize = 5L,
        )

        assertTrue(
            "Uploading a video/webm file via sendDocument must succeed (sendVideo must not be called).",
            result is UploadResult.Success,
        )
        assertEquals(
            "fileId must come from the document field of the response.",
            expectedFileId,
            (result as UploadResult.Success).fileId,
        )
    }
}
