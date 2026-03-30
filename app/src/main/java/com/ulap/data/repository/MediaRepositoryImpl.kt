package com.ulap.data.repository

import com.ulap.data.local.MediaStoreScanner
import com.ulap.data.local.dao.BackupFolderDao
import com.ulap.data.local.dao.BackupStatsRow
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.dao.SyncStateDao
import com.ulap.data.local.db.ROOM_BATCH_SIZE
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.SyncStateEntity
import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.MediaItem
import com.ulap.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
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

    override fun observeBackupStats(): Flow<BackupStats> =
        mediaItemDao.observeBackupStatsGrouped().map { rows ->
            val byStatus = rows.associateBy { it.backupStatus }
            fun countOf(s: BackupStatus) = byStatus[s]?.count ?: 0
            fun sizeOf(s: BackupStatus) = byStatus[s]?.totalSize ?: 0L

            val backedUp = countOf(BackupStatus.BACKED_UP)
            val pending = countOf(BackupStatus.PENDING) + countOf(BackupStatus.UPLOADING)
            val failed = countOf(BackupStatus.FAILED)
            val excluded = countOf(BackupStatus.EXCLUDED)
            val cloudOnly = countOf(BackupStatus.CLOUD_ONLY)

            BackupStats(
                total = backedUp + pending + failed + excluded + cloudOnly,
                backedUp = backedUp,
                pending = pending,
                failed = failed,
                excluded = excluded,
                cloudOnly = cloudOnly,
                backedUpBytes = sizeOf(BackupStatus.BACKED_UP),
                pendingBytes = sizeOf(BackupStatus.PENDING) + sizeOf(BackupStatus.UPLOADING),
                cloudOnlyBytes = sizeOf(BackupStatus.CLOUD_ONLY),
            )
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

        if (scanned.isNotEmpty()) {
            val existingMap: Map<String, MediaItemEntity> = scanned
                .map { it.id }
                .chunked(ROOM_BATCH_SIZE)
                .flatMap { mediaItemDao.findByIds(it) }
                .associateBy { it.id }

            val newItems = mutableListOf<MediaItemEntity>()
            val existingItems = mutableListOf<MediaItemEntity>()

            for (entity in scanned) {
                val existing = existingMap[entity.id]
                if (existing == null) {
                    newItems += entity
                } else if (existing.backupStatus == BackupStatus.BACKED_UP || existing.backupStatus == BackupStatus.EXCLUDED) {
                    // Preserve ALL backup-related columns so we don't lose Telegram metadata.
                    // IMPORTANT: we must use UPDATE (not REPLACE/INSERT) for existing rows.
                    // INSERT OR REPLACE performs a DELETE + INSERT which triggers the FK CASCADE
                    // on chunk_metadata (ON DELETE CASCADE), wiping all chunk rows for the item.
                    existingItems += entity.copy(
                        backupStatus = existing.backupStatus,
                        telegramFileId = existing.telegramFileId,
                        telegramMessageId = existing.telegramMessageId,
                        lastSyncedAt = existing.lastSyncedAt,
                        thumbnailFileId = existing.thumbnailFileId,
                        thumbnailMessageId = existing.thumbnailMessageId,
                        uploadBotIndex = existing.uploadBotIndex,
                        contentHash = existing.contentHash,
                        chunkMessageIds = existing.chunkMessageIds,
                        uploadedChunks = existing.uploadedChunks,
                        uploadedChunkCount = existing.uploadedChunkCount,
                        errorMessage = existing.errorMessage,
                    )
                } else {
                    // PENDING / FAILED / UPLOADING / CLOUD_ONLY: safe to REPLACE since
                    // chunk_metadata for non-BACKED_UP items is either empty or intentionally
                    // stale (upload in progress). Losing it here just forces a re-upload.
                    existingItems += entity
                }
            }

            // INSERT for brand-new items (no existing row, no CASCADE risk).
            newItems.chunked(ROOM_BATCH_SIZE).forEach { batch ->
                mediaItemDao.upsertAll(batch)
            }
            // UPDATE for existing items — avoids the DELETE+INSERT cycle that REPLACE triggers,
            // which would cascade-delete chunk_metadata rows for BACKED_UP chunked videos.
            existingItems.chunked(ROOM_BATCH_SIZE).forEach { batch ->
                mediaItemDao.updateAll(batch)
            }
        }

        if (effectiveFullScan) {
            // Detect locally-deleted files: BACKED_UP items in enabled buckets that are no
            // longer present in the MediaStore scan are files the user deleted from the device.
            // Mark them CLOUD_ONLY so the viewer falls through to the cloud resolution path
            // instead of trying (and silently failing) to open the now-stale content URI.
            val scannedIds = scanned.map { it.id }.toHashSet()
            val enabledBucketSet = enabledBuckets.toHashSet()
            val deletedLocallyIds = mediaItemDao.getAllBackedUp()
                .filter { it.bucketName in enabledBucketSet && it.contentUri.isNotBlank() && it.id !in scannedIds }
                .map { it.id }
            if (deletedLocallyIds.isNotEmpty()) {
                deletedLocallyIds.chunked(ROOM_BATCH_SIZE).forEach { batch ->
                    mediaItemDao.markAsCloudOnly(batch)
                }
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

    override fun observeCorruptChunkedBackupCount(): Flow<Int> =
        mediaItemDao.observeCorruptChunkedBackupCount()

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
        uploadBotIndex = uploadBotIndex,
    )
}
