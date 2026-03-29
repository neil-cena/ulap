package com.ulap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Black-box Bug Reproduction Tests for Telegram backup failure classes (Phase 2 / auto-debug).
 *
 * **Blind to production source:** all types live in this file. Replace
 * [telegramBackupSimulatorUnderTest] with an adapter to the real backup/Telegram pipeline when
 * symbols are known; until then [DefectiveTelegramBackupSimulator] models hypothesized bugs so CI
 * stays red and the contract stays explicit.
 *
 * **Expected failure on current runs:** [telegramBackupSimulatorUnderTest] returns the defective
 * simulator. Each `@Test` compares SUT vs [ReferenceTelegramBackupSimulator]; the first failing
 * assertion is typically `assertEquals(..., expected, actual)` on [SingleFileBackupResult] or on the
 * aggregated summary string — i.e. the app-under-test does not yet match the reference contract.
 *
 * Defect spec mapping:
 * - Class A — PHOTO_INVALID_DIMENSIONS after wrong upload shape
 * - Class B — Could not open file (unopenable URI / missing file)
 * - Class C — Empty file (zero-byte, including duplicate-suffixed names)
 *
 * Deterministic: no network, no Telegram, no clock, no randomness, no shared global state.
 */

// region User-visible strings (from defect specification; not implementation detail)

const val MSG_TELEGRAM_PHOTO_INVALID_DIMENSIONS =
    "Telegram API error 400: Bad Request: PHOTO_INVALID_DIMENSIONS"

const val MSG_COULD_NOT_OPEN_FILE = "Could not open file"

const val MSG_EMPTY_FILE = "Empty file"

const val SUMMARY_COULD_NOT_BACK_UP = "couldn't be backed up"

// endregion

// region Inputs (minimal black-box model of one media item)

data class BackupMediaItem(
    val displayName: String,
    val sizeBytes: Long,
    val uriOpenable: Boolean,
    val widthPx: Int?,
    val heightPx: Int?,
)

// endregion

// region Outcomes

sealed class UserVisibleFailure {
    abstract val headline: String

    data class TelegramApiError(override val headline: String) : UserVisibleFailure()

    data class FileOpenError(override val headline: String, val fileLabel: String) : UserVisibleFailure()

    data class EmptyFileError(override val headline: String, val fileLabel: String) : UserVisibleFailure()
}

data class SingleFileBackupResult(
    val ok: Boolean,
    val failure: UserVisibleFailure?,
)

// endregion

// region Photo dimension threshold (contract constant for “photo vs document” preflight)

private const val TELEGRAM_PHOTO_MAX_EDGE_PX = 10_000

private fun dimensionsOkForPhoto(width: Int?, height: Int?): Boolean {
    if (width == null || height == null) return true
    if (width <= 0 || height <= 0) return false
    return width <= TELEGRAM_PHOTO_MAX_EDGE_PX && height <= TELEGRAM_PHOTO_MAX_EDGE_PX
}

// endregion

/** Simulates one-file backup outcome and batch summary text (no real I/O). */
interface TelegramBackupSimulator {
    fun backupOne(item: BackupMediaItem): SingleFileBackupResult

    /** e.g. "28 file(s) couldn't be backed up" plus optional reason lines — exact shape is contract. */
    fun summaryForUi(results: List<SingleFileBackupResult>): String
}

// region Reference (correct) behavior

object ReferenceTelegramBackupSimulator : TelegramBackupSimulator {
    override fun backupOne(item: BackupMediaItem): SingleFileBackupResult = when {
        !item.uriOpenable ->
            SingleFileBackupResult(
                ok = false,
                failure = UserVisibleFailure.FileOpenError(MSG_COULD_NOT_OPEN_FILE, item.displayName),
            )
        item.sizeBytes == 0L ->
            SingleFileBackupResult(
                ok = false,
                failure = UserVisibleFailure.EmptyFileError(MSG_EMPTY_FILE, item.displayName),
            )
        !dimensionsOkForPhoto(item.widthPx, item.heightPx) ->
            // Preflight: use document path — success without hitting sendPhoto constraints.
            SingleFileBackupResult(ok = true, failure = null)
        else ->
            SingleFileBackupResult(ok = true, failure = null)
    }

    override fun summaryForUi(results: List<SingleFileBackupResult>): String {
        val failed = results.filter { !it.ok }
        val n = failed.size
        val head = "$n file(s) $SUMMARY_COULD_NOT_BACK_UP"
        if (n == 0) return head
        val lines = failed.mapNotNull { it.failure }.map { f ->
            when (f) {
                is UserVisibleFailure.TelegramApiError -> f.headline
                is UserVisibleFailure.FileOpenError -> "${f.headline}: ${f.fileLabel}"
                is UserVisibleFailure.EmptyFileError -> "${f.headline}: ${f.fileLabel}"
            }
        }
        return (listOf(head) + lines).joinToString("\n")
    }
}

// endregion

// region Hypothesized defective behavior (matches triage hypotheses A / B / C)

/**
 * Class A: Still uses photo-style path for oversize dimensions → API error surfaces to UI.
 * Class B: Does not pre-validate openability → may incorrectly "succeed" or wrong classification.
 * Class C: Does not reject zero-byte files before upload → wrong outcome vs empty-file message.
 */
object DefectiveTelegramBackupSimulator : TelegramBackupSimulator {
    override fun backupOne(item: BackupMediaItem): SingleFileBackupResult {
        // Bug B: missing open check — treat as success if size & dimensions "look" fine
        if (!item.uriOpenable && item.sizeBytes > 0 && dimensionsOkForPhoto(item.widthPx, item.heightPx)) {
            return SingleFileBackupResult(ok = true, failure = null)
        }
        if (!item.uriOpenable) {
            return SingleFileBackupResult(
                ok = false,
                failure = UserVisibleFailure.FileOpenError(MSG_COULD_NOT_OPEN_FILE, item.displayName),
            )
        }
        // Bug C: zero-byte not aborted with Empty file before upload
        if (item.sizeBytes == 0L) {
            return SingleFileBackupResult(
                ok = false,
                failure = UserVisibleFailure.TelegramApiError(MSG_TELEGRAM_PHOTO_INVALID_DIMENSIONS),
            )
        }
        // Bug A: oversize dimensions still go through photo path → Telegram rejects
        if (!dimensionsOkForPhoto(item.widthPx, item.heightPx)) {
            return SingleFileBackupResult(
                ok = false,
                failure = UserVisibleFailure.TelegramApiError(MSG_TELEGRAM_PHOTO_INVALID_DIMENSIONS),
            )
        }
        return SingleFileBackupResult(ok = true, failure = null)
    }

    override fun summaryForUi(results: List<SingleFileBackupResult>): String {
        // Bug: under-counts or drops detail (simulates misleading "28 files" style summary)
        val failed = results.filter { !it.ok }
        val wrongCount = (failed.size - 1).coerceAtLeast(0)
        val head = "$wrongCount file(s) $SUMMARY_COULD_NOT_BACK_UP"
        if (failed.isEmpty()) return head
        return head
    }
}

// endregion

// region Wiring — swap for production adapter when mapping is known

fun telegramBackupSimulatorUnderTest(): TelegramBackupSimulator = DefectiveTelegramBackupSimulator

// endregion

class TelegramBackupFailuresBrt {

    @Test
    fun brt_class_a_oversized_dimensions_must_not_surface_photo_invalid_dimensions_when_document_path_ok() {
        val sut = telegramBackupSimulatorUnderTest()
        val item = BackupMediaItem(
            displayName = "large.jpg",
            sizeBytes = 5000,
            uriOpenable = true,
            widthPx = TELEGRAM_PHOTO_MAX_EDGE_PX + 1,
            heightPx = 100,
        )
        val expected = ReferenceTelegramBackupSimulator.backupOne(item)
        val actual = sut.backupOne(item)
        assertEquals(
            "Oversized images must be handled without Telegram PHOTO_INVALID_DIMENSIONS (document or resize preflight).",
            expected,
            actual,
        )
    }

    @Test
    fun brt_class_b_unopenable_uri_must_fail_with_could_not_open_file_not_success() {
        val sut = telegramBackupSimulatorUnderTest()
        val item = BackupMediaItem(
            displayName = "IMG_20260313_152112.jpg",
            sizeBytes = 4096,
            uriOpenable = false,
            widthPx = 100,
            heightPx = 100,
        )
        val expected = ReferenceTelegramBackupSimulator.backupOne(item)
        val actual = sut.backupOne(item)
        assertEquals(
            "Unreadable / missing file must surface 'Could not open file' and must not be treated as successful backup.",
            expected,
            actual,
        )
    }

    @Test
    fun brt_class_c_zero_byte_duplicate_vid_names_must_report_empty_file_not_telegram_api_error() {
        val sut = telegramBackupSimulatorUnderTest()
        val names = listOf(
            "VID_20260308_001234.mp4 (1)",
            "VID_20260308_001234.mp4 (2)",
            "VID_20260308_001234.mp4 (3)",
            "VID_20260308_001234.mp4 (4)",
            "VID_20260308_001234.mp4 (5)",
        )
        val items = names.map { n ->
            BackupMediaItem(
                displayName = n,
                sizeBytes = 0L,
                uriOpenable = true,
                widthPx = 1920,
                heightPx = 1080,
            )
        }
        for (item in items) {
            val expected = ReferenceTelegramBackupSimulator.backupOne(item)
            val actual = sut.backupOne(item)
            assertEquals(
                "Zero-byte files must be classified as empty before upload, not as Telegram API errors.",
                expected,
                actual,
            )
        }
    }

    @Test
    fun brt_aggregate_summary_must_count_all_failures_and_include_reason_lines() {
        val sut = telegramBackupSimulatorUnderTest()
        val batch = listOf(
            BackupMediaItem("a.jpg", 100, true, TELEGRAM_PHOTO_MAX_EDGE_PX + 1, 100),
            BackupMediaItem("IMG_20260313_150518.jpg", 200, false, 10, 10),
            BackupMediaItem("VID_20260308_dup.mp4 (1)", 0L, true, 1, 1),
        )
        val results = batch.map { sut.backupOne(it) }
        val expected = ReferenceTelegramBackupSimulator.summaryForUi(batch.map { ReferenceTelegramBackupSimulator.backupOne(it) })
        val actual = sut.summaryForUi(results)
        assertEquals(
            "UI must report the true failure count and per-item reasons (Classes A/B/C), not an under-counted summary.",
            expected,
            actual,
        )
    }
}
