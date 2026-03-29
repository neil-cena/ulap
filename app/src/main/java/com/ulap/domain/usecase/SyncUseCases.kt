package com.ulap.domain.usecase

import com.ulap.data.remote.BackupIndexManager
import com.ulap.domain.repository.CredentialRepository
import com.ulap.sync.DeleteAllBackupsResult
import com.ulap.sync.SyncEngine
import javax.inject.Inject

class FetchIndexFromFileIdUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val backupIndexManager: BackupIndexManager,
) {
    suspend operator fun invoke(fileIdOrLink: String): Result<Int> {
        val token = credentialRepository.getBotToken() ?: return Result.failure(Exception("No bot token"))
        val fileId = fileIdOrLink.trim().let { s ->
            if (s.contains("=")) s.substringAfterLast("=").trim() else s
        }
        if (fileId.isBlank()) return Result.failure(Exception("Enter the index link or file ID"))
        return backupIndexManager.fetchAndMergeFromFileId(token, fileId)
    }
}

class FetchIndexFromPinnedMessageUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val backupIndexManager: BackupIndexManager,
) {
    suspend operator fun invoke(): Result<Int> {
        val token = credentialRepository.getBotToken() ?: return Result.failure(Exception("No bot token"))
        val chatId = credentialRepository.getChatId() ?: return Result.failure(Exception("No chat ID"))
        return backupIndexManager.fetchAndMerge(token, chatId)
    }
}

/**
 * Rebuilds missing `chunk_metadata` for corrupt chunked rows: pinned index (chunk file or message IDs),
 * legacy `uploadedChunks` JSON, or `chunkMessageIds` JSON (resolves file IDs via short-lived forward+delete).
 */
class RepairCorruptChunkMetadataFromPinnedIndexUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val backupIndexManager: BackupIndexManager,
) {
    suspend operator fun invoke(): Result<Int> {
        val token = credentialRepository.getBotToken() ?: return Result.failure(Exception("No bot token"))
        val chatId = credentialRepository.getChatId() ?: return Result.failure(Exception("No chat ID"))
        return backupIndexManager.repairCorruptChunkMetadataFromPinnedIndex(token, chatId)
    }
}

class DownloadCloudItemUseCase @Inject constructor(
    private val syncEngine: SyncEngine,
) {
    suspend operator fun invoke(mediaId: String) = syncEngine.downloadCloudItemToLocal(mediaId)
}

class StartBackupUseCase @Inject constructor(
    private val syncEngine: SyncEngine,
) {
    suspend operator fun invoke() = syncEngine.startUpload()
}

class RetryFailedUseCase @Inject constructor(
    private val syncEngine: SyncEngine,
) {
    suspend operator fun invoke() = syncEngine.retryFailed()
}

class ResetFailedToPendingUseCase @Inject constructor(
    private val syncEngine: SyncEngine,
) {
    suspend operator fun invoke() = syncEngine.resetFailedToPending()
}

class StartRestoreUseCase @Inject constructor(
    private val syncEngine: SyncEngine,
) {
    suspend operator fun invoke() = syncEngine.startDownload()
}

class CancelSyncUseCase @Inject constructor(
    private val syncEngine: SyncEngine,
) {
    operator fun invoke() = syncEngine.cancel()
}

class DeleteAllBackupsUseCase @Inject constructor(
    private val syncEngine: SyncEngine,
) {
    suspend operator fun invoke(
        onProgress: (deleted: Int, total: Int) -> Unit = { _, _ -> },
    ) = syncEngine.deleteAllBackups(onProgress)
}
