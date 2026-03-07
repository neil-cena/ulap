package com.ulap.data.remote

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Rewrites an MP4 so the `moov` atom appears before `mdat` ("fast-start").
 * This enables progressive playback: ExoPlayer can parse codec info from the
 * first bytes and begin rendering frames without needing the entire file.
 *
 * If `moov` is already before `mdat`, copies the file as-is.
 * Uses only standard Java I/O -- no external libraries.
 */
object Mp4FastStart {

    internal const val MOOV = 0x6D6F6F76
    internal const val MDAT = 0x6D646174

    /** Top-level MP4 box descriptor. Internal so StreamingFastStartReader can share the parsing logic. */
    internal data class Atom(val type: Int, val offset: Long, val size: Long)

    fun fastStart(input: File, output: File): Boolean {
        if (!input.exists() || input.length() < 8) return false

        RandomAccessFile(input, "r").use { raf ->
            val atoms = parseTopLevelAtoms(raf, input.length()) ?: return false

            val moov = atoms.find { it.type == MOOV } ?: return false
            val mdat = atoms.find { it.type == MDAT } ?: return false

            if (moov.offset < mdat.offset) {
                input.copyTo(output, overwrite = true)
                return true
            }

            val moovData = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(moovData)

            val beforeMdat = atoms.filter { it.offset < mdat.offset }
            val afterMdat = atoms.filter { it.offset > mdat.offset && it.type != MOOV }

            val newMdatOffset = beforeMdat.sumOf { it.size } + moov.size
            val oldMdatOffset = mdat.offset
            val delta = newMdatOffset - oldMdatOffset

            patchMoovOffsets(moovData, delta)

            RandomAccessFile(output, "rw").use { out ->
                for (atom in beforeMdat) {
                    raf.seek(atom.offset)
                    copyBytes(raf, out, atom.size)
                }

                out.write(moovData)

                raf.seek(mdat.offset)
                copyBytes(raf, out, mdat.size)

                for (atom in afterMdat) {
                    raf.seek(atom.offset)
                    copyBytes(raf, out, atom.size)
                }
            }
        }
        return true
    }

    internal fun parseTopLevelAtoms(raf: RandomAccessFile, fileSize: Long): List<Atom>? {
        val atoms = mutableListOf<Atom>()
        var pos = 0L
        val header = ByteArray(8)

        while (pos < fileSize - 7) {
            raf.seek(pos)
            if (raf.read(header) != 8) break
            val buf = ByteBuffer.wrap(header)
            var size = buf.int.toLong() and 0xFFFFFFFFL
            val type = buf.int

            if (size == 1L) {
                val ext = ByteArray(8)
                if (raf.read(ext) != 8) break
                size = ByteBuffer.wrap(ext).long
            } else if (size == 0L) {
                size = fileSize - pos
            }

            if (size < 8 || pos + size > fileSize) break
            atoms.add(Atom(type, pos, size))
            pos += size
        }
        return atoms.ifEmpty { null }
    }

    internal fun patchMoovOffsets(moovData: ByteArray, delta: Long) {
        patchAtomTree(moovData, 0, moovData.size, delta)
    }

    private fun patchAtomTree(data: ByteArray, start: Int, end: Int, delta: Long) {
        var pos = start + 8
        while (pos < end - 7) {
            val buf = ByteBuffer.wrap(data, pos, 8)
            var size = buf.int.toLong() and 0xFFFFFFFFL
            val type = buf.int

            if (size == 1L && pos + 16 <= end) {
                size = ByteBuffer.wrap(data, pos + 8, 8).long
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < 8) break

            val atomEnd = (pos + size).toInt().coerceAtMost(end)

            when (type) {
                0x7374636F -> patchStco(data, pos, atomEnd, delta) // stco
                0x636F3634 -> patchCo64(data, pos, atomEnd, delta) // co64
                else -> {
                    if (isContainer(type)) {
                        patchAtomTree(data, pos, atomEnd, delta)
                    }
                }
            }
            pos = atomEnd
        }
    }

    private fun patchStco(data: ByteArray, pos: Int, end: Int, delta: Long) {
        if (pos + 16 > end) return
        val count = ByteBuffer.wrap(data, pos + 12, 4).int
        for (i in 0 until count) {
            val off = pos + 16 + i * 4
            if (off + 4 > end) break
            val old = ByteBuffer.wrap(data, off, 4).int.toLong() and 0xFFFFFFFFL
            ByteBuffer.wrap(data, off, 4).putInt((old + delta).toInt())
        }
    }

    private fun patchCo64(data: ByteArray, pos: Int, end: Int, delta: Long) {
        if (pos + 16 > end) return
        val count = ByteBuffer.wrap(data, pos + 12, 4).int
        for (i in 0 until count) {
            val off = pos + 16 + i * 8
            if (off + 8 > end) break
            val old = ByteBuffer.wrap(data, off, 8).long
            ByteBuffer.wrap(data, off, 8).putLong(old + delta)
        }
    }

    internal fun isContainer(type: Int): Boolean = type in intArrayOf(
        0x6D6F6F76, // moov
        0x7472616B, // trak
        0x6D646961, // mdia
        0x6D696E66, // minf
        0x7374626C, // stbl
        0x75647461, // udta
        0x65647473, // edts
        0x64696E66, // dinf
    )

    private fun copyBytes(src: RandomAccessFile, dst: RandomAccessFile, count: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val read = src.read(buf, 0, toRead)
            if (read <= 0) break
            dst.write(buf, 0, read)
            remaining -= read
        }
    }
}
