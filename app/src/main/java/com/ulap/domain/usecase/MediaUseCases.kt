package com.ulap.domain.usecase

import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.MediaItem
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

class ScanMediaUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(fullScan: Boolean = false) =
        mediaRepository.scanAndSync(fullScan)
}
