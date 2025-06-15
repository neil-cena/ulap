package com.ulap.domain.repository

import com.ulap.domain.model.BackupFolder
import kotlinx.coroutines.flow.Flow

interface FolderRepository {
    fun observeFolders(): Flow<List<BackupFolder>>
    suspend fun refreshFolders()
    suspend fun setFolderEnabled(bucketName: String, enabled: Boolean)
    suspend fun getEnabledFolders(): List<BackupFolder>
}
