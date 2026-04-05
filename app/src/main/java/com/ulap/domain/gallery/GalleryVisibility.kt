package com.ulap.domain.gallery

import com.ulap.domain.model.BackupStatus

/**
 * Timeline and media-type (Browse) grids: show items that are already in Telegram
 * ([BackupStatus.BACKED_UP] / [BackupStatus.CLOUD_ONLY]), or live in an enabled backup folder,
 * or belong to a bucket not tracked in [allManagedBucketNames] (e.g. Google Photos import).
 */
fun isVisibleInGalleryGrid(
    backupStatus: BackupStatus,
    bucketName: String,
    enabledBucketNames: Set<String>,
    allManagedBucketNames: Set<String>,
): Boolean {
    if (backupStatus == BackupStatus.BACKED_UP || backupStatus == BackupStatus.CLOUD_ONLY) {
        return true
    }
    if (bucketName !in allManagedBucketNames) {
        return true
    }
    return bucketName in enabledBucketNames
}
