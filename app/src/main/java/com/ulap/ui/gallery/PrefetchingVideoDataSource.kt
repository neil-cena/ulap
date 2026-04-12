package com.ulap.ui.gallery

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.remote.ParallelChunkDownloader
import com.ulap.data.remote.ParallelChunkDownloader.Companion.chunkFile
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * ExoPlayer [DataSource] that reads from individually prefetched chunk files.
 *
 * Each chunk is a separate file under [chunkDir]: `chunk_0.dat`, `chunk_1.dat`, etc.
 * The [ChunkPrefetchEngine] downloads upcoming chunks in a sliding window.
 * Supports byte-range seeks by binary-searching [chunkMeta] for the target chunk.
 */
class PrefetchingVideoDataSource(
    private val chunkDir: File,
    private val chunkMeta: List<ChunkMetadataEntity>,
    private val prefetchEngine: ChunkPrefetchEngine,
) : BaseDataSource(/* isNetwork = */ false) {

    private val totalSize = chunkMeta.sumOf { it.byteLength.toLong() }
    private var currentChunkIndex = 0
    private var currentRaf: RandomAccessFile? = null
    private var positionInChunk = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val globalOffset = dataSpec.position
        currentChunkIndex = findChunkIndexForOffset(globalOffset)
        val chunk = chunkMeta.getOrNull(currentChunkIndex)
        positionInChunk = if (chunk != null) globalOffset - chunk.byteOffset else 0L

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalSize - globalOffset
        }
        Log.d("UlapChunkPlay", "DataSource.open pos=$globalOffset totalSize=$totalSize bytesRemaining=$bytesRemaining chunkIdx=$currentChunkIndex posInChunk=$positionInChunk totalChunks=${chunkMeta.size}")
        // #region agent log
        Log.w("DBG_5f6b53", "[DS.open] ENTER pos=$globalOffset chunkIdx=$currentChunkIndex totalChunks=${chunkMeta.size} | thread=${Thread.currentThread().name}")
        // #endregion

        // #region agent log
        Log.w("DBG_5f6b53", "[DS.open] CALLING_SET_PREFETCH_ORIGIN chunkIdx=$currentChunkIndex | thread=${Thread.currentThread().name}")
        // #endregion
        prefetchEngine.setPrefetchOrigin(currentChunkIndex)
        try {
            // #region agent log
            Log.w("DBG_5f6b53", "[DS.open] CALLING_WAIT_FOR_CHUNK chunkIdx=$currentChunkIndex | thread=${Thread.currentThread().name}")
            // #endregion
            runBlocking { prefetchEngine.waitForChunk(currentChunkIndex) }
            // #region agent log
            Log.w("DBG_5f6b53", "[DS.open] WAIT_FOR_CHUNK_SUCCESS chunkIdx=$currentChunkIndex | thread=${Thread.currentThread().name}")
            // #endregion
        } catch (e: IOException) {
            // #region agent log
            Log.w("DBG_5f6b53", "[DS.open] WAIT_FOR_CHUNK_FAILED chunkIdx=$currentChunkIndex error=${e.message} | thread=${Thread.currentThread().name}")
            // #endregion
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        openCurrentChunk()
        if (currentRaf == null) {
            Log.w("UlapChunkPlay", "DataSource.open: chunk file STILL not found after wait for idx=$currentChunkIndex, file=${ParallelChunkDownloader.chunkFile(chunkDir, currentChunkIndex)}")
            // #region agent log
            Log.w("DBG_5f6b53", "[DS.open] CHUNK_FILE_NOT_FOUND idx=$currentChunkIndex file=${ParallelChunkDownloader.chunkFile(chunkDir, currentChunkIndex)} exists=${ParallelChunkDownloader.chunkFile(chunkDir, currentChunkIndex).exists()} | thread=${Thread.currentThread().name}")
            // #endregion
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (currentChunkIndex >= chunkMeta.size) return C.RESULT_END_OF_INPUT

        val chunk = chunkMeta[currentChunkIndex]
        try {
            runBlocking { prefetchEngine.waitForChunk(currentChunkIndex) }
        } catch (e: IOException) {
            // #region agent log
            Log.w("DBG_5f6b53", "[DS.read] WAIT_FOR_CHUNK_FAILED chunkIdx=$currentChunkIndex error=${e.message} | thread=${Thread.currentThread().name}")
            // #endregion
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }

        val raf = currentRaf ?: run {
            openCurrentChunk()
            currentRaf ?: run {
                advanceToNextChunk()
                return if (currentChunkIndex < chunkMeta.size) read(buffer, offset, length)
                else C.RESULT_END_OF_INPUT
            }
        }

        val actualFileLen = raf.length()
        val chunkRemaining = actualFileLen - positionInChunk
        if (chunkRemaining <= 0) {
            advanceToNextChunk()
            return if (currentChunkIndex < chunkMeta.size) read(buffer, offset, length)
            else C.RESULT_END_OF_INPUT
        }

        val toRead = if (bytesRemaining > 0) {
            minOf(length.toLong(), bytesRemaining, chunkRemaining).toInt()
        } else {
            minOf(length.toLong(), chunkRemaining).toInt()
        }

        raf.seek(positionInChunk)
        val bytesRead = raf.read(buffer, offset, toRead)
        if (bytesRead == -1) {
            advanceToNextChunk()
            return if (currentChunkIndex < chunkMeta.size) read(buffer, offset, length)
            else C.RESULT_END_OF_INPUT
        }

        positionInChunk += bytesRead
        if (bytesRemaining > 0) bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)

        if (positionInChunk >= actualFileLen) {
            advanceToNextChunk()
        }

        return bytesRead
    }

    override fun getUri(): Uri = Uri.fromFile(chunkDir)

    override fun close() {
        try { currentRaf?.close() } catch (_: Exception) { }
        currentRaf = null
        transferEnded()
    }

    private fun openCurrentChunk() {
        try { currentRaf?.close() } catch (_: Exception) { }
        currentRaf = null
        val file = ParallelChunkDownloader.chunkFile(chunkDir, currentChunkIndex)
        if (file.exists()) {
            currentRaf = RandomAccessFile(file, "r")
        }
    }

    private fun advanceToNextChunk() {
        try { currentRaf?.close() } catch (_: Exception) { }
        currentRaf = null
        currentChunkIndex++
        positionInChunk = 0L
        if (currentChunkIndex < chunkMeta.size) {
            prefetchEngine.advanceOrigin(currentChunkIndex)
            openCurrentChunk()
        }
    }

    /** Binary search for the chunk containing [byteOffset]. Returns 0 if not found. */
    private fun findChunkIndexForOffset(byteOffset: Long): Int {
        if (chunkMeta.isEmpty()) return 0
        var lo = 0
        var hi = chunkMeta.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chunkMeta[mid].byteOffset <= byteOffset) lo = mid else hi = mid - 1
        }
        return lo
    }

    class Factory(
        private val chunkDir: File,
        private val chunkMeta: List<ChunkMetadataEntity>,
        private val prefetchEngine: ChunkPrefetchEngine,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            PrefetchingVideoDataSource(chunkDir, chunkMeta, prefetchEngine)
    }
}
