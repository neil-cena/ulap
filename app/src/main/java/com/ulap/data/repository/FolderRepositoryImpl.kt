package com.ulap.data.repository

import com.ulap.data.local.MediaStoreScanner
import com.ulap.data.local.dao.BackupFolderDao
import com.ulap.data.local.entity.BackupFolderEntity
import com.ulap.domain.model.BackupFolder
import com.ulap.domain.repository.FolderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderRepositoryImpl @Inject constructor(
    private val folderDao: BackupFolderDao,
    private val scanner: MediaStoreScanner,
) : FolderRepository {

    override fun observeFolders(): Flow<List<BackupFolder>> =
        folderDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun refreshFolders() {
        val outcome = scanner.scanFolders()
        if (!outcome.mediaStoreQueriesSucceeded) return
        val deviceFolders = outcome.folders
        val existing = folderDao.getEnabled().associate { it.bucketName to true }
        val entities = deviceFolders.map { folder ->
            BackupFolderEntity(
                bucketName = folder.bucketName,
                displayName = folder.displayName,
                isEnabled = existing.getOrDefault(folder.bucketName, false),
                itemCount = folder.totalCount,
                backedUpCount = 0,
            )
        }
        folderDao.upsertAll(entities)
    }

    override suspend fun setFolderEnabled(bucketName: String, enabled: Boolean) {
        folderDao.setEnabled(bucketName, enabled)
    }

    override suspend fun getEnabledFolders(): List<BackupFolder> =
        folderDao.getEnabled().map { it.toDomain() }

    private fun BackupFolderEntity.toDomain() = BackupFolder(
        bucketName = bucketName,
        displayName = displayName,
        isEnabled = isEnabled,
        itemCount = itemCount,
        backedUpCount = backedUpCount,
    )
}
