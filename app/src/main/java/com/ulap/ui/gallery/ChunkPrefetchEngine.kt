package com.ulap.ui.gallery

import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.remote.ParallelChunkDownloader.Companion.chunkFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ChunkPrefetchEngine(
    private val chunkDir: File,
    private val chunkMeta: List<ChunkMetadataEntity>,
    private val urlResolver: suspend (Int) -> String,
    private val okHttpClient: OkHttpClient,
    private val logCallback: (String) -> Unit = {},
    private val windowSize: Int = 4,
    private val concurrency: Int = 3,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicInteger(0)
    private val downloading = ConcurrentHashMap<Int, Job>()
    private val completed = ConcurrentHashMap<Int, Boolean>()
    private val failedChunks = ConcurrentHashMap<Int, Throwable>()

    // Build a CDN-specific client that does not follow redirects.
    // Fall back to the original client if the mock/builder throws (e.g. in unit tests).
    private val cdnHttpClient: OkHttpClient = try {
        okHttpClient.newBuilder().followRedirects(false).build()
    } catch (_: Exception) {
        okHttpClient
    }

    fun setPrefetchOrigin(origin: Int) {
        val gen = generation.incrementAndGet()
        completed.clear()
        failedChunks.clear()
        downloading.values.forEach { it.cancel() }
        downloading.clear()

        scope.launch {
            val windowEnd = minOf(origin + windowSize, chunkMeta.size)
            for (i in origin until windowEnd) {
                startDownload(i, gen)
            }
        }
    }

    fun advanceOrigin(chunkIndex: Int) = setPrefetchOrigin(chunkIndex)

    fun isChunkReady(index: Int): Boolean {
        if (completed[index] == true) return true
        val file = chunkFile(chunkDir, index)
        return file.exists() && file.length() > 0
    }

    suspend fun waitForChunk(chunkIndex: Int) {
        try {
            if (failedChunks.containsKey(chunkIndex)) {
                throw IOException(failedChunks[chunkIndex]!!.message ?: "Chunk $chunkIndex failed")
            }
            if (isChunkReady(chunkIndex)) return

            val gen = generation.get()
            mutex.withLock {
                if (!isChunkReady(chunkIndex) &&
                    !downloading.containsKey(chunkIndex) &&
                    !failedChunks.containsKey(chunkIndex)
                ) {
                    val job = scope.launch { doDownload(chunkIndex, gen) }
                    downloading[chunkIndex] = job
                }
            }

            var iterations = 0
            while (iterations < 200) {
                delay(50)
                iterations++
                if (isChunkReady(chunkIndex)) return
                if (failedChunks.containsKey(chunkIndex)) break
            }

            if (isChunkReady(chunkIndex)) return
            if (failedChunks.containsKey(chunkIndex)) {
                throw IOException(failedChunks[chunkIndex]!!.message ?: "Chunk $chunkIndex failed")
            }
        } catch (e: CancellationException) {
            throw IOException("chunk $chunkIndex cancelled")
        }
    }

    fun release() {
        scope.cancel()
    }

    fun isValidCdnUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = uri.host ?: return false
            uri.scheme == "https" && (
                host == "telegram.org" ||
                    host.endsWith(".telegram.org") ||
                    host == "cdn-telegram.org" ||
                    host.endsWith(".cdn-telegram.org") ||
                    host == "telegram-cdn.net" ||
                    host.endsWith(".telegram-cdn.net")
                )
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveUrlWithRetry(index: Int, gen: Int): String {
        var delayMs = 50L
        var lastException: IOException? = null
        for (attempt in 0 until 3) {
            if (generation.get() != gen) throw IOException("Stale generation for chunk $index")
            try {
                return urlResolver(index)
            } catch (e: IOException) {
                lastException = e
                if (attempt < 2) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        throw lastException ?: IOException("Failed to resolve URL for chunk $index")
    }

    private suspend fun doDownload(index: Int, gen: Int) {
        try {
            val url = resolveUrlWithRetry(index, gen)
            if (!isValidCdnUrl(url)) {
                failedChunks[index] = IOException("Invalid CDN URL: $url")
                return
            }
            val targetFile = chunkFile(chunkDir, index)
            chunkDir.mkdirs()
            val request = Request.Builder().url(url).build()
            cdnHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    failedChunks[index] = IOException("HTTP ${response.code} for chunk $index")
                    return@use
                }
                val body = response.body
                if (body == null) {
                    failedChunks[index] = IOException("Null response body for chunk $index")
                    return@use
                }
                val tmpFile = File(targetFile.parent, targetFile.name + ".tmp")
                body.byteStream().use { input ->
                    tmpFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (generation.get() != gen) {
                    tmpFile.delete()
                    return@use
                }
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.delete()
                    failedChunks[index] = IOException("Failed to rename tmp file for chunk $index")
                    return@use
                }
                completed[index] = true
            }
        } catch (e: IOException) {
            if (!failedChunks.containsKey(index)) {
                failedChunks[index] = e
            }
        } finally {
            downloading.remove(index)
        }
    }

    private suspend fun startDownload(index: Int, gen: Int) {
        mutex.withLock {
            if (downloading.containsKey(index) || isChunkReady(index) || failedChunks.containsKey(index)) return
            val job = scope.launch { doDownload(index, gen) }
            downloading[index] = job
        }
    }
}
