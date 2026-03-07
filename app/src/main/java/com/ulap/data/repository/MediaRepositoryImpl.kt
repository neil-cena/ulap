package com.ulap.data.repository

import com.ulap.data.local.MediaStoreScanner
import com.ulap.data.local.dao.BackupFolderDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.dao.SyncStateDao
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.SyncStateEntity
import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.MediaItem
import com.ulap.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val folderDao: BackupFolderDao,
    private val syncStateDao: SyncStateDao,
    private val scanner: MediaStoreScanner,
) : MediaRepository {

    override fun observeTimeline(): Flow<List<MediaItem>> =
        mediaItemDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeByFolder(bucketName: String): Flow<List<MediaItem>> =
        mediaItemDao.observeByBuckets(listOf(bucketName)).map { entities -> entities.map { it.toDomain() } }

    override fun observeByMediaType(type: com.ulap.domain.model.MediaType): Flow<List<MediaItem>> =
        mediaItemDao.observeByMediaType(com.ulap.data.local.entity.MediaType.valueOf(type.name))
            .map { entities -> entities.map { it.toDomain() } }

    override fun observeBackupStats(): Flow<BackupStats> = combine(
        mediaItemDao.countByStatus(BackupStatus.BACKED_UP),
        mediaItemDao.countByStatus(BackupStatus.PENDING),
        mediaItemDao.countByStatus(BackupStatus.FAILED),
        mediaItemDao.countByStatus(BackupStatus.EXCLUDED),
        mediaItemDao.countByStatus(BackupStatus.UPLOADING),
    ) { backedUp: Int, pending: Int, failed: Int, excluded: Int, uploading: Int ->
        Triple(backedUp, pending + uploading, Triple(failed, excluded, uploading))
    }.combine(mediaItemDao.countByStatus(BackupStatus.CLOUD_ONLY)) { t, cloudOnly: Int ->
        val (backedUp, pending, failExclUp) = t
        val (failed, excluded, _) = failExclUp
        BackupStats(
            total = backedUp + pending + failed + excluded + cloudOnly,
            backedUp = backedUp,
            pending = pending,
            failed = failed,
            excluded = excluded,
            cloudOnly = cloudOnly,
        )
    }.combine(mediaItemDao.sumSizeByStatus(BackupStatus.BACKED_UP)) { stats, backedUpBytes ->
        stats.copy(backedUpBytes = backedUpBytes)
    }.combine(mediaItemDao.sumSizeByStatus(BackupStatus.PENDING)) { stats, pendingBytes ->
        stats.copy(pendingBytes = pendingBytes)
    }.combine(mediaItemDao.sumSizeByStatus(BackupStatus.CLOUD_ONLY)) { stats, cloudOnlyBytes ->
        stats.copy(cloudOnlyBytes = cloudOnlyBytes)
    }

    override suspend fun scanAndSync(fullScan: Boolean) {
        val syncState = syncStateDao.get() ?: SyncStateEntity()
        val enabledBuckets = folderDao.getEnabled().map { it.bucketName }

        // Mark any pending/failed items from now-disabled buckets as EXCLUDED so they
        // no longer appear in backup stats or get picked up by the upload pipeline.
        if (enabledBuckets.isEmpty()) {
            mediaItemDao.excludeItemsNotInBuckets(listOf("__none__"))
            return
        }
        mediaItemDao.excludeItemsNotInBuckets(enabledBuckets)

        // If the set of enabled buckets has changed since the last scan, do a full scan
        // so we don't miss files whose modification date is older than lastIncrementalScanAt.
        val lastEnabledKey = syncState.lastEnabledBucketsKey ?: ""
        val currentEnabledKey = enabledBuckets.sorted().joinToString(",")
        val bucketSetChanged = lastEnabledKey != currentEnabledKey
        val effectiveFullScan = fullScan || bucketSetChanged

        val since = if (effectiveFullScan) 0L else syncState.lastIncrementalScanAt ?: 0L
        val scanned = scanner.scanMedia(enabledBuckets, since)

        scanned.forEach { entity ->
            val existing = mediaItemDao.findById(entity.id)
            if (existing == null) {
                mediaItemDao.upsert(entity)
            } else if (existing.backupStatus == BackupStatus.BACKED_UP || existing.backupStatus == BackupStatus.EXCLUDED) {
                // don't overwrite already-backed-up status; also don't re-enqueue excluded
                mediaItemDao.upsert(entity.copy(
                    backupStatus = existing.backupStatus,
                    telegramFileId = existing.telegramFileId,
                    telegramMessageId = existing.telegramMessageId,
                    lastSyncedAt = existing.lastSyncedAt,
                    thumbnailFileId = existing.thumbnailFileId,
                ))
            } else {
                mediaItemDao.upsert(entity)
            }
        }

        val now = System.currentTimeMillis()
        if (effectiveFullScan) {
            syncStateDao.upsert(syncState.copy(
                lastFullScanAt = now,
                lastIncrementalScanAt = now,
                lastEnabledBucketsKey = currentEnabledKey,
            ))
        } else {
            syncStateDao.upsert(syncState.copy(
                lastIncrementalScanAt = now,
                lastEnabledBucketsKey = currentEnabledKey,
            ))
        }
    }

    override suspend fun getItemById(id: String): MediaItem? =
        mediaItemDao.findById(id)?.toDomain()

    override suspend fun getBackedUpWithLocal(): List<MediaItem> =
        mediaItemDao.getAllBackedUp()
            .filter { it.contentUri.isNotEmpty() }
            .map { it.toDomain() }

    override suspend fun markAsCloudOnly(ids: List<String>) =
        mediaItemDao.markAsCloudOnly(ids)

    override fun observeFailedItems(): Flow<List<MediaItem>> =
        mediaItemDao.observeByStatus(BackupStatus.FAILED).map { list -> list.map { it.toDomain() } }

    private fun MediaItemEntity.toDomain() = MediaItem(
        id = id,
        path = path,
        contentUri = contentUri,
        fileName = fileName,
        mimeType = mimeType,
        size = size,
        dateModified = dateModified,
        dateTaken = dateTaken,
        bucketName = bucketName,
        mediaType = com.ulap.domain.model.MediaType.valueOf(mediaType.name),
        durationMs = durationMs,
        backupStatus = com.ulap.domain.model.BackupStatus.valueOf(backupStatus.name),
        telegramFileId = telegramFileId,
        telegramMessageId = telegramMessageId,
        lastSyncedAt = lastSyncedAt,
        errorMessage = errorMessage,
        thumbnailFileId = thumbnailFileId,
    )
}
