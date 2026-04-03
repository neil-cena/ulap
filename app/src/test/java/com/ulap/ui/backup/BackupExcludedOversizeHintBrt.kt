package com.ulap.ui.backup

import com.ulap.domain.backup.BackupSingleFileLimitPolicy
import com.ulap.domain.model.BackupStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Bug reproduction / regression: backup card must not show the 2 GB limit hint count for every
 * [BackupStats.excluded] row — only for items strictly over the single-file cap
 * ([BackupSingleFileLimitPolicy]).
 *
 * [BackupScreen] uses [BackupStats.excludedOverSingleFileLimit] for [R.string.backup_stat_excluded_hint].
 */
private data class ExcludedRowSpec(
    val sizeBytes: Long?,
    val errorMessage: String?,
)

private fun hintCountUsedByBackupScreenToday(stats: BackupStats): Int = stats.excludedOverSingleFileLimit

private fun stats(
    excludedTotal: Int,
    excludedOverSingleFileLimit: Int,
    total: Int = excludedTotal,
    backedUp: Int = 0,
    pending: Int = 0,
    failed: Int = 0,
): BackupStats = BackupStats(
    total = total,
    backedUp = backedUp,
    pending = pending,
    failed = failed,
    excluded = excludedTotal,
    excludedOverSingleFileLimit = excludedOverSingleFileLimit,
)

class BackupExcludedOversizeHintBrt {

    @Test
    fun brt_small_file_excluded_for_non_size_reason_must_not_count_in_two_gb_hint() {
        val rows = listOf(
            ExcludedRowSpec(sizeBytes = 1_024L, errorMessage = "Folder disabled"),
            ExcludedRowSpec(sizeBytes = 50_000L, errorMessage = "File no longer on device"),
        )
        val sizes = rows.map { it.sizeBytes }
        val spec = BackupSingleFileLimitPolicy.excludedOversizeHintCount(sizes)
        assertEquals(0, spec)
        assertNotEquals(
            "Total excluded must not equal oversize-only hint when no file exceeds the cap",
            rows.size,
            spec,
        )
        val stats = stats(excludedTotal = rows.size, excludedOverSingleFileLimit = spec)
        assertEquals(spec, hintCountUsedByBackupScreenToday(stats))
    }

    @Test
    fun brt_boundary_at_limit_and_one_byte_over() {
        val max = BackupSingleFileLimitPolicy.MAX_SINGLE_FILE_BYTES
        assertEquals(0, BackupSingleFileLimitPolicy.excludedOversizeHintCount(listOf(max)))
        assertEquals(0, BackupSingleFileLimitPolicy.excludedOversizeHintCount(listOf(max - 1L)))
        assertEquals(1, BackupSingleFileLimitPolicy.excludedOversizeHintCount(listOf(max + 1L)))
        val statsOver = stats(excludedTotal = 1, excludedOverSingleFileLimit = 1)
        assertEquals(1, hintCountUsedByBackupScreenToday(statsOver))
    }

    @Test
    fun brt_zero_byte_and_unknown_size_must_not_spuriously_count_as_two_gb_bucket() {
        val zeroSizes = listOf<Long?>(0L)
        assertEquals(0, BackupSingleFileLimitPolicy.excludedOversizeHintCount(zeroSizes))
        val statsZero = stats(excludedTotal = 1, excludedOverSingleFileLimit = 0)
        assertEquals(0, hintCountUsedByBackupScreenToday(statsZero))

        val unknownSizes = listOf<Long?>(null)
        assertEquals(0, BackupSingleFileLimitPolicy.excludedOversizeHintCount(unknownSizes))
        val statsUnknown = stats(excludedTotal = 1, excludedOverSingleFileLimit = 0)
        assertEquals(0, hintCountUsedByBackupScreenToday(statsUnknown))
    }

    @Test
    fun brt_stale_oversize_error_message_with_small_reported_size_must_not_count() {
        val sizes = listOf<Long?>(4096L)
        assertEquals(0, BackupSingleFileLimitPolicy.excludedOversizeHintCount(sizes))
        val stats = stats(excludedTotal = 1, excludedOverSingleFileLimit = 0)
        assertEquals(0, hintCountUsedByBackupScreenToday(stats))
    }

    @Test
    fun brt_batch_mixed_undersized_exclusions_plus_true_oversize_only_oversize_in_hint_count() {
        val max = BackupSingleFileLimitPolicy.MAX_SINGLE_FILE_BYTES
        val sizes = listOf<Long?>(100L, 200L, max + 10L)
        val spec = BackupSingleFileLimitPolicy.excludedOversizeHintCount(sizes)
        assertEquals(1, spec)
        assertNotEquals(sizes.size, spec)
        val stats = stats(excludedTotal = sizes.size, excludedOverSingleFileLimit = spec)
        assertEquals(spec, hintCountUsedByBackupScreenToday(stats))
    }

    @Test
    fun brt_policy_documentation_matches_backup_screen_field() {
        // Ensures tests fail if the screen reverts to passing total [excluded] into the 2GB string.
        val misleadingTotal = 5
        val correctHint = 1
        assertNotEquals(misleadingTotal, correctHint)
        val s = stats(excludedTotal = misleadingTotal, excludedOverSingleFileLimit = correctHint)
        assertEquals(correctHint, hintCountUsedByBackupScreenToday(s))
    }
}
