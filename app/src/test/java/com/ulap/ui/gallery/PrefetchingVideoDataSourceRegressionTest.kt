package com.ulap.ui.gallery

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Regression tests for [PrefetchingVideoDataSource] covering the playback
 * failures observed during the debugging session:
 *
 * 1. **IOException wrapping** — ExoPlayer expects DataSourceException, not
 *    raw IOException from waitForChunk failures.
 *
 * 2. **Chunk boundary transitions** — when data spans multiple chunks,
 *    the data source must correctly advance to the next chunk and trigger
 *    prefetch window advancement.
 *
 * 3. **Byte-range seeks** — ExoPlayer's MP4 parser seeks to the end of
 *    the file for moov/sidx atoms; the data source must correctly map
 *    byte offsets to chunk indices.
 *
 * 4. **setPrefetchOrigin called on open** — each open() must call
 *    setPrefetchOrigin to ensure the prefetch window starts at the
 *    correct chunk, preventing stale-window issues.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrefetchingVideoDataSourceRegressionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun chunk(
        index: Int,
        byteOffset: Long = index.toLong() * 1024,
        byteLength: Int = 1024,
    ) = ChunkMetadataEntity(
        id = (index + 1).toLong(),
        mediaItemId = "media-ds-reg",
        chunkIndex = index,
        telegramFileId = "tg-ds-$index",
        telegramMessageId = 5000L + index,
        byteOffset = byteOffset,
        byteLength = byteLength,
        status = ChunkStatus.UPLOADED,
    )

    // =========================================================================
    // Regression 1: open() wraps IOException as DataSourceException
    // =========================================================================

    @Test
    fun `open wraps IOException from waitForChunk as DataSourceException with IO_UNSPECIFIED`() {
        val engine = mock<ChunkPrefetchEngine> {
            onBlocking { waitForChunk(any()) }.thenAnswer {
                throw IOException("simulated chunk failure")
            }
        }
        val chunks = listOf(chunk(0))
        val ds = PrefetchingVideoDataSource(temporaryFolder.root, chunks, engine)

        val thrown = assertThrows(DataSourceException::class.java) {
            ds.open(DataSpec(Uri.EMPTY))
        }

        assertEquals(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            thrown.reason,
        )
    }

    // =========================================================================
    // Regression 2: read() wraps IOException as DataSourceException
    // =========================================================================

    @Test
    fun `read wraps IOException from waitForChunk as DataSourceException`() {
        val chunkDir = temporaryFolder.root
        File(chunkDir, "chunk_0.dat").writeBytes(ByteArray(100) { it.toByte() })
        val chunks = listOf(chunk(0, byteOffset = 0, byteLength = 100))

        val engine = mock<ChunkPrefetchEngine>()
        whenever(engine.isChunkReady(any())).thenReturn(false)
        runBlocking {
            whenever(engine.waitForChunk(any()))
                .thenReturn(Unit) // open succeeds
                .thenAnswer { throw IOException("read-time failure") }
        }

        val ds = PrefetchingVideoDataSource(chunkDir, chunks, engine)
        ds.open(DataSpec(Uri.EMPTY))

        val thrown = assertThrows(DataSourceException::class.java) {
            ds.read(ByteArray(64), 0, 64)
        }

        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, thrown.reason)
    }

    // =========================================================================
    // Regression 3: Correct chunk index for byte-range seeks
    // =========================================================================

    /**
     * ExoPlayer's MP4 parser seeks to the end of the file to read the moov
     * atom. The data source must correctly map the byte offset to the last
     * chunk index. Previously, when setPrefetchOrigin was called for the
     * last chunk, it cleared all state — including the first chunk that was
     * already downloaded — causing PARSING_CONTAINER_MALFORMED.
     */
    @Test
    fun `open at last chunk offset maps to correct chunk index`() {
        val chunkDir = temporaryFolder.root
        val chunks = listOf(
            chunk(0, byteOffset = 0, byteLength = 1024),
            chunk(1, byteOffset = 1024, byteLength = 1024),
            chunk(2, byteOffset = 2048, byteLength = 512),
        )

        File(chunkDir, "chunk_2.dat").writeBytes(ByteArray(512))

        val engine = mock<ChunkPrefetchEngine>()
        whenever(engine.isChunkReady(any())).thenReturn(true)
        runBlocking { whenever(engine.waitForChunk(any())).thenReturn(Unit) }

        val ds = PrefetchingVideoDataSource(chunkDir, chunks, engine)
        val bytesRemaining = ds.open(DataSpec(Uri.EMPTY, 2048, C.LENGTH_UNSET.toLong()))

        assertEquals("bytesRemaining should be last chunk size", 512L, bytesRemaining)
        verify(engine).setPrefetchOrigin(2)
    }

    @Test
    fun `open at middle of second chunk calculates correct position in chunk`() {
        val chunkDir = temporaryFolder.root
        val chunks = listOf(
            chunk(0, byteOffset = 0, byteLength = 1000),
            chunk(1, byteOffset = 1000, byteLength = 1000),
            chunk(2, byteOffset = 2000, byteLength = 500),
        )

        File(chunkDir, "chunk_1.dat").writeBytes(ByteArray(1000))

        val engine = mock<ChunkPrefetchEngine>()
        runBlocking { whenever(engine.waitForChunk(any())).thenReturn(Unit) }

        val ds = PrefetchingVideoDataSource(chunkDir, chunks, engine)
        val offset = 1500L // middle of chunk 1
        val bytesRemaining = ds.open(DataSpec(Uri.EMPTY, offset, C.LENGTH_UNSET.toLong()))

        val expectedRemaining = 2500L - 1500L // total=2500, position=1500
        assertEquals(expectedRemaining, bytesRemaining)
        verify(engine).setPrefetchOrigin(1)
    }

    // =========================================================================
    // Regression 4: setPrefetchOrigin called on every open
    // =========================================================================

    @Test
    fun `open always calls setPrefetchOrigin for the target chunk`() {
        val chunkDir = temporaryFolder.root
        File(chunkDir, "chunk_0.dat").writeBytes(ByteArray(100))
        val chunks = listOf(chunk(0, byteOffset = 0, byteLength = 100))

        val engine = mock<ChunkPrefetchEngine>()
        runBlocking { whenever(engine.waitForChunk(any())).thenReturn(Unit) }

        val ds = PrefetchingVideoDataSource(chunkDir, chunks, engine)
        ds.open(DataSpec(Uri.EMPTY))

        verify(engine).setPrefetchOrigin(0)
    }

    // =========================================================================
    // Regression 5: Sequential read across chunk boundaries
    // =========================================================================

    @Test
    fun `read crosses chunk boundary and advances to next chunk`() {
        val chunkDir = temporaryFolder.root
        val chunk0Data = ByteArray(50) { 0xAA.toByte() }
        val chunk1Data = ByteArray(50) { 0xBB.toByte() }
        File(chunkDir, "chunk_0.dat").writeBytes(chunk0Data)
        File(chunkDir, "chunk_1.dat").writeBytes(chunk1Data)

        val chunks = listOf(
            chunk(0, byteOffset = 0, byteLength = 50),
            chunk(1, byteOffset = 50, byteLength = 50),
        )

        val engine = mock<ChunkPrefetchEngine>()
        whenever(engine.isChunkReady(any())).thenReturn(true)
        runBlocking { whenever(engine.waitForChunk(any())).thenReturn(Unit) }

        val ds = PrefetchingVideoDataSource(chunkDir, chunks, engine)
        ds.open(DataSpec(Uri.EMPTY))

        val buffer = ByteArray(100)
        var totalRead = 0
        var readAttempts = 0
        while (totalRead < 100 && readAttempts < 10) {
            val n = ds.read(buffer, totalRead, 100 - totalRead)
            if (n == C.RESULT_END_OF_INPUT) break
            totalRead += n
            readAttempts++
        }

        assertEquals("Should read all 100 bytes across both chunks", 100, totalRead)
        assertEquals("First 50 bytes should be from chunk 0", 0xAA.toByte(), buffer[0])
        assertEquals("Last 50 bytes should be from chunk 1", 0xBB.toByte(), buffer[50])
    }

    // =========================================================================
    // Regression 6: advanceOrigin called when crossing chunk boundary
    // =========================================================================

    @Test
    fun `advanceToNextChunk calls advanceOrigin on the prefetch engine`() {
        val chunkDir = temporaryFolder.root
        File(chunkDir, "chunk_0.dat").writeBytes(ByteArray(10))
        File(chunkDir, "chunk_1.dat").writeBytes(ByteArray(10))

        val chunks = listOf(
            chunk(0, byteOffset = 0, byteLength = 10),
            chunk(1, byteOffset = 10, byteLength = 10),
        )

        val engine = mock<ChunkPrefetchEngine>()
        whenever(engine.isChunkReady(any())).thenReturn(true)
        runBlocking { whenever(engine.waitForChunk(any())).thenReturn(Unit) }

        val ds = PrefetchingVideoDataSource(chunkDir, chunks, engine)
        ds.open(DataSpec(Uri.EMPTY))

        // Read all of chunk 0 to trigger advancement
        val buf = ByteArray(10)
        ds.read(buf, 0, 10)

        // Next read should trigger advanceOrigin(1)
        ds.read(buf, 0, 10)

        verify(engine).advanceOrigin(1)
    }

    // =========================================================================
    // Regression 7: EOF handling
    // =========================================================================

    @Test
    fun `read returns END_OF_INPUT when all data is consumed`() {
        val chunkDir = temporaryFolder.root
        File(chunkDir, "chunk_0.dat").writeBytes(ByteArray(10))

        val chunks = listOf(chunk(0, byteOffset = 0, byteLength = 10))
        val engine = mock<ChunkPrefetchEngine>()
        whenever(engine.isChunkReady(any())).thenReturn(true)
        runBlocking { whenever(engine.waitForChunk(any())).thenReturn(Unit) }

        val ds = PrefetchingVideoDataSource(chunkDir, chunks, engine)
        ds.open(DataSpec(Uri.EMPTY))

        val buf = ByteArray(10)
        ds.read(buf, 0, 10) // consume all data

        val result = ds.read(buf, 0, 10) // should be EOF
        assertEquals(C.RESULT_END_OF_INPUT, result)
    }

    @Test
    fun `read returns END_OF_INPUT when bytesRemaining is zero`() {
        val chunkDir = temporaryFolder.root
        File(chunkDir, "chunk_0.dat").writeBytes(ByteArray(100))

        val chunks = listOf(chunk(0, byteOffset = 0, byteLength = 100))
        val engine = mock<ChunkPrefetchEngine>()
        runBlocking { whenever(engine.waitForChunk(any())).thenReturn(Unit) }

        val ds = PrefetchingVideoDataSource(chunkDir, chunks, engine)
        // Request only 10 bytes
        ds.open(DataSpec(Uri.EMPTY, 0, 10))

        val buf = ByteArray(10)
        ds.read(buf, 0, 10)

        val result = ds.read(buf, 0, 10)
        assertEquals(C.RESULT_END_OF_INPUT, result)
    }

    // =========================================================================
    // Regression 8: Factory creates correct instances
    // =========================================================================

    @Test
    fun `Factory creates PrefetchingVideoDataSource instances`() {
        val engine = mock<ChunkPrefetchEngine>()
        val chunks = listOf(chunk(0))
        val factory = PrefetchingVideoDataSource.Factory(temporaryFolder.root, chunks, engine)

        val ds = factory.createDataSource()
        assertTrue(ds is PrefetchingVideoDataSource)
    }
}
