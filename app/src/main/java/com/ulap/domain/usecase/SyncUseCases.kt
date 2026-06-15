package com.ulap.domain.usecase

import android.util.Log
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.remote.BackupIndexManager
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramApiException
import com.ulap.domain.repository.CredentialRepository
import com.ulap.sync.DeleteAllBackupsResult
import com.ulap.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        return backupIndexManager.fetchAndMerge(
            token = token,
            chatId = chatId,
            fallbackFileId = credentialRepository.getLastIndexFileId(),
            fallbackMessageId = credentialRepository.getLastIndexMessageId(),
        )
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

class MarkCorruptChunkedItemsForReuploadUseCase @Inject constructor(
    private val mediaItemDao: MediaItemDao,
) {
    suspend operator fun invoke(): Int = mediaItemDao.markCorruptChunkedItemsForReupload()
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

sealed class DeleteFileResult {
    data object Success : DeleteFileResult()
    data class Error(val message: String) : DeleteFileResult()
}

/**
 * Deletes a single file/message from Telegram.
 * Requires the message ID to be available for the media item.
 */
class DeleteFileFromTelegramUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val telegramApi: TelegramBotApi,
) {
    suspend operator fun invoke(messageId: Long): DeleteFileResult = withContext(Dispatchers.IO) {
        try {
            val token = credentialRepository.getBotToken()
                ?: return@withContext DeleteFileResult.Error("No bot token found")
            val chatId = credentialRepository.getChatId()
                ?: return@withContext DeleteFileResult.Error("No chat ID found")

            val response = telegramApi.deleteMessage(
                token = token,
                chatId = chatId,
                messageId = messageId
            )

            if (response.ok) {
                DeleteFileResult.Success
            } else {
                val errorMsg = response.description ?: "Unknown error"
                Log.w("DeleteFileFromTelegramUseCase", "Delete failed: $errorMsg (code: ${response.errorCode})")
                DeleteFileResult.Error(errorMsg)
            }
        } catch (e: TelegramApiException) {
            // Message not found or already deleted is acceptable
            if (e.errorCode == 400 && e.description?.contains("not found", ignoreCase = true) == true) {
                Log.i("DeleteFileFromTelegramUseCase", "Message already deleted: ${e.message}")
                DeleteFileResult.Success
            } else {
                Log.w("DeleteFileFromTelegramUseCase", "API error: ${e.message}")
                DeleteFileResult.Error(e.description ?: "Failed to delete from Telegram")
            }
        } catch (e: Exception) {
            Log.w("DeleteFileFromTelegramUseCase", "Unexpected error: ${e.message}", e)
            DeleteFileResult.Error(e.message ?: "Unexpected error")
        }
    }
}
