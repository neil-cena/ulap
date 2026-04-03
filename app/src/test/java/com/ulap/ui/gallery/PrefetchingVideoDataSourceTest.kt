package com.ulap.ui.gallery

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * BRT suite for [PrefetchingVideoDataSource].
 *
 * Both tests document the NEW contract: IOException thrown by
 * [ChunkPrefetchEngine.waitForChunk] must be caught and rethrown as a
 * [DataSourceException] with [PlaybackException.ERROR_CODE_IO_UNSPECIFIED].
 *
 * Both tests FAIL against the current implementation (which calls
 * `runBlocking { prefetchEngine.waitForChunk(chunkIndex) }` without any
 * try/catch) because `assertThrows(DataSourceException::class.java)` will
 * not match a raw [IOException].
 *
 * Both tests PASS once the wrapping is in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrefetchingVideoDataSourceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private fun chunk(
        index: Int,
        byteOffset: Long = 0L,
        byteLength: Int = 1024,
    ) = ChunkMetadataEntity(
        id = (index + 1).toLong(),
        mediaItemId = "media-brt",
        chunkIndex = index,
        telegramFileId = "tg-brt-$index",
        telegramMessageId = 9000L + index,
        byteOffset = byteOffset,
        byteLength = byteLength,
        status = ChunkStatus.UPLOADED,
    )

    // =========================================================================
    // Test 1 — open() must wrap IOException as DataSourceException
    // =========================================================================

    /**
     * RED: [PrefetchingVideoDataSource.open] currently propagates the
     * [IOException] thrown by [ChunkPrefetchEngine.waitForChunk] as-is,
     * because there is no try/catch around the `runBlocking { ... }` call.
     * [assertThrows] expects [DataSourceException] but receives a raw
     * [IOException], causing the assertion to fail.
     *
     * GREEN: Passes once `open()` catches [IOException] from `waitForChunk`
     * and rethrows it as `DataSourceException(cause, ERROR_CODE_IO_UNSPECIFIED)`.
     */
    @Test
    fun `open wraps IOException from waitForChunk as DataSourceException`() {
        // Arrange: engine whose waitForChunk always throws IOException
        val chunkDir = temporaryFolder.root
        val chunk = chunk(index = 0, byteOffset = 0L, byteLength = 1024)
        // thenAnswer bypasses Mockito's checked-exception validation, which rejects IOException
        // on suspend funs because their JVM signature lacks a `throws` declaration.
        val engine = mock<ChunkPrefetchEngine> {
            onBlocking { waitForChunk(any()) }.thenAnswer { throw IOException("simulated prefetch failure") }
        }
        val dataSource = PrefetchingVideoDataSource(chunkDir, listOf(chunk), engine)
        val dataSpec = DataSpec(Uri.EMPTY)

        // Act + Assert: open() must throw DataSourceException, not raw IOException
        val thrown = assertThrows(DataSourceException::class.java) {
            dataSource.open(dataSpec)
        }

        assertEquals(
            "open() must rethrow IOException as DataSourceException with ERROR_CODE_IO_UNSPECIFIED",
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            thrown.reason,
        )
    }

    // =========================================================================
    // Test 2 — read() must wrap IOException as DataSourceException
    // =========================================================================

    /**
     * RED: [PrefetchingVideoDataSource.read] currently propagates the
     * [IOException] thrown by [ChunkPrefetchEngine.waitForChunk] as-is.
     * [assertThrows] expects [DataSourceException] but receives a raw
     * [IOException], causing the assertion to fail.
     *
     * The test writes a real chunk file so that `open()` succeeds on the first
     * `waitForChunk` call. The IOException is injected only on the second call
     * (from `read()`), which isolates the failure to the read path.
     *
     * GREEN: Passes once `read()` catches [IOException] from `waitForChunk`
     * and rethrows it as `DataSourceException(cause, ERROR_CODE_IO_UNSPECIFIED)`.
     */
    @Test
    fun `read wraps IOException from waitForChunk as DataSourceException`() {
        // Arrange: write a real chunk file so open() can proceed past waitForChunk
        val chunkDir = temporaryFolder.root
        File(chunkDir, "chunk_0.dat").writeBytes(ByteArray(100) { it.toByte() })
        val chunk = chunk(index = 0, byteOffset = 0L, byteLength = 100)

        val engine = mock<ChunkPrefetchEngine>()
        // isChunkReady returns false by default on a mock, but explicit for clarity
        whenever(engine.isChunkReady(any())).thenReturn(false)

        // First call (open) succeeds; second call (read) throws IOException.
        // thenAnswer bypasses Mockito's checked-exception validation (see Test 1 comment).
        runBlocking {
            whenever(engine.waitForChunk(any()))
                .thenReturn(Unit)
                .thenAnswer { throw IOException("simulated read-time prefetch failure") }
        }

        val dataSource = PrefetchingVideoDataSource(chunkDir, listOf(chunk), engine)
        val dataSpec = DataSpec(Uri.EMPTY)

        // open() must succeed — the first waitForChunk returns Unit
        dataSource.open(dataSpec)

        // Act + Assert: read() must throw DataSourceException, not raw IOException
        val buffer = ByteArray(64)
        val thrown = assertThrows(DataSourceException::class.java) {
            dataSource.read(buffer, 0, 64)
        }

        assertEquals(
            "read() must rethrow IOException as DataSourceException with ERROR_CODE_IO_UNSPECIFIED",
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            thrown.reason,
        )
    }
}
