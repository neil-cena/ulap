package com.ulap.ui.gallery

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
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
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else C.LENGTH_UNSET.toLong()
        prefetchEngine.setPrefetchOrigin(currentChunkIndex)
        openCurrentChunk()
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (currentChunkIndex >= chunkMeta.size) return C.RESULT_END_OF_INPUT

        // Wait for current chunk file to be ready.
        val chunk = chunkMeta[currentChunkIndex]
        runBlocking { prefetchEngine.waitForChunk(currentChunkIndex) }

        val raf = currentRaf ?: run {
            openCurrentChunk()
            currentRaf ?: return C.RESULT_END_OF_INPUT
        }

        val chunkRemaining = chunk.byteLength - positionInChunk
        if (chunkRemaining <= 0) {
            // Advance to next chunk.
            advanceToNextChunk()
            return read(buffer, offset, length)
        }

        val toRead = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), bytesRemaining, chunkRemaining).toInt()
        } else {
            minOf(length.toLong(), chunkRemaining).toInt()
        }

        raf.seek(positionInChunk)
        val bytesRead = raf.read(buffer, offset, toRead)
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        positionInChunk += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)

        // If we've exhausted this chunk, advance to next.
        if (positionInChunk >= chunk.byteLength) {
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
