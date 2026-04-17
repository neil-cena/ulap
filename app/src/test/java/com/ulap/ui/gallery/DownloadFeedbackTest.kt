package com.ulap.ui.gallery

import android.content.Context
import android.net.Uri
import com.ulap.R
import com.ulap.data.local.ThumbnailUrlCache
import com.ulap.data.remote.TelegramDownloader
import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import com.ulap.domain.usecase.DownloadCloudItemUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.GetMediaByTypeUseCase
import com.ulap.domain.usecase.MarkAsCloudOnlyUseCase
import com.ulap.domain.usecase.RemoveLocalMediaFileUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests that downloadFromGallery provides immediate visual feedback:
 *  1. The item ID is added to downloadingIds as soon as the download starts.
 *  2. The "Downloading..." snackbar fires before the download use case returns.
 *  3. The item ID is removed from downloadingIds after the download completes.
 *  4. A completion snackbar fires after the download finishes (success or error).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadFeedbackTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context = mock()
    private val getMediaByType: GetMediaByTypeUseCase = mock()
    private val downloader: TelegramDownloader = mock()
    private val thumbnailUrlCache: ThumbnailUrlCache = mock()
    private val getCredentials: GetCredentialsUseCase = mock()
    private val downloadCloudItem: DownloadCloudItemUseCase = mock()
    private val removeLocalMedia: RemoveLocalMediaFileUseCase = mock()
    private val markAsCloudOnly: MarkAsCloudOnlyUseCase = mock()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Stub context.getString to return a recognisable placeholder for any resource id
        whenever(context.getString(any<Int>())).thenAnswer { "str:${it.arguments[0]}" }
        whenever(context.getString(any<Int>(), any())).thenAnswer { "str:${it.arguments[0]}" }
        // getMediaByType must return a flow so the ViewModel's stateIn doesn't crash
        whenever(getMediaByType(any())).thenReturn(flowOf(emptyList()))
        whenever(thumbnailUrlCache.getAll()).thenReturn(emptyMap())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun buildVm() = MediaTypeViewModel(
        context = context,
        getMediaByType = getMediaByType,
        downloader = downloader,
        thumbnailUrlCache = thumbnailUrlCache,
        getCredentials = getCredentials,
        downloadCloudItem = downloadCloudItem,
        removeLocalMediaFile = removeLocalMedia,
        markAsCloudOnly = markAsCloudOnly,
    )

    private fun cloudItem(id: String = "item1") = MediaItem(
        id = id,
        path = "",
        contentUri = "",
        fileName = "$id.jpg",
        mimeType = "image/jpeg",
        size = 1024L,
        dateModified = 0L,
        dateTaken = 0L,
        bucketName = "Camera",
        mediaType = MediaType.IMAGE,
        durationMs = null,
        backupStatus = BackupStatus.CLOUD_ONLY,
        telegramFileId = "tg_file_id_$id",
        streamUrl = null,
        errorMessage = null,
    )

    // ── 1. downloadingIds contains the item ID while the download is in flight ─

    @Test
    fun downloadFromGallery_addsItemIdToDownloadingIds_whileInFlight() = runTest {
        val deferred = CompletableDeferred<Result<Uri>>()
        whenever(downloadCloudItem(any())).doSuspendableAnswer { deferred.await() }

        val vm = buildVm()
        val item = cloudItem("abc")

        vm.downloadFromGallery(item)
        // Advance until the coroutine suspends inside the download use case
        advanceUntilIdle()

        assertTrue(
            "downloadingIds must contain the item ID while download is in flight",
            "abc" in vm.downloadingIds.value,
        )

        // Unblock so the VM can clean up (avoids scope leaks)
        deferred.complete(Result.success(Uri.EMPTY))
        advanceUntilIdle()
    }

    // ── 2. downloadingIds is cleared after successful download ────────────────

    @Test
    fun downloadFromGallery_removesItemIdFromDownloadingIds_afterSuccess() = runTest {
        whenever(downloadCloudItem(any())).thenReturn(Result.success(Uri.EMPTY))

        val vm = buildVm()
        val item = cloudItem("abc")

        vm.downloadFromGallery(item)
        advanceUntilIdle()

        assertFalse(
            "downloadingIds must be empty after download completes",
            "abc" in vm.downloadingIds.value,
        )
    }

    // ── 3. downloadingIds is cleared even when the download fails ─────────────

    @Test
    fun downloadFromGallery_removesItemIdFromDownloadingIds_afterFailure() = runTest {
        whenever(downloadCloudItem(any())).thenReturn(Result.failure(RuntimeException("network down")))

        val vm = buildVm()
        val item = cloudItem("abc")

        vm.downloadFromGallery(item)
        advanceUntilIdle()

        assertFalse(
            "downloadingIds must be cleared even after a failed download",
            "abc" in vm.downloadingIds.value,
        )
    }

    // ── 4. "Downloading..." snackbar fires before the download use case returns

    @Test
    fun downloadFromGallery_emitsStartedSnackbar_beforeDownloadCompletes() = runTest {
        val deferred = CompletableDeferred<Result<Uri>>()
        whenever(downloadCloudItem(any())).doSuspendableAnswer { deferred.await() }

        val vm = buildVm()
        val snackbarMessages = mutableListOf<String>()
        val collector = launch { vm.snackbarMessages.collect { snackbarMessages.add(it) } }

        vm.downloadFromGallery(cloudItem("abc"))
        advanceUntilIdle() // runs until coroutine suspends inside use case

        assertEquals(
            "Exactly one snackbar ('Downloading...') must be emitted before the download returns",
            1,
            snackbarMessages.size,
        )
        assertTrue(
            "The first snackbar must reference gallery_download_started (got: ${snackbarMessages[0]})",
            snackbarMessages[0].contains(R.string.gallery_download_started.toString()),
        )

        deferred.complete(Result.success(Uri.EMPTY))
        advanceUntilIdle()
        collector.cancel()

        assertEquals(
            "Two snackbars total: start + completion",
            2,
            snackbarMessages.size,
        )
    }

    // ── 5. Concurrent downloads track both IDs independently ─────────────────

    @Test
    fun downloadFromGallery_tracksConcurrentDownloadsIndependently() = runTest {
        val deferred1 = CompletableDeferred<Result<Uri>>()
        val deferred2 = CompletableDeferred<Result<Uri>>()
        var callCount = 0
        whenever(downloadCloudItem(any())).doSuspendableAnswer {
            if (callCount++ == 0) deferred1.await() else deferred2.await()
        }

        val vm = buildVm()

        vm.downloadFromGallery(cloudItem("item1"))
        vm.downloadFromGallery(cloudItem("item2"))
        advanceUntilIdle()

        assertTrue("item1 must be tracked", "item1" in vm.downloadingIds.value)
        assertTrue("item2 must be tracked", "item2" in vm.downloadingIds.value)

        deferred1.complete(Result.success(Uri.EMPTY))
        advanceUntilIdle()
        assertFalse("item1 must be removed after its download finishes", "item1" in vm.downloadingIds.value)
        assertTrue("item2 must still be tracked", "item2" in vm.downloadingIds.value)

        deferred2.complete(Result.success(Uri.EMPTY))
        advanceUntilIdle()
        assertTrue("downloadingIds must be empty when all downloads finish", vm.downloadingIds.value.isEmpty())
    }
}
