package com.ulap.domain.repository

import com.ulap.domain.model.BackupFolder
import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    fun observeTimeline(): Flow<List<MediaItem>>
    fun observeByFolder(bucketName: String): Flow<List<MediaItem>>
    fun observeBackupStats(): Flow<BackupStats>
    suspend fun scanAndSync(fullScan: Boolean = false)
    suspend fun getItemById(id: String): MediaItem?
}
