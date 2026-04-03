package com.ulap.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSingleFileLimitPolicyTest {

    private val max = BackupSingleFileLimitPolicy.MAX_SINGLE_FILE_BYTES

    @Test
    fun empty_list_counts_zero() {
        assertEquals(0, BackupSingleFileLimitPolicy.excludedOversizeHintCount(emptyList()))
    }

    @Test
    fun negative_size_not_counted() {
        assertEquals(0, BackupSingleFileLimitPolicy.excludedOversizeHintCount(listOf(-1L)))
    }

    @Test
    fun interleaved_nulls_and_oversize() {
        val sizes = listOf<Long?>(null, max + 1, 10L, null, max + 2)
        assertEquals(2, BackupSingleFileLimitPolicy.excludedOversizeHintCount(sizes))
    }

    @Test
    fun max_constant_is_two_gib() {
        assertEquals(2_147_483_648L, max)
    }
}
