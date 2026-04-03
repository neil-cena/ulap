package com.ulap.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadByteLengthTest {

    @Test
    fun coalesceStatSize_prefersPositiveStat() {
        assertEquals(1864647L, coalesceStatSize(1864573L, 1864647L))
    }

    @Test
    fun coalesceStatSize_fallsBackWhenStatNull() {
        assertEquals(1864573L, coalesceStatSize(1864573L, null))
    }

    @Test
    fun coalesceStatSize_ignoresNonPositiveStat() {
        assertEquals(100L, coalesceStatSize(100L, 0L))
        assertEquals(100L, coalesceStatSize(100L, -1L))
    }
}
