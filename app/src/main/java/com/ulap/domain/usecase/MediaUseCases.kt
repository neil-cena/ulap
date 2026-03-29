package com.ulap.domain.usecase

import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import com.ulap.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
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
