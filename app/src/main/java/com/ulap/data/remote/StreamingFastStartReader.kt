package com.ulap.data.remote

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * An [InputStream] that streams MP4 bytes in fast-start order (moov before mdat) without
 * writing any intermediate temp files to disk.
 *
 * For videos where moov is already before mdat, returns a plain stream without any processing.
 * For videos where moov is after mdat, reads the file segments via [FileChannel] seeks and
 * splices them in the correct order: [pre-mdat atoms] + [patched moov] + [mdat] + [post-mdat atoms].
 *
 * Peak memory usage = size of the moov atom only (typically <50MB even for very large videos),
 * rather than the 2x full-file disk usage of the copy-based approach.
 *
 * Returns null if the URI cannot be opened as a seekable file channel (e.g. non-file-backed URIs),
 * in which case the caller should fall back to a plain InputStream.
 */
object StreamingFastStartReader {

    /**
     * Opens a fast-start InputStream for [uri].
     * Returns null if the content is not an MP4, or if it cannot be opened as a seekable channel.
     * Falls back to a plain InputStream if moov is already before mdat (no reordering needed).
     */
    fun open(contentResolver: ContentResolver, uri: Uri): InputStream? {
        val pfd = try {
            contentResolver.openFileDescriptor(uri, "r") ?: return null
        } catch (_: Exception) {
            return null
        }

        return try {
            val fc = FileChannel.open(
                java.nio.file.Paths.get("/proc/self/fd/${pfd.fd}"),
                java.nio.file.StandardOpenOption.READ,
            )
            val fileSize = fc.size()
            if (fileSize < 8) {
                fc.close()
                pfd.close()
                return null
            }

            val atoms = parseAtoms(fc, fileSize) ?: run {
                fc.close()
                pfd.close()
                return null
            }

            val moov = atoms.find { it.type == Mp4FastStart.MOOV }
            val mdat = atoms.find { it.type == Mp4FastStart.MDAT }

            if (moov == null || mdat == null) {
                fc.close()
                pfd.close()
                return null
            }

            if (moov.offset < mdat.offset) {
                // Already fast-started; just return a plain sequential stream.
                fc.close()
                pfd.close()
                return contentResolver.openInputStream(uri)
            }

            // Read moov into memory and patch chunk offsets.
            if (moov.size > Int.MAX_VALUE) {
                // Unrealistically large moov -- fall through to caller's fallback.
                fc.close()
                pfd.close()
                return null
            }
            val moovData = ByteArray(moov.size.toInt())
            fc.position(moov.offset)
            val moovBuf = ByteBuffer.wrap(moovData)
            var totalRead = 0
            while (totalRead < moovData.size) {
                val r = fc.read(moovBuf)
                if (r == -1) break
                totalRead += r
            }
            if (totalRead != moovData.size) {
                fc.close()
                pfd.close()
                return null
            }

            val beforeMdat = atoms.filter { it.offset < mdat.offset }
            val afterMdat = atoms.filter { it.offset > mdat.offset && it.type != Mp4FastStart.MOOV }

            val newMdatOffset = beforeMdat.sumOf { it.size } + moov.size
            val delta = newMdatOffset - mdat.offset
            Mp4FastStart.patchMoovOffsets(moovData, delta)

            // Build ordered list of segments to stream:
            // 1. Atoms before mdat (in order)
            // 2. Patched moov (from memory)
            // 3. mdat
            // 4. Atoms after mdat (excluding the original moov)
            val segments: List<Segment> = buildList {
                for (atom in beforeMdat) {
                    add(Segment.Channel(fc, atom.offset, atom.size))
                }
                add(Segment.Memory(moovData))
                add(Segment.Channel(fc, mdat.offset, mdat.size))
                for (atom in afterMdat) {
                    add(Segment.Channel(fc, atom.offset, atom.size))
                }
            }

            FastStartInputStream(fc, pfd, segments)
        } catch (_: Exception) {
            try { pfd.close() } catch (_: Exception) { }
            null
        }
    }

    private fun parseAtoms(fc: FileChannel, fileSize: Long): List<Mp4FastStart.Atom>? {
        val atoms = mutableListOf<Mp4FastStart.Atom>()
        var pos = 0L
        val headerBuf = ByteBuffer.allocate(8)

        while (pos < fileSize - 7) {
            headerBuf.clear()
            fc.position(pos)
            var read = 0
            while (read < 8) {
                val r = fc.read(headerBuf)
                if (r == -1) return atoms.ifEmpty { null }
                read += r
            }
            headerBuf.flip()
            var size = headerBuf.int.toLong() and 0xFFFFFFFFL
            val type = headerBuf.int

            if (size == 1L) {
                val extBuf = ByteBuffer.allocate(8)
                fc.position(pos + 8)
                var extRead = 0
                while (extRead < 8) {
                    val r = fc.read(extBuf)
                    if (r == -1) return atoms.ifEmpty { null }
                    extRead += r
                }
                extBuf.flip()
                size = extBuf.long
            } else if (size == 0L) {
                size = fileSize - pos
            }

            if (size < 8 || pos + size > fileSize) break
            atoms.add(Mp4FastStart.Atom(type, pos, size))
            pos += size
        }
        return atoms.ifEmpty { null }
    }

    private sealed class Segment {
        abstract val length: Long
        abstract var consumed: Long

        class Memory(val data: ByteArray) : Segment() {
            override val length: Long get() = data.size.toLong()
            override var consumed: Long = 0L
        }

        class Channel(val fc: FileChannel, val fileOffset: Long, override val length: Long) : Segment() {
            override var consumed: Long = 0L
        }
    }

    private class FastStartInputStream(
        private val fc: FileChannel,
        private val pfd: android.os.ParcelFileDescriptor,
        private val segments: List<Segment>,
    ) : InputStream() {

        private var segIdx = 0
        private val readBuf = ByteBuffer.allocate(64 * 1024)
        private var closed = false

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n == -1) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream closed")
            if (len == 0) return 0

            while (segIdx < segments.size) {
                val seg = segments[segIdx]
                val remaining = seg.length - seg.consumed
                if (remaining <= 0) {
                    segIdx++
                    continue
                }
                val toRead = minOf(len.toLong(), remaining).toInt()
                val bytesRead = when (seg) {
                    is Segment.Memory -> {
                        val srcOff = seg.consumed.toInt()
                        System.arraycopy(seg.data, srcOff, b, off, toRead)
                        toRead
                    }
                    is Segment.Channel -> {
                        readBuf.clear()
                        readBuf.limit(toRead.coerceAtMost(readBuf.capacity()))
                        fc.position(seg.fileOffset + seg.consumed)
                        var totalRead = 0
                        while (totalRead < toRead) {
                            readBuf.limit(minOf(toRead - totalRead, readBuf.capacity()))
                            readBuf.clear()
                            readBuf.limit(minOf(toRead - totalRead, readBuf.capacity()))
                            val r = fc.read(readBuf)
                            if (r == -1) break
                            readBuf.flip()
                            readBuf.get(b, off + totalRead, r)
                            totalRead += r
                            fc.position(seg.fileOffset + seg.consumed + totalRead)
                        }
                        totalRead
                    }
                }
                if (bytesRead <= 0) return if (seg.consumed == 0L) -1 else 0
                seg.consumed += bytesRead
                return bytesRead
            }
            return -1
        }

        override fun close() {
            if (!closed) {
                closed = true
                try { fc.close() } catch (_: Exception) { }
                try { pfd.close() } catch (_: Exception) { }
            }
        }
    }
}
