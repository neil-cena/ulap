package com.ulap.ui.gallery

import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.remote.ParallelChunkDownloader
import com.ulap.data.remote.ParallelChunkDownloader.Companion.chunkFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages background prefetching of video chunks in a sliding window ahead of current playback.
 *
 * The window contains [windowSize] chunks starting at [setPrefetchOrigin]. When the playback
 * position advances to the next chunk, [advanceOrigin] shifts the window forward and cancels
 * downloads outside it.
 *
 * Thread-safety: public methods are guarded by [mutex]; OkHttp calls happen on IO thread.
 */
class ChunkPrefetchEngine(
    private val chunkDir: File,
    private val chunkMeta: List<ChunkMetadataEntity>,
    private val resolvedUrls: List<String?>,
    private val okHttpClient: OkHttpClient,
    private val windowSize: Int = 4,
    private val concurrency: Int = 3,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val downloading = ConcurrentHashMap<Int, Job>()
    private val completed = ConcurrentHashMap<Int, Boolean>()

    /** Start prefetching from [chunkIndex]. Cancels any in-flight downloads outside the new window. */
    fun setPrefetchOrigin(chunkIndex: Int) {
        scope.launch {
            mutex.withLock {
                val windowEnd = minOf(chunkIndex + windowSize, chunkMeta.size)
                // Cancel downloads outside the new window.
                val keysToCancel = downloading.keys.filter { it < chunkIndex || it >= windowEnd }
                keysToCancel.forEach { key ->
                    downloading[key]?.cancel()
                    downloading.remove(key)
                }
                // Start downloads for missing chunks within the window.
                for (i in chunkIndex until windowEnd) {
                    if (isChunkReady(i) || downloading.containsKey(i)) continue
                    if ((downloading.size) >= concurrency) break
                    startDownload(i)
                }
            }
        }
    }

    /** Advance the prefetch origin to [chunkIndex] and trigger window update. */
    fun advanceOrigin(chunkIndex: Int) = setPrefetchOrigin(chunkIndex)

    /** Returns true if the chunk file is fully downloaded and has correct size. */
    fun isChunkReady(chunkIndex: Int): Boolean {
        if (completed[chunkIndex] == true) return true
        val meta = chunkMeta.getOrNull(chunkIndex) ?: return false
        val file = ParallelChunkDownloader.chunkFile(chunkDir, chunkIndex)
        val ready = file.exists() && file.length() == meta.byteLength.toLong()
        if (ready) completed[chunkIndex] = true
        return ready
    }

    /**
     * Suspends until chunk [chunkIndex] is ready (file exists with correct size) or
     * there is no URL available for it. Polls with 50ms intervals.
     */
    suspend fun waitForChunk(chunkIndex: Int) {
        // Kick off download if not already running.
        mutex.withLock {
            if (!isChunkReady(chunkIndex) && !downloading.containsKey(chunkIndex)) {
                startDownload(chunkIndex)
            }
        }
        while (!isChunkReady(chunkIndex)) {
            if (resolvedUrls.getOrNull(chunkIndex) == null) break
            kotlinx.coroutines.delay(50)
        }
    }

    /** Cancels all in-flight downloads and closes the scope. */
    fun cancel() {
        scope.cancel()
    }

    private fun startDownload(chunkIndex: Int) {
        val url = resolvedUrls.getOrNull(chunkIndex) ?: return
        val meta = chunkMeta.getOrNull(chunkIndex) ?: return
        val targetFile = ParallelChunkDownloader.chunkFile(chunkDir, chunkIndex)

        val job = scope.launch {
            try {
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@launch
                    val body = response.body ?: return@launch
                    val tmpFile = File(targetFile.parent, targetFile.name + ".tmp")
                    body.byteStream().use { input ->
                        tmpFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (!tmpFile.renameTo(targetFile)) {
                        tmpFile.delete()
                        return@launch
                    }
                    completed[chunkIndex] = true
                }
                // After completing, advance the window.
                mutex.withLock {
                    downloading.remove(chunkIndex)
                    val nextToFill = (downloading.keys.maxOrNull() ?: chunkIndex) + 1
                    if (nextToFill < chunkMeta.size && !isChunkReady(nextToFill) && downloading.size < concurrency) {
                        startDownload(nextToFill)
                    }
                }
            } catch (_: Exception) {
                downloading.remove(chunkIndex)
            }
        }
        downloading[chunkIndex] = job
    }
}
