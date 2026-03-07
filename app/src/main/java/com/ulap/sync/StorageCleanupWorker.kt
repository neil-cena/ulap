package com.ulap.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ulap.data.local.ThumbnailUrlCache
import com.ulap.ui.gallery.STREAM_CACHE_MAX_BYTES
import com.ulap.ui.gallery.STREAM_CACHE_TTL_MS
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private const val STREAM_STALE_INCOMPLETE_MS = 60L * 60 * 1000 // 1 hour — abandoned in-progress downloads

@HiltWorker
class StorageCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val thumbnailUrlCache: ThumbnailUrlCache,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        withContext(Dispatchers.IO) {
            evictStreamCache()
        }
        thumbnailUrlCache.evictExpired()
        return Result.success()
    }

    private fun evictStreamCache() {
        val cacheDir = applicationContext.cacheDir
        val streamFiles = cacheDir.listFiles { f ->
            f.name.startsWith("ulap_stream_") && f.name.endsWith(".mp4")
        } ?: return

        val now = System.currentTimeMillis()

        // TTL eviction: remove completed files older than the TTL threshold.
        // Also remove incomplete (no .done marker) files abandoned for more than 1 hour.
        for (f in streamFiles) {
            val markerName = f.name.removeSuffix(".mp4") + ".done"
            val marker = File(cacheDir, markerName)
            if (marker.exists()) {
                if ((now - f.lastModified()) > STREAM_CACHE_TTL_MS) {
                    f.delete()
                    marker.delete()
                }
            } else {
                if ((now - f.lastModified()) > STREAM_STALE_INCOMPLETE_MS) {
                    f.delete()
                }
            }
        }

        // Size-cap eviction: only completed files count toward the cap.
        // Enumerate again after TTL pass to get the current state.
        val remaining = cacheDir.listFiles { f ->
            f.name.startsWith("ulap_stream_") && f.name.endsWith(".mp4")
        } ?: return

        val completed = remaining.filter { f ->
            val markerName = f.name.removeSuffix(".mp4") + ".done"
            File(cacheDir, markerName).exists()
        }

        var totalBytes = completed.sumOf { it.length() }
        if (totalBytes <= STREAM_CACHE_MAX_BYTES) return

        completed.sortedBy { it.lastModified() }.forEach { f ->
            if (totalBytes <= STREAM_CACHE_MAX_BYTES) return@forEach
            val markerName = f.name.removeSuffix(".mp4") + ".done"
            val marker = File(cacheDir, markerName)
            val fileSize = f.length()
            if (f.delete()) {
                totalBytes -= fileSize
                marker.delete()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "ulap_storage_cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StorageCleanupWorker>(24, TimeUnit.HOURS)
                .build()
            // UPDATE replaces the existing request so future interval/constraint changes take effect.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
