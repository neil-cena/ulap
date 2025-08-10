package com.ulap.data.remote

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.InputStream

class ProgressRequestBody(
    private val delegate: RequestBody,
    private val totalBytes: Long,
    private val onProgress: (uploaded: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = totalBytes

    override fun writeTo(sink: BufferedSink) {
        val buffer = Buffer()
        delegate.writeTo(buffer)
        var uploaded = 0L
        val buf = ByteArray(8 * 1024)
        buffer.inputStream().use { stream ->
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                sink.write(buf, 0, read)
                uploaded += read
                onProgress(uploaded, totalBytes)
            }
        }
    }
}

/**
 * Streams from [inputStream] to [sink] without loading the full body into memory.
 * Use for large file uploads.
 */
class StreamProgressRequestBody(
    private val inputStream: InputStream,
    private val contentLength: Long,
    private val contentType: MediaType,
    private val onProgress: (uploaded: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        var uploaded = 0L
        val buf = ByteArray(8 * 1024)
        var read: Int
        while (inputStream.read(buf).also { read = it } != -1) {
            sink.write(buf, 0, read)
            uploaded += read
            onProgress(uploaded, contentLength)
        }
    }
}
