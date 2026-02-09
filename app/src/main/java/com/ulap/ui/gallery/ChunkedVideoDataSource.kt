package com.ulap.ui.gallery

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.RandomAccessFile

/**
 * ExoPlayer DataSource that reads from a file being progressively written to disk.
 * A background coroutine appends chunk data to [file]; this source reads what's
 * available and waits (polls) when it catches up to the write position.
 *
 * [bytesWritten] must be updated by the writer whenever new bytes are flushed.
 * [downloadComplete] is set to true when the entire file has been written.
 */
class ChunkedVideoDataSource(
    private val file: File,
    private val bytesWritten: () -> Long,
    private val downloadComplete: () -> Boolean,
) : BaseDataSource(/* isNetwork = */ false) {

    private var raf: RandomAccessFile? = null
    private var readPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        raf = RandomAccessFile(file, "r")
        readPosition = dataSpec.position
        raf!!.seek(readPosition)

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            C.LENGTH_UNSET.toLong()
        }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val available = waitForData()
        if (available <= 0 && downloadComplete()) return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), bytesRemaining, available).toInt()
        } else {
            minOf(length.toLong(), available).toInt()
        }
        if (toRead <= 0) return C.RESULT_END_OF_INPUT

        raf!!.seek(readPosition)
        val bytesRead = raf!!.read(buffer, offset, toRead)
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        readPosition += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri = Uri.fromFile(file)

    override fun close() {
        try {
            raf?.close()
        } finally {
            raf = null
            transferEnded()
        }
    }

    /**
     * Waits until there's data ahead of [readPosition] or download finishes.
     * Returns how many bytes are available to read right now.
     */
    private fun waitForData(): Long {
        while (true) {
            val written = bytesWritten()
            val available = written - readPosition
            if (available > 0) return available
            if (downloadComplete()) return 0
            Thread.sleep(100)
        }
    }

    class Factory(
        private val file: File,
        private val bytesWritten: () -> Long,
        private val downloadComplete: () -> Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedVideoDataSource(file, bytesWritten, downloadComplete)
    }
}
