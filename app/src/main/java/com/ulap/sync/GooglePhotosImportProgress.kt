package com.ulap.sync

import androidx.work.Data
import androidx.work.workDataOf

/** WorkManager [Data] keys for [GooglePhotosImportWorker] progress updates. */
object GooglePhotosImportProgress {
    /** Successfully imported item count (legacy WM key name). */
    const val KEY_IMPORTED = "progress"

    /** Items handled this run (success, duplicate skip, failure). */
    const val KEY_PROCESSED = "processed"

    /** Total items in the picker session for this job (stable denominator). */
    const val KEY_SELECTED_TOTAL = "selected_total"
}

internal fun buildGooglePhotosImportProgressData(
    imported: Int,
    processed: Int,
    selectedTotal: Int,
): Data = workDataOf(
    GooglePhotosImportProgress.KEY_IMPORTED to imported,
    GooglePhotosImportProgress.KEY_PROCESSED to processed,
    GooglePhotosImportProgress.KEY_SELECTED_TOTAL to selectedTotal,
)

/** WorkManager output [Data] keys for a finished [GooglePhotosImportWorker] run. */
object GooglePhotosImportOutput {
    const val KEY_VERSION = "result_version"
    const val KEY_PROCESSED = "result_processed"
    const val KEY_IMPORTED = "result_imported"
    const val KEY_SKIPPED_DUPLICATE = "result_skipped_duplicate"
    const val KEY_SKIPPED_UNSUPPORTED = "result_skipped_unsupported"
    const val KEY_FAILED = "result_failed"
    /** 1 if the user paused / work was stopped before the queue finished. */
    const val KEY_STOPPED_EARLY = "result_stopped_early"
}

private const val RESULT_DATA_VERSION = 1

internal fun buildGooglePhotosImportSuccessOutput(
    processed: Int,
    imported: Int,
    skippedDuplicate: Int,
    skippedUnsupported: Int,
    failed: Int,
    stoppedEarly: Boolean,
): Data = workDataOf(
    GooglePhotosImportOutput.KEY_VERSION to RESULT_DATA_VERSION,
    GooglePhotosImportOutput.KEY_PROCESSED to processed,
    GooglePhotosImportOutput.KEY_IMPORTED to imported,
    GooglePhotosImportOutput.KEY_SKIPPED_DUPLICATE to skippedDuplicate,
    GooglePhotosImportOutput.KEY_SKIPPED_UNSUPPORTED to skippedUnsupported,
    GooglePhotosImportOutput.KEY_FAILED to failed,
    GooglePhotosImportOutput.KEY_STOPPED_EARLY to if (stoppedEarly) 1 else 0,
)
