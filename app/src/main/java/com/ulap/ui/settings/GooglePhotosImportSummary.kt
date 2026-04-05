package com.ulap.ui.settings

import androidx.work.Data
import com.ulap.sync.GooglePhotosImportOutput

/** Summary shown after a successful Google Photos import work run. */
data class GooglePhotosImportSummary(
    val processed: Int,
    val imported: Int,
    val skippedDuplicate: Int,
    val skippedUnsupported: Int,
    val failed: Int,
    /** Import was interrupted (e.g. pause); counts may be partial. */
    val stoppedEarly: Boolean,
)

fun Data.parseGooglePhotosImportSummary(): GooglePhotosImportSummary? {
    if (getInt(GooglePhotosImportOutput.KEY_VERSION, 0) != 1) return null
    return GooglePhotosImportSummary(
        processed = getInt(GooglePhotosImportOutput.KEY_PROCESSED, 0),
        imported = getInt(GooglePhotosImportOutput.KEY_IMPORTED, 0),
        skippedDuplicate = getInt(GooglePhotosImportOutput.KEY_SKIPPED_DUPLICATE, 0),
        skippedUnsupported = getInt(GooglePhotosImportOutput.KEY_SKIPPED_UNSUPPORTED, 0),
        failed = getInt(GooglePhotosImportOutput.KEY_FAILED, 0),
        stoppedEarly = getInt(GooglePhotosImportOutput.KEY_STOPPED_EARLY, 0) == 1,
    )
}
