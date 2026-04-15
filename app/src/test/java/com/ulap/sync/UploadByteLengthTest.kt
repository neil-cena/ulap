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

    // ── resolveEmptyFileSize BRTs (BUG-005: MediaStore race, SIZE=0 for new files) ──
    //
    // When MediaStore reports SIZE=0 for a file that was just created (race condition),
    // re-querying the ContentResolver via statSize returns the real size. The upload
    // must proceed rather than being rejected as "Empty file".

    @Test
    fun resolveEmptyFileSize_staleZeroEntitySize_positiveStatSize_returnsStat() {
        // MediaStore race: entity has size=0 but the real file has bytes.
        // The upload must NOT be rejected; statSize must win.
        assertEquals(5_242_880L, resolveEmptyFileSize(entitySize = 0L, statSize = 5_242_880L))
    }

    @Test
    fun resolveEmptyFileSize_staleZeroEntitySize_nullStatSize_returnsZero() {
        // ContentResolver cannot determine size either — file is truly empty (or gone).
        assertEquals(0L, resolveEmptyFileSize(entitySize = 0L, statSize = null))
    }

    @Test
    fun resolveEmptyFileSize_positiveEntitySize_returnsEntitySizeWithoutConsultingStat() {
        // Entity size is already valid — statSize (whatever it is) must be ignored.
        assertEquals(1024L, resolveEmptyFileSize(entitySize = 1024L, statSize = 9999L))
        assertEquals(1024L, resolveEmptyFileSize(entitySize = 1024L, statSize = null))
    }
}
