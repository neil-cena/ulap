package com.ulap.data.googlephotos

import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.remote.TelegramBotApi
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * RED spec: [C:\Users\neila\.cursor\extreme-build\workspaces\c--users--neila--ulap\tdd\task_1_red_spec.md]
 * Traceability: xb-brt-forge
 */
class GooglePhotosRelayBrt {

    private val trace = "xb-brt-forge tdd/task_1_red_spec.md"

    @Test
    fun areaA_downloadVideoUrl_appendsEqualsDv() {
        val missing = "https://lh3.googleusercontent.com/vid"
        assertEquals(
            "$trace video =dv",
            "https://lh3.googleusercontent.com/vid=dv",
            GooglePhotosUrls.downloadVideoUrl(missing),
        )
        val has = "https://lh3.googleusercontent.com/x=dv"
        assertEquals("$trace video already =dv", has, GooglePhotosUrls.downloadVideoUrl(has))
    }

    @Test
    fun areaA_fullResolutionImageUrl_appendsEqualsD() {
        val missing = "https://lh3.googleusercontent.com/abc"
        val expectedMissing = "https://lh3.googleusercontent.com/abc=d"
        assertEquals(
            "$trace expected missing-suffix case",
            expectedMissing,
            GooglePhotosUrls.fullResolutionImageUrl(missing),
        )
        val already = "https://lh3.googleusercontent.com/xyz=d"
        assertEquals(
            "$trace expected already-suffixed case",
            already,
            GooglePhotosUrls.fullResolutionImageUrl(already),
        )
    }

    @Test
    fun areaB_sendPhotoFromUrl_postsPhotoUrlToTelegram() {
        val server = MockWebServer()
        server.start()
        try {
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TelegramBotApi::class.java)
            val photoUrl = "https://example.com/photo.jpg"
            server.enqueue(
                MockResponse().setBody(
                    """{"ok":true,"result":{"message_id":1,"photo":[{"file_id":"test-file-id-123","width":10,"height":10}]}}""",
                ),
            )
            runBlocking {
                api.sendPhotoFromUrl(
                    token = "dummy",
                    chatId = "99".toRequestBody("text/plain".toMediaType()),
                    photoUrl = photoUrl.toRequestBody("text/plain".toMediaType()),
                    caption = null,
                )
            }
            val req = server.takeRequest()
            assertEquals("$trace POST", "POST", req.method)
            assertTrue("$trace path sendPhoto", req.path.orEmpty().contains("sendPhoto"))
            val body = req.body!!.readUtf8()
            assertTrue("$trace multipart contains photo URL", body.contains(photoUrl))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun areaC_cloudEntity_matchesRedSpec() {
        val item = GooglePhotosMediaItem(
            id = "abc",
            mimeType = "image/jpeg",
            filename = "x.jpg",
            baseUrl = "https://lh3.googleusercontent.com/x",
            mediaMetadata = GooglePhotosMediaMetadata(creationTime = "2020-01-01T00:00:00Z"),
        )
        val entity = GooglePhotosImportEntityFactory.cloudEntityFromGooglePhoto(
            item = item,
            telegramFileId = "test-file-id-123",
            messageId = 42L,
        )
        assertEquals("$trace CLOUD_ONLY", BackupStatus.CLOUD_ONLY, entity.backupStatus)
        assertEquals("$trace empty contentUri", "", entity.contentUri)
        assertEquals("$trace telegramFileId", "test-file-id-123", entity.telegramFileId)
        assertTrue("$trace id prefix", entity.id.startsWith("gphoto_"))
    }

    @Test
    fun areaD_pixelDimensions_parsedFromApiStrings() {
        val meta = GooglePhotosMediaMetadata(
            creationTime = null,
            width = "4032",
            height = "3024",
        )
        val (w, h) = meta.pixelDimensions()
        assertEquals("$trace width", 4032, w)
        assertEquals("$trace height", 3024, h)
    }
}
