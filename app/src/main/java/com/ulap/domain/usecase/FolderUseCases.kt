package com.ulap.domain.usecase

import com.ulap.domain.model.BackupFolder
import com.ulap.domain.repository.FolderRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveFoldersUseCase @Inject constructor(
    private val folderRepository: FolderRepository,
) {
    operator fun invoke(): Flow<List<BackupFolder>> = folderRepository.observeFolders()
}

class RefreshFoldersUseCase @Inject constructor(
    private val folderRepository: FolderRepository,
) {
    suspend operator fun invoke() = folderRepository.refreshFolders()
}

class ToggleFolderBackupUseCase @Inject constructor(
    private val folderRepository: FolderRepository,
) {
    suspend operator fun invoke(bucketName: String, enabled: Boolean) =
        folderRepository.setFolderEnabled(bucketName, enabled)
}
