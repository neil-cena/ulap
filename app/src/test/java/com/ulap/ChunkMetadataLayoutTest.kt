package com.ulap

import com.ulap.data.remote.CHUNK_UPLOAD_SIZE
import com.ulap.data.remote.CHUNKED_FILE_ID_PREFIX
import com.ulap.data.remote.ChunkMetadataLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChunkMetadataLayoutTest {

    @Test
    fun byteLengths_sumToTotal_forTwoChunks() {
        val total = CHUNK_UPLOAD_SIZE + 5L
        val lens = ChunkMetadataLayout.byteLengthsForChunkedFile(total, 2)
        assertEquals(listOf(CHUNK_UPLOAD_SIZE.toInt(), 5), lens)
        assertEquals(total, lens.sum().toLong())
    }

    @Test
    fun byteLengths_singleChunk() {
        val lens = ChunkMetadataLayout.byteLengthsForChunkedFile(100L, 1)
        assertEquals(listOf(100), lens)
    }

    @Test
    fun totalChunksFromSentinel_parses() {
        assertEquals(12, ChunkMetadataLayout.totalChunksFromSentinel("${CHUNKED_FILE_ID_PREFIX}12"))
        assertNull(ChunkMetadataLayout.totalChunksFromSentinel("other"))
        assertNull(ChunkMetadataLayout.totalChunksFromSentinel("${CHUNKED_FILE_ID_PREFIX}0"))
    }
}
