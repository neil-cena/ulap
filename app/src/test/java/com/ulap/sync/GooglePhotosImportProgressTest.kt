package com.ulap.sync

import androidx.work.Data
import androidx.work.workDataOf
import com.ulap.ui.settings.parseGooglePhotosImportSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED spec: tdd/task_1_red_spec.md — xb-brt-forge, generalPurpose — shape test for WM progress Data.
 */
class GooglePhotosImportProgressTest {

    @Test
    fun progressData_contains_imported_processed_and_selectedTotal_fromInput() {
        // tdd/task_1_red_spec.md — contract keys
        val selectedTotal = 7
        val imported = 3
        val processed = 5
        val input: Data = workDataOf(GooglePhotosImportWorker.KEY_SELECTED_TOTAL to selectedTotal)
        val progress: Data = buildGooglePhotosImportProgressData(
            imported = imported,
            processed = processed,
            selectedTotal = input.getInt(GooglePhotosImportWorker.KEY_SELECTED_TOTAL, -1),
        )
        assertEquals(imported, progress.getInt(GooglePhotosImportProgress.KEY_IMPORTED, -1))
        assertEquals(processed, progress.getInt(GooglePhotosImportProgress.KEY_PROCESSED, -1))
        assertEquals(selectedTotal, progress.getInt(GooglePhotosImportProgress.KEY_SELECTED_TOTAL, -1))
    }

    @Test
    fun successOutput_parsesToSummary() {
        val data = buildGooglePhotosImportSuccessOutput(
            processed = 300,
            imported = 120,
            skippedDuplicate = 170,
            skippedUnsupported = 4,
            failed = 6,
            stoppedEarly = true,
        )
        val s = data.parseGooglePhotosImportSummary()!!
        assertEquals(300, s.processed)
        assertEquals(120, s.imported)
        assertEquals(170, s.skippedDuplicate)
        assertEquals(4, s.skippedUnsupported)
        assertEquals(6, s.failed)
        assertTrue(s.stoppedEarly)
    }

    @Test
    fun successOutput_withoutVersion_doesNotParse() {
        val raw = workDataOf(GooglePhotosImportOutput.KEY_PROCESSED to 1)
        assertEquals(null, raw.parseGooglePhotosImportSummary())
    }
}
