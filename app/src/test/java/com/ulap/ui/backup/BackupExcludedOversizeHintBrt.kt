package com.ulap.ui.backup

import com.ulap.domain.model.BackupStats
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Black-box Bug Reproduction Tests (Phase 2 / auto-debug) for backup stats copy conflation.
 *
 * **Symptom:** The backup card shows
 * `R.string.backup_stat_excluded_hint` (`%1$d file(s) can't be backed up — they exceed the 2 GB limit`)
 * using [BackupStats.excluded] ([BackupScreen] passes `s.excluded` into that string).
 *
 * **Defect:** `EXCLUDED` aggregates folder-disabled, not-on-device, stale-queue exclusions, and true
 * oversize items. The hint text must only count files that **actually** exceed the app's single-file
 * backup byte cap — not every excluded row.
 *
 * **Contract under test (expected correct behavior):** The integer formatted into
 * `backup_stat_excluded_hint` must equal [specOversizeExcludedHintCount], not the total excluded
 * count, whenever those differ.
 *
 * Deterministic: no network, no Room, no MediaStore, no clock, no randomness.
 *
 * **After fix:** Replace [hintCountUsedByBackupScreenToday] with the dedicated field or use-case
 * output wired from the repository; keep the policy assertions as regression tests.
 */

// Matches product copy "2 GB limit" as a binary-size cap (same convention as typical Android file limits).
private const val MAX_SINGLE_FILE_BACKUP_BYTES: Long = 2L * 1024 * 1024 * 1024

/** Minimal row shape: what the repository/UI layer should use to classify the hint bucket. */
private data class ExcludedRowSpec(
    val sizeBytes: Long?,
    val errorMessage: String?,
)

/**
 * Expected count for `backup_stat_excluded_hint` — **spec**, not current app logic.
 *
 * - Unknown size (`null`): do **not** count toward oversize (avoids false positives from bad metadata).
 * - Non-positive stored size: not oversize (zero-byte / corrupt / missing stat — not the 2GB bucket).
 * - Oversize: strictly greater than [MAX_SINGLE_FILE_BACKUP_BYTES].
 *
 * Classification is **size-based**. A stale `errorMessage` such as legacy `File exceeds 2GB limit` with
 * a small `sizeBytes` must **not** alone place the row in this bucket.
 */
private fun specOversizeExcludedHintCount(excludedRows: List<ExcludedRowSpec>): Int =
    excludedRows.count { row ->
        val s = row.sizeBytes ?: return@count false
        if (s <= 0L) return@count false
        s > MAX_SINGLE_FILE_BACKUP_BYTES
    }

/** Mirrors [BackupScreen]: `stringResource(R.string.backup_stat_excluded_hint, s.excluded)`. */
private fun hintCountUsedByBackupScreenToday(stats: BackupStats): Int = stats.excluded

private fun statsWithExcludedTotal(
    excludedTotal: Int,
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
)

class BackupExcludedOversizeHintBrt {

    @Test
    fun brt_small_file_excluded_for_non_size_reason_must_not_count_in_two_gb_hint() {
        val rows = listOf(
            ExcludedRowSpec(sizeBytes = 1_024L, errorMessage = "Folder disabled"),
            ExcludedRowSpec(sizeBytes = 50_000L, errorMessage = "File no longer on device"),
        )
        val spec = specOversizeExcludedHintCount(rows)
        val stats = statsWithExcludedTotal(excludedTotal = rows.size, total = rows.size)
        assertEquals(
            "Only rows above the single-file cap may appear in the 2 GB hint count",
            spec,
            hintCountUsedByBackupScreenToday(stats),
        )
    }

    @Test
    fun brt_boundary_at_limit_and_one_byte_over() {
        val atLimit = listOf(ExcludedRowSpec(sizeBytes = MAX_SINGLE_FILE_BACKUP_BYTES, errorMessage = null))
        assertEquals(
            0,
            specOversizeExcludedHintCount(atLimit),
        )
        val below = listOf(ExcludedRowSpec(sizeBytes = MAX_SINGLE_FILE_BACKUP_BYTES - 1L, errorMessage = null))
        assertEquals(0, specOversizeExcludedHintCount(below))
        val over = listOf(ExcludedRowSpec(sizeBytes = MAX_SINGLE_FILE_BACKUP_BYTES + 1L, errorMessage = null))
        assertEquals(1, specOversizeExcludedHintCount(over))
        val statsOver = statsWithExcludedTotal(excludedTotal = over.size, total = over.size)
        assertEquals(
            specOversizeExcludedHintCount(over),
            hintCountUsedByBackupScreenToday(statsOver),
        )
    }

    @Test
    fun brt_zero_byte_and_unknown_size_must_not_spuriously_count_as_two_gb_bucket() {
        val zero = listOf(ExcludedRowSpec(sizeBytes = 0L, errorMessage = "File no longer on device"))
        assertEquals(0, specOversizeExcludedHintCount(zero))
        val statsZero = statsWithExcludedTotal(excludedTotal = 1, total = 1)
        assertEquals(
            specOversizeExcludedHintCount(zero),
            hintCountUsedByBackupScreenToday(statsZero),
        )

        val unknown = listOf(ExcludedRowSpec(sizeBytes = null, errorMessage = "File exceeds 2GB limit"))
        assertEquals(
            "Unknown size must not land in the 2GB hint bucket on message text alone",
            0,
            specOversizeExcludedHintCount(unknown),
        )
        val statsUnknown = statsWithExcludedTotal(excludedTotal = 1, total = 1)
        assertEquals(
            specOversizeExcludedHintCount(unknown),
            hintCountUsedByBackupScreenToday(statsUnknown),
        )
    }

    @Test
    fun brt_stale_oversize_error_message_with_small_reported_size_must_not_count() {
        val rows = listOf(
            ExcludedRowSpec(sizeBytes = 4096L, errorMessage = "File exceeds 2GB limit"),
        )
        assertEquals(0, specOversizeExcludedHintCount(rows))
        val stats = statsWithExcludedTotal(excludedTotal = 1, total = 1)
        assertEquals(
            specOversizeExcludedHintCount(rows),
            hintCountUsedByBackupScreenToday(stats),
        )
    }

    @Test
    fun brt_batch_mixed_undersized_exclusions_plus_true_oversize_only_oversize_in_hint_count() {
        val rows = listOf(
            ExcludedRowSpec(100L, "Folder disabled"),
            ExcludedRowSpec(200L, "File no longer on device"),
            ExcludedRowSpec(MAX_SINGLE_FILE_BACKUP_BYTES + 10L, null),
        )
        val spec = specOversizeExcludedHintCount(rows)
        val stats = statsWithExcludedTotal(excludedTotal = rows.size, total = rows.size)
        assertEquals(1, spec)
        assertEquals(
            "Mixed exclusions: hint must show oversize-only count, not total EXCLUDED",
            spec,
            hintCountUsedByBackupScreenToday(stats),
        )
    }
}
