package com.ulap.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.ulap.data.auth.GoogleAuthManager
import com.ulap.data.googlephotos.BatchItemResult
import com.ulap.data.googlephotos.GooglePhotosImportItemStatus
import com.ulap.data.googlephotos.GooglePhotosImportManager
import com.ulap.data.googlephotos.GooglePhotosMediaItem
import com.ulap.data.googlephotos.GooglePhotosPickerApi
import com.ulap.data.googlephotos.PickedMediaFile
import com.ulap.data.googlephotos.PickedMediaItem
import com.ulap.data.googlephotos.PickedMediaItemsResponse
import com.ulap.data.googlephotos.toGooglePhotosMediaItem
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.remote.BackupIndexManager
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.debug.DebugLogBuffer
import com.ulap.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Bug Reproduction Test — GooglePhotosImportWorker real-time progress reporting.
 *
 * ## Defect
 *
 * The worker calls `importBatch(onItemComplete = { _, _ -> setProgress(imported, processed) })`.
 * The `imported` and `processed` counters are plain `var` variables updated only in the
 * post-batch `for` loop that runs AFTER `importBatch` returns. Every mid-batch `setProgress`
 * therefore reports 0/0, so the UI shows no progress until an entire page batch finishes.
 *
 * ## Contract encoded by this test
 *
 * When `importBatch.onItemComplete` fires after the FIRST item in a batch completes, the
 * `setProgress` call triggered by that callback must pass `processed >= 1`. The second
 * recorded progress update (index 1 = first mid-batch callback) is the observable witness.
 *
 * ## Why this test FAILS against current code
 *
 * `imported` and `processed` are both 0 when `onItemComplete` fires, so every mid-batch
 * `setProgress` emits `processed = 0`. The assertion `capturedProgressUpdates[1].processed >= 1`
 * therefore fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class GooglePhotosImportWorkerProgressBrt {

    @Test
    fun firstMidBatchProgressUpdate_mustShowAtLeastOneProcessedItem_notZero() = runBlocking {
        // ── Fake ProgressUpdater: records every setProgress call ──────────────────────────────
        val capturedProgress = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val fakeProgressUpdater = object : ProgressUpdater {
            override fun updateProgress(
                context: Context,
                id: UUID,
                data: androidx.work.Data,
            ): ListenableFuture<Void?> {
                val imp = data.getInt(GooglePhotosImportProgress.KEY_IMPORTED, -1)
                val proc = data.getInt(GooglePhotosImportProgress.KEY_PROCESSED, -1)
                capturedProgress.add(imp to proc)
                // Immediately-resolved ListenableFuture without Guava
                return object : ListenableFuture<Void?> {
                    override fun addListener(r: Runnable, e: Executor) = e.execute(r)
                    override fun isDone(): Boolean = true
                    override fun isCancelled(): Boolean = false
                    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
                    override fun get(): Void? = null
                    override fun get(timeout: Long, unit: TimeUnit): Void? = null
                }
            }
        }

        // ── importBatch mock: calls onItemComplete once per item, sequentially ───────────────
        val importManager = mock<GooglePhotosImportManager>()
        whenever(importManager.recommendedConcurrency()).thenReturn(3)
        whenever(importManager.importBatch(any(), any(), any(), anyOrNull(), any()))
            .doSuspendableAnswer { inv ->
                val batchItems = inv.getArgument<List<GooglePhotosMediaItem>>(0)
                @Suppress("UNCHECKED_CAST")
                val onItemComplete = inv.getArgument(4)
                    as (suspend (GooglePhotosMediaItem, kotlin.Result<GooglePhotosImportItemStatus>) -> Unit)
                batchItems.map { item ->
                    val result = kotlin.Result.success(GooglePhotosImportItemStatus.UPLOADED)
                    onItemComplete(item, result)
                    BatchItemResult(item, result)
                }
            }

        // ── Other mocks ────────────────────────────────────────────────────────────────────────
        val googleAuthManager = mock<GoogleAuthManager>()
        whenever(googleAuthManager.refreshToken(any(), any())).thenReturn(true)

        val userPreferencesRepository = mock<UserPreferencesRepository>()
        whenever(userPreferencesRepository.googlePhotosWebClientId).thenReturn(MutableStateFlow("test-cid"))
        whenever(userPreferencesRepository.googlePhotosClientSecret).thenReturn(MutableStateFlow("test-secret"))

        val pickerApi = mock<GooglePhotosPickerApi>()
        whenever(pickerApi.listMediaItems(any(), any(), anyOrNull())).thenReturn(
            PickedMediaItemsResponse(
                mediaItems = listOf(
                    fakePickedItem("item-1"),
                    fakePickedItem("item-2"),
                    fakePickedItem("item-3"),
                ),
                nextPageToken = null,
            ),
        )

        val mediaItemDao = mock<MediaItemDao>()
        val backupIndexManager = mock<BackupIndexManager>()
        val credentialRepository = mock<CredentialRepository>()
        val debugLog = mock<DebugLogBuffer>()

        // ── Build the worker ───────────────────────────────────────────────────────────────────
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inputData = workDataOf(
            GooglePhotosImportWorker.KEY_SESSION_ID to "test-session",
            GooglePhotosImportWorker.KEY_SELECTED_TOTAL to 3,
        )
        val worker = TestListenableWorkerBuilder<GooglePhotosImportWorker>(context, inputData)
            .setProgressUpdater(fakeProgressUpdater)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = GooglePhotosImportWorker(
                    context = appContext,
                    params = workerParameters,
                    pickerApi = pickerApi,
                    mediaItemDao = mediaItemDao,
                    importManager = importManager,
                    googleAuthManager = googleAuthManager,
                    backupIndexManager = backupIndexManager,
                    credentialRepository = credentialRepository,
                    userPreferencesRepository = userPreferencesRepository,
                    debugLog = debugLog,
                )
            })
            .build()

        // ── Act ────────────────────────────────────────────────────────────────────────────────
        worker.doWork()

        // ── Assert ─────────────────────────────────────────────────────────────────────────────
        // Expected sequence with the FIXED code:
        //   index 0 → (0, 0)   initial call at start of doWork()
        //   index 1 → (1, 1)   first onItemComplete callback  ← THE WITNESS
        //   index 2 → (2, 2)   second onItemComplete callback
        //   index 3 → (3, 3)   third onItemComplete callback
        //
        // Expected sequence with the BUGGY code (current):
        //   index 0 → (0, 0)   initial
        //   index 1 → (0, 0)   first onItemComplete  ← fails here
        //   index 2 → (0, 0)   second onItemComplete
        //   index 3 → (0, 0)   third onItemComplete
        //   index 4 → (3, 3)   post-batch for-loop setProgress

        assertTrue(
            "Expected at least 2 setProgress calls but got ${capturedProgress.size}: $capturedProgress",
            capturedProgress.size >= 2,
        )

        val firstMidBatch = capturedProgress[1]
        assertTrue(
            "First mid-batch setProgress must report processed >= 1, indicating the item " +
                "completion was tracked BEFORE setProgress was called. " +
                "Actual processed=${firstMidBatch.second}. All updates: $capturedProgress",
            firstMidBatch.second >= 1,
        )
    }

    private fun fakePickedItem(id: String) = PickedMediaItem(
        id = id,
        type = "PHOTO",
        mediaFile = PickedMediaFile(
            baseUrl = "https://photos.example.com/$id",
            mimeType = "image/jpeg",
            filename = "$id.jpg",
            mediaFileMetadata = null,
        ),
        createTime = null,
    )
}
