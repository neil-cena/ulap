package com.ulap.domain.backup

import com.ulap.domain.model.BackupStats
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BRT: the 2 GiB per-file backup cap machinery (BackupSingleFileLimitPolicy and
 * BackupStats.excludedOverSingleFileLimit) must be absent from the codebase.
 *
 * These tests FAIL when the classes/fields exist (current state) and PASS after removal (fixed state).
 */
class NoSizeCapBrt {

    @Test
    fun brt_BackupSingleFileLimitPolicy_class_must_not_exist() {
        val result = runCatching {
            Class.forName("com.ulap.domain.backup.BackupSingleFileLimitPolicy")
        }
        assertTrue(
            "BackupSingleFileLimitPolicy must be removed — no per-file size cap exists in this backup system",
            result.isFailure
        )
    }

    @Test
    fun brt_BackupStats_must_not_expose_excludedOverSingleFileLimit() {
        val fieldNames = BackupStats::class.java.declaredFields.map { it.name }
        assertFalse(
            "BackupStats.excludedOverSingleFileLimit must be removed — it references a size cap that no longer exists",
            fieldNames.contains("excludedOverSingleFileLimit")
        )
    }
}
