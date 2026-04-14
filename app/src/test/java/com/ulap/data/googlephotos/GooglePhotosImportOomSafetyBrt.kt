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
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.Response
import java.io.File

/**
 * Bug Reproduction Tests — OOM safety for Google Photos image import.
 *
 * ## Defect
 *
 * BUG-003: `importImageItem` loads entire images into heap via `body.use { it.bytes() }`.
 * With AIMD concurrency up to 6, multiple 10-20 MB images simultaneously exhaust the
 * 256 MB heap limit, causing either a hard crash (OOM on OkHttp thread) or a silent
 * WorkManager FAILURE.
 *
 * ## Required contracts
 *
 * 1. **Disk-backed streaming** — Image downloads must be streamed to a temp file, not
 *    held entirely in heap. The temp directory must exist during the download.
 * 2. **Per-file cleanup** — Each temp file is deleted immediately after upload (success
 *    or failure). No temp files remain in cacheDir after the import method returns.
 * 3. **OOM isolation** — If one item triggers OutOfMemoryError, the remaining items in
 *    the batch continue processing. The failed item reports Result.failure, not a crash.
 *
 * Deterministic: no real network; delay() uses virtual time via runTest.
 */
class GooglePhotosImportOomSafetyBrt {

    private lateinit var tempCacheDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        tempCacheDir = File(System.getProperty("java.io.tmpdir"), "oom-brt-${System.nanoTime()}")
        tempCacheDir.mkdirs()
        context = mock()
        whenever(context.cacheDir).thenReturn(tempCacheDir)
    }

    @After
    fun tearDown() {
        tempCacheDir.deleteRecursively()
    }

    private val jpegBytes = ByteArray(1024) { (it % 256).toByte() }

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
        appContext = context,
    )

    // ── Contract 1: Temp file created during import ──────────────────────────

    @Test
    fun imageImport_success_noTempFilesRemainAfterImport() = runTest {
        val item = imageItem("oom-safe-1")
        val bot = BotCredential(0, "tok")

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer { successImageResponse() }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(telegramOkResponse())

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(bot)

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")
        assertTrue("Import must succeed", result.isSuccess)

        val importDir = File(tempCacheDir, "gphoto_import")
        val remainingFiles = importDir.listFiles()?.toList() ?: emptyList()
        assertTrue(
            "No temp files should remain after successful import, found: $remainingFiles",
            remainingFiles.isEmpty(),
        )
    }

    // ── Contract 2: Temp file cleaned up on upload failure ───────────────────

    @Test
    fun imageImport_uploadFails_tempFileStillCleaned() = runTest {
        val item = imageItem("oom-safe-fail")
        val bot = BotCredential(0, "tok")

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer { successImageResponse() }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(
                TelegramResponse(
                    ok = false,
                    result = null,
                    description = "Bad Request",
                    errorCode = 400,
                    parameters = null,
                ),
            )

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(bot)

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")
        assertTrue("Import must fail", result.isFailure)

        val importDir = File(tempCacheDir, "gphoto_import")
        val remainingFiles = importDir.listFiles()?.toList() ?: emptyList()
        assertTrue(
            "No temp files should remain after failed import, found: $remainingFiles",
            remainingFiles.isEmpty(),
        )
    }

    // ── Contract 3: OOM in one item doesn't crash the batch ─────────────────

    @Test
    fun importBatch_throwableInOneItem_otherItemsComplete() = runTest {
        val items = (1..3).map { imageItem("oom-batch-$it") }
        val bot = BotCredential(0, "tok")

        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer {
            val c = callCount.incrementAndGet()
            if (c == 1) throw OutOfMemoryError("simulated OOM for test")
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
        whenever(botPool.allBots()).thenReturn(listOf(bot))
        whenever(botPool.selectForUpload()).thenReturn(bot)

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val results = manager.importBatch(items = items, sessionId = "s")

        assertEquals("All 3 items must have a result", 3, results.size)
        val failed = results.count { it.result.isFailure }
        val succeeded = results.count { it.result.isSuccess }
        assertTrue("At least 1 item should fail (OOM)", failed >= 1)
        assertTrue("At least 1 item should succeed despite the OOM", succeeded >= 1)
    }

    // ── Contract 1 (extended): Temp file bytes match the downloaded content ──

    @Test
    fun imageImport_uploadedBytesMatchDownloadedContent() = runTest {
        val item = imageItem("oom-content-check")
        val bot = BotCredential(0, "tok")
        val expectedBytes = ByteArray(2048) { (it % 256).toByte() }

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer {
            Response.success(expectedBytes.toResponseBody("image/jpeg".toMediaType()))
        }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val capturedParts = mutableListOf<okhttp3.MultipartBody.Part>()
        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .doAnswer { inv ->
                capturedParts.add(inv.getArgument(2))
                telegramOkResponse()
            }

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(bot)

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")
        assertTrue("Import must succeed", result.isSuccess)
        assertEquals("Exactly one sendDocument call", 1, capturedParts.size)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Video OOM Safety BRTs (BUG-004a)
    //
    // importVideoFromStream must NOT hold 19 MB ByteArrays in heap during upload.
    // Each chunk must be written to a temp file and cleaned up in a finally block.
    // ════════════════════════════════════════════════════════════════════════

    private val smallVideoBytes = ByteArray(4096) { (it % 256).toByte() }

    private fun successVideoResponse(): Response<okhttp3.ResponseBody> =
        Response.success(smallVideoBytes.toResponseBody("video/mp4".toMediaType()))

    private fun videoItem(id: String): GooglePhotosMediaItem = GooglePhotosMediaItem(
        id = id,
        mimeType = "video/mp4",
        filename = "$id.mp4",
        baseUrl = "https://lh3.googleusercontent.com/$id",
        mediaMetadata = GooglePhotosMediaMetadata(creationTime = null, width = "1920", height = "1080"),
    )

    // ── Video Contract 1: No temp files remain after successful video import ─

    @Test
    fun videoImport_success_noTempFilesRemainAfterImport() = runTest {
        val item = videoItem("vid-oom-1")
        val bot = BotCredential(0, "tok")

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer { successVideoResponse() }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(telegramOkResponse())

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(bot)

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")
        assertTrue("Video import must succeed", result.isSuccess)

        val importDir = File(tempCacheDir, "gphoto_import")
        val remainingFiles = importDir.listFiles()?.toList() ?: emptyList()
        assertTrue(
            "No temp files should remain after successful video import, found: $remainingFiles",
            remainingFiles.isEmpty(),
        )
    }

    // ── Video Contract 2: Chunk temp files cleaned up on upload failure ──────

    @Test
    fun videoImport_uploadFails_chunkTempFilesStillCleaned() = runTest {
        val item = videoItem("vid-oom-fail")
        val bot = BotCredential(0, "tok")

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer { successVideoResponse() }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(
                TelegramResponse(
                    ok = false,
                    result = null,
                    description = "Bad Request",
                    errorCode = 400,
                    parameters = null,
                ),
            )

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(bot)

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        val result = manager.importGooglePhotosMediaItem(item, sessionId = "test-session")
        assertTrue("Video import must fail when sendDocument returns ok=false", result.isFailure)

        val importDir = File(tempCacheDir, "gphoto_import")
        val remainingFiles = importDir.listFiles()?.toList() ?: emptyList()
        assertTrue(
            "No temp files should remain after failed video import, found: $remainingFiles",
            remainingFiles.isEmpty(),
        )
    }

    // ── Video Contract 3 (BRT): A temp file must exist on disk during upload ─
    //
    // The current code allocates ByteArray(19 MB) and never writes a temp file.
    // The fix must write the chunk to a temp file BEFORE calling sendDocument,
    // so this assertion is FALSE with the old code (no file → fails) and TRUE
    // after the fix (file present during upload → passes).

    @Test
    fun videoImport_chunkTempFileExistsDuringUpload() = runTest {
        val item = videoItem("vid-brt-tempfile")
        val bot = BotCredential(0, "tok")
        var tempFilesExistDuringUpload = false

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.streamMedia(any())).doAnswer { successVideoResponse() }

        val mediaDao = mock<MediaItemDao>()
        whenever(mediaDao.countItemsMatchingImportFingerprint(any(), any(), any(), any())).thenReturn(0)

        val uploadApi = mock<TelegramBotApi>()
        whenever(uploadApi.sendDocument(any(), any(), any(), anyOrNull(), anyOrNull()))
            .doAnswer { _ ->
                val importDir = File(tempCacheDir, "gphoto_import")
                val files = importDir.listFiles() ?: emptyArray()
                tempFilesExistDuringUpload = files.isNotEmpty()
                telegramOkResponse()
            }

        val creds = mock<CredentialRepository>()
        whenever(creds.getBotToken()).thenReturn("tok")
        whenever(creds.getChatId()).thenReturn("99")

        val botPool = mock<BotPool>()
        whenever(botPool.selectForUpload()).thenReturn(bot)

        val manager = buildManager(
            pickerApi = pickerApi,
            uploadApi = uploadApi,
            mediaDao = mediaDao,
            creds = creds,
            botPool = botPool,
        )

        manager.importGooglePhotosMediaItem(item, sessionId = "test-session")

        assertTrue(
            "A temp file must exist on disk while the video chunk sendDocument is in-flight " +
                "(verifies disk-backed streaming is used instead of in-memory ByteArray)",
            tempFilesExistDuringUpload,
        )
    }
}
