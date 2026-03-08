package com.ulap.data.remote

import com.ulap.data.local.dao.ChunkMetadataDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads large chunked files (stored in the chunk_metadata table) with parallel CDN fetches
 * and batched Telegram `getFile` URL resolution.
 *
 * Two download modes:
 * - [downloadToStream]: reassembles all chunks in order into a single [OutputStream] (for restore).
 * - [downloadToDirectory]: downloads each chunk into individual files in [chunkDir] (for progressive playback).
 */
@Singleton
class ParallelChunkDownloader @Inject constructor(
    private val downloader: TelegramDownloader,
    private val chunkMetadataDao: ChunkMetadataDao,
    private val okHttpClient: OkHttpClient,
) {
    private val downloadConcurrency = 4
    private val resolveBatchSize = 20

    /**
     * Downloads all chunks for [mediaItemId] and writes them in order to [outputStream].
     * Progress reports total bytes downloaded (with total = -1 if unknown).
     */
    suspend fun downloadToStream(
        token: String,
        mediaItemId: String,
        outputStream: OutputStream,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val chunks = chunkMetadataDao.getChunksForMedia(mediaItemId)
        if (chunks.isEmpty()) return@withContext DownloadResult.Error(Exception("No chunk metadata for $mediaItemId"))

        val fileIds = chunks.map { it.telegramFileId }
        val totalBytes = chunks.sumOf { it.byteLength.toLong() }

        val urls = downloader.resolveStreamUrlsBatched(
            token = token,
            fileIds = fileIds,
            batchSize = resolveBatchSize,
        )

        // If any URL resolution failed, fail the whole download.
        if (urls.any { it == null }) {
            return@withContext DownloadResult.Error(Exception("Failed to resolve all chunk URLs for $mediaItemId"))
        }
        val resolvedUrls = urls.filterNotNull()

        // Download up to downloadConcurrency chunks in parallel.
        val downloadedChunks = arrayOfNulls<ByteArray>(chunks.size)
        var downloadError: Exception? = null

        try {
            coroutineScope {
                val workChannel = Channel<Pair<Int, String>>(Channel.UNLIMITED)
                for (i in resolvedUrls.indices) workChannel.send(Pair(i, resolvedUrls[i]))
                workChannel.close()

                val workers = (1..downloadConcurrency).map {
                    launch {
                        for ((idx, url) in workChannel) {
                            if (downloadError != null) break
                            try {
                                val bytes = downloadUrlToBytes(url)
                                downloadedChunks[idx] = bytes
                            } catch (e: Exception) {
                                downloadError = e
                            }
                        }
                    }
                }
                workers.forEach { it.join() }
            }
        } catch (e: Exception) {
            return@withContext DownloadResult.Error(e)
        }

        downloadError?.let { return@withContext DownloadResult.Error(it) }

        // Write all chunks in order to outputStream.
        var written = 0L
        for (i in downloadedChunks.indices) {
            val bytes = downloadedChunks[i]
                ?: return@withContext DownloadResult.Error(Exception("Chunk $i missing after download"))
            outputStream.write(bytes)
            written += bytes.size
            onProgress(written, totalBytes)
        }
        DownloadResult.Success
    }

    /**
     * Downloads chunks for [mediaItemId] into individual files under [chunkDir].
     * Already-complete chunks (correct file size) are skipped.
     * Used for progressive playback prefetching.
     */
    suspend fun downloadToDirectory(
        token: String,
        mediaItemId: String,
        chunkDir: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val chunks = chunkMetadataDao.getChunksForMedia(mediaItemId)
        if (chunks.isEmpty()) return@withContext DownloadResult.Error(Exception("No chunk metadata for $mediaItemId"))

        chunkDir.mkdirs()

        // Identify chunks that still need to be downloaded.
        val pending = chunks.filter { chunk ->
            val file = chunkFile(chunkDir, chunk.chunkIndex)
            !file.exists() || file.length() != chunk.byteLength.toLong()
        }

        if (pending.isEmpty()) return@withContext DownloadResult.Success

        val totalBytes = chunks.sumOf { it.byteLength.toLong() }
        val pendingFileIds = pending.map { it.telegramFileId }
        val urls = downloader.resolveStreamUrlsBatched(
            token = token,
            fileIds = pendingFileIds,
            batchSize = resolveBatchSize,
        )

        var downloadError: Exception? = null
        var downloaded = chunks.sumOf { chunk ->
            val f = chunkFile(chunkDir, chunk.chunkIndex)
            if (f.exists() && f.length() == chunk.byteLength.toLong()) f.length() else 0L
        }

        try {
            coroutineScope {
                val workChannel = Channel<Pair<Int, String?>>(Channel.UNLIMITED)
                for (i in pending.indices) workChannel.send(Pair(i, urls[i]))
                workChannel.close()

                val workers = (1..downloadConcurrency).map {
                    launch {
                        for ((i, url) in workChannel) {
                            if (downloadError != null || url == null) {
                                if (url == null) downloadError = Exception("Could not resolve URL for chunk ${pending[i].chunkIndex}")
                                continue
                            }
                            try {
                                val targetFile = chunkFile(chunkDir, pending[i].chunkIndex)
                                val bytesCopied = downloadUrlToFile(url, targetFile)
                                synchronized(this@ParallelChunkDownloader) {
                                    downloaded += bytesCopied
                                    onProgress(downloaded, totalBytes)
                                }
                            } catch (e: Exception) {
                                downloadError = e
                            }
                        }
                    }
                }
                workers.forEach { it.join() }
            }
        } catch (e: Exception) {
            return@withContext DownloadResult.Error(e)
        }

        downloadError?.let { return@withContext DownloadResult.Error(it) }
        DownloadResult.Success
    }

    private fun downloadUrlToBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            val body = response.body ?: throw Exception("Empty response body")
            return body.byteStream().readBytes()
        }
    }

    private fun downloadUrlToFile(url: String, targetFile: File): Long {
        val request = Request.Builder().url(url).build()
        val tmpFile = File(targetFile.parent, targetFile.name + ".tmp")
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            val body = response.body ?: throw Exception("Empty response body")
            val bytesCopied = body.byteStream().use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.delete()
                throw Exception("Failed to rename tmp file for $url")
            }
            return bytesCopied
        }
    }

    companion object {
        /** Returns the chunk file for a given index within the chunk directory. */
        fun chunkFile(chunkDir: File, chunkIndex: Int): File =
            File(chunkDir, "chunk_${chunkIndex}.dat")

        /** Returns the chunk directory for a given media item under the cache dir. */
        fun chunkDirFor(cacheDir: File, mediaItemId: String): File =
            File(cacheDir, "ulap_chunks_${mediaItemId.replace('/', '_').replace('\\', '_')}")
    }
}
