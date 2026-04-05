package com.ulap.domain.gallery

import com.ulap.domain.model.BackupStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryVisibilityTest {

    private val managed = setOf("Camera", "Screenshots")
    private val enabled = setOf("Camera")

    @Test
    fun backedUp_visible_even_when_folder_disabled() {
        assertTrue(
            isVisibleInGalleryGrid(
                backupStatus = BackupStatus.BACKED_UP,
                bucketName = "Screenshots",
                enabledBucketNames = enabled,
                allManagedBucketNames = managed,
            ),
        )
    }

    @Test
    fun cloudOnly_visible_when_folder_disabled() {
        assertTrue(
            isVisibleInGalleryGrid(
                backupStatus = BackupStatus.CLOUD_ONLY,
                bucketName = "Screenshots",
                enabledBucketNames = enabled,
                allManagedBucketNames = managed,
            ),
        )
    }

    @Test
    fun pending_hidden_when_folder_disabled() {
        assertFalse(
            isVisibleInGalleryGrid(
                backupStatus = BackupStatus.PENDING,
                bucketName = "Screenshots",
                enabledBucketNames = enabled,
                allManagedBucketNames = managed,
            ),
        )
    }

    @Test
    fun pending_visible_when_folder_enabled() {
        assertTrue(
            isVisibleInGalleryGrid(
                backupStatus = BackupStatus.PENDING,
                bucketName = "Camera",
                enabledBucketNames = enabled,
                allManagedBucketNames = managed,
            ),
        )
    }

    @Test
    fun importBucket_not_in_managed_always_visible_for_pending() {
        assertTrue(
            isVisibleInGalleryGrid(
                backupStatus = BackupStatus.PENDING,
                bucketName = "Google Photos",
                enabledBucketNames = enabled,
                allManagedBucketNames = managed,
            ),
        )
    }
}
