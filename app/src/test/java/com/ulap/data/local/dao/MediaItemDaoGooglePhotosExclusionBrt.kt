package com.ulap.data.local.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ulap.data.local.db.UlapDatabase
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bug Reproduction Tests — Google Photos items must not be affected by folder exclusion
 * or blocked from re-import by stale fingerprint matches.
 *
 * ## Defects
 *
 * BUG-002a: [MediaItemDao.excludeItemsNotInBuckets] overwrites FAILED Google Photos items
 * to EXCLUDED with "Folder disabled", because "Google Photos" is a synthetic bucket that
 * never appears in the enabled backup folders list.
 *
 * BUG-002b: [MediaItemDao.countItemsMatchingImportFingerprint] counts items regardless of
 * backup status. FAILED/EXCLUDED items matching the fingerprint block re-import attempts.
 *
 * Deterministic: in-memory Room database, no network, no clocks, no randomness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaItemDaoGooglePhotosExclusionBrt {

    private lateinit var db: UlapDatabase
    private lateinit var dao: MediaItemDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            UlapDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.mediaItemDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        id: String,
        bucketName: String,
        backupStatus: BackupStatus,
        fileName: String = "$id.jpg",
        mimeType: String = "image/jpeg",
        errorMessage: String? = null,
        widthPx: Int? = 100,
        heightPx: Int? = 100,
    ) = MediaItemEntity(
        id = id,
        path = "",
        contentUri = "",
        fileName = fileName,
        mimeType = mimeType,
        size = 1000L,
        dateModified = 1_700_000_000L,
        dateTaken = 1_700_000_000L,
        bucketName = bucketName,
        mediaType = MediaType.IMAGE,
        backupStatus = backupStatus,
        errorMessage = errorMessage,
        widthPx = widthPx,
        heightPx = heightPx,
    )

    // ── BUG-002a: excludeItemsNotInBuckets must skip Google Photos bucket ────

    @Test
    fun excludeItemsNotInBuckets_doesNotAffectGooglePhotosBucket() = runBlocking {
        val gphotoFailed = entity(
            id = "gphoto_abc",
            bucketName = "Google Photos",
            backupStatus = BackupStatus.FAILED,
            errorMessage = "Google stream failed: HTTP 429",
        )
        val cameraFailed = entity(
            id = "local_123",
            bucketName = "Camera",
            backupStatus = BackupStatus.FAILED,
            errorMessage = "upload timeout",
        )
        val gphotoPending = entity(
            id = "gphoto_def",
            bucketName = "Google Photos",
            backupStatus = BackupStatus.PENDING,
        )
        dao.upsertAll(listOf(gphotoFailed, cameraFailed, gphotoPending))

        dao.excludeItemsNotInBuckets(listOf("Downloads"))

        val resultGphotoFailed = dao.findById("gphoto_abc")!!
        assertEquals(
            "FAILED Google Photos item must NOT be changed to EXCLUDED",
            BackupStatus.FAILED,
            resultGphotoFailed.backupStatus,
        )
        assertEquals(
            "Original error message must be preserved for Google Photos item",
            "Google stream failed: HTTP 429",
            resultGphotoFailed.errorMessage,
        )

        val resultGphotoPending = dao.findById("gphoto_def")!!
        assertEquals(
            "PENDING Google Photos item must NOT be changed to EXCLUDED",
            BackupStatus.PENDING,
            resultGphotoPending.backupStatus,
        )

        val resultCamera = dao.findById("local_123")!!
        assertEquals(
            "FAILED Camera item NOT in enabled buckets must be changed to EXCLUDED",
            BackupStatus.EXCLUDED,
            resultCamera.backupStatus,
        )
        assertEquals(
            "Camera item error must be overwritten to 'Folder disabled'",
            "Folder disabled",
            resultCamera.errorMessage,
        )
    }

    @Test
    fun excludeItemsNotInBuckets_doesNotAffectCloudOnlyGooglePhotos() = runBlocking {
        val gphotoSuccess = entity(
            id = "gphoto_ok",
            bucketName = "Google Photos",
            backupStatus = BackupStatus.CLOUD_ONLY,
        )
        dao.upsert(gphotoSuccess)

        dao.excludeItemsNotInBuckets(listOf("Camera"))

        val result = dao.findById("gphoto_ok")!!
        assertEquals(
            "CLOUD_ONLY Google Photos items must never be touched by folder exclusion",
            BackupStatus.CLOUD_ONLY,
            result.backupStatus,
        )
    }

    // ── BUG-002b: countItemsMatchingImportFingerprint must skip non-successful ──

    @Test
    fun countImportFingerprint_doesNotCountFailedOrExcludedItems() = runBlocking {
        val failedItem = entity(
            id = "gphoto_fail",
            bucketName = "Google Photos",
            backupStatus = BackupStatus.FAILED,
            fileName = "IMG_001.jpg",
            mimeType = "image/jpeg",
            widthPx = 4000,
            heightPx = 3000,
        )
        val excludedItem = entity(
            id = "gphoto_excl",
            bucketName = "Google Photos",
            backupStatus = BackupStatus.EXCLUDED,
            fileName = "IMG_002.jpg",
            mimeType = "image/jpeg",
            widthPx = 4000,
            heightPx = 3000,
        )
        dao.upsertAll(listOf(failedItem, excludedItem))

        val countFailed = dao.countItemsMatchingImportFingerprint(
            fileName = "IMG_001.jpg",
            mimeType = "image/jpeg",
            widthPx = 4000,
            heightPx = 3000,
        )
        assertEquals(
            "FAILED items must NOT be counted as duplicates (would block re-import)",
            0,
            countFailed,
        )

        val countExcluded = dao.countItemsMatchingImportFingerprint(
            fileName = "IMG_002.jpg",
            mimeType = "image/jpeg",
            widthPx = 4000,
            heightPx = 3000,
        )
        assertEquals(
            "EXCLUDED items must NOT be counted as duplicates (would block re-import)",
            0,
            countExcluded,
        )
    }

    @Test
    fun countImportFingerprint_countsBackedUpAndCloudOnlyItems() = runBlocking {
        val backedUp = entity(
            id = "local_backed",
            bucketName = "Camera",
            backupStatus = BackupStatus.BACKED_UP,
            fileName = "IMG_003.jpg",
            mimeType = "image/jpeg",
            widthPx = 4000,
            heightPx = 3000,
        )
        val cloudOnly = entity(
            id = "gphoto_cloud",
            bucketName = "Google Photos",
            backupStatus = BackupStatus.CLOUD_ONLY,
            fileName = "IMG_004.jpg",
            mimeType = "image/jpeg",
            widthPx = 4000,
            heightPx = 3000,
        )
        dao.upsertAll(listOf(backedUp, cloudOnly))

        val countBackedUp = dao.countItemsMatchingImportFingerprint(
            fileName = "IMG_003.jpg",
            mimeType = "image/jpeg",
            widthPx = 4000,
            heightPx = 3000,
        )
        assertEquals(
            "BACKED_UP items must be counted as duplicates (prevent re-upload of existing backup)",
            1,
            countBackedUp,
        )

        val countCloudOnly = dao.countItemsMatchingImportFingerprint(
            fileName = "IMG_004.jpg",
            mimeType = "image/jpeg",
            widthPx = 4000,
            heightPx = 3000,
        )
        assertEquals(
            "CLOUD_ONLY items must be counted as duplicates (already imported successfully)",
            1,
            countCloudOnly,
        )
    }

    @Test
    fun countImportFingerprint_doesNotCountPendingOrUploadingItems() = runBlocking {
        val pendingItem = entity(
            id = "local_pending",
            bucketName = "Camera",
            backupStatus = BackupStatus.PENDING,
            fileName = "IMG_005.jpg",
            mimeType = "image/jpeg",
            widthPx = 4000,
            heightPx = 3000,
        )
        dao.upsert(pendingItem)

        val count = dao.countItemsMatchingImportFingerprint(
            fileName = "IMG_005.jpg",
            mimeType = "image/jpeg",
            widthPx = 4000,
            heightPx = 3000,
        )
        assertEquals(
            "PENDING items must NOT block import (not yet successfully backed up)",
            0,
            count,
        )
    }
}
