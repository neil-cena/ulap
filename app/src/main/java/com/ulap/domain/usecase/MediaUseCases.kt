package com.ulap.domain.usecase

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import com.ulap.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GetTimelineUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    operator fun invoke(): Flow<List<MediaItem>> = mediaRepository.observeTimeline()
}

class GetBackupStatsUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    operator fun invoke(): Flow<BackupStats> = mediaRepository.observeBackupStats()
}

class GetMediaByTypeUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    operator fun invoke(type: MediaType): Flow<List<MediaItem>> = mediaRepository.observeByMediaType(type)
}

class ScanMediaUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(fullScan: Boolean = false) =
        mediaRepository.scanAndSync(fullScan)
}

class GetBackedUpWithLocalUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(): List<MediaItem> = mediaRepository.getBackedUpWithLocal()
}

class MarkAsCloudOnlyUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(ids: List<String>) = mediaRepository.markAsCloudOnly(ids)
}

sealed class RemoveLocalMediaOutcome {
    data object DeletedLocally : RemoveLocalMediaOutcome()

    data class NeedsDeleteConfirmation(
        val intentSender: android.content.IntentSender,
        val mediaItemId: String,
    ) : RemoveLocalMediaOutcome()
}

/**
 * Deletes the local MediaStore/content row for this item, then marks the DB row cloud-only
 * so the gallery matches "removed from device but still in backup".
 */
class RemoveLocalMediaFileUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val markAsCloudOnly: MarkAsCloudOnlyUseCase,
) {
    suspend operator fun invoke(item: MediaItem): Result<RemoveLocalMediaOutcome> = withContext(Dispatchers.IO) {
        val uriStr = item.contentUri.trim()
        if (uriStr.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No local file"))
        }
        if (item.backupStatus == BackupStatus.CLOUD_ONLY) {
            return@withContext Result.failure(IllegalStateException("Already cloud-only"))
        }
        val uri = Uri.parse(uriStr)
        val rowsDeleted =
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    return@withContext createDeleteRequestResult(item.id, uri)
                }
                return@withContext Result.failure(IllegalStateException("Could not delete from device", e))
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        if (rowsDeleted > 0) {
            markAsCloudOnly(listOf(item.id))
            return@withContext Result.success(RemoveLocalMediaOutcome.DeletedLocally)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@withContext createDeleteRequestResult(item.id, uri)
        }
        return@withContext Result.failure(IllegalStateException("Could not delete from device"))
    }

    private fun createDeleteRequestResult(mediaItemId: String, uri: Uri): Result<RemoveLocalMediaOutcome> =
        try {
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
            Result.success(
                RemoveLocalMediaOutcome.NeedsDeleteConfirmation(pendingIntent.intentSender, mediaItemId),
            )
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not delete from device", e))
        }
}

class ObserveFailedItemsUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    operator fun invoke(): Flow<List<MediaItem>> = mediaRepository.observeFailedItems()
}

class ObserveCorruptChunkedBackupCountUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    operator fun invoke(): Flow<Int> = mediaRepository.observeCorruptChunkedBackupCount()
}
