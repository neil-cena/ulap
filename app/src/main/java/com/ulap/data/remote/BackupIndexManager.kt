package com.ulap.data.remote // CLOUD_ONLY status fix

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val INDEX_CAPTION = "[ulap-backup-index]"
private const val INDEX_FILENAME = "ulap_index_latest.json"
private const val SCHEMA_VERSION = 1

data class IndexEntry(
    @SerializedName("id") val id: String,
    @SerializedName("telegramFileId") val telegramFileId: String,
    @SerializedName("telegramMessageId") val telegramMessageId: Long?,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("size") val size: Long,
    @SerializedName("dateTaken") val dateTaken: Long,
    @SerializedName("bucketName") val bucketName: String,
    @SerializedName("mediaType") val mediaType: String,
    @SerializedName("durationMs") val durationMs: Long?,
    @SerializedName("thumbnailFileId") val thumbnailFileId: String? = null,
)

data class IndexManifest(
    @SerializedName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerializedName("exportedAt") val exportedAt: Long = System.currentTimeMillis(),
    @SerializedName("items") val items: List<IndexEntry>,
)

@Singleton
class BackupIndexManager @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val api: TelegramBotApi,
    private val rateLimiter: TelegramRateLimiter,
    private val downloader: TelegramDownloader,
) {

    private val gson = Gson()

    /** Uploads the backup index to the chat. Returns the document file_id on success (for "Sync from other device"). */
    suspend fun exportAndUpload(token: String, chatId: String): Result<String?> = withContext(Dispatchers.IO) {
        val entities = mediaItemDao.getAllBackedUp()
        if (entities.isEmpty()) return@withContext Result.success(null)

        val items = entities.map { it.toIndexEntry() }
        val manifest = IndexManifest(items = items)
        val json = gson.toJson(manifest)
        val bytes = json.toByteArray(Charsets.UTF_8)

        val safeToken = sanitizeTokenForPath(token)
        val part = okhttp3.MultipartBody.Part.createFormData(
            "document",
            INDEX_FILENAME,
            bytes.toRequestBody("application/json".toMediaType()),
        )
        val chatIdBody = chatId.toRequestBody("text/plain".toMediaType())
        val captionBody = INDEX_CAPTION.toRequestBody("text/plain".toMediaType())

        try {
            val response = rateLimiter.withRateLimit {
                api.sendDocument(safeToken, chatIdBody, part, captionBody)
            }
            if (!response.ok) {
                if (response.errorCode == 429) {
                    val retryAfterMs = (response.parameters?.retryAfter ?: 30) * 1_000L
                    throw TelegramRateLimitException(retryAfterMs)
                }
                rateLimiter.recordFailure()
                return@withContext Result.failure(Exception(response.description ?: "Upload failed"))
            }
            rateLimiter.recordSuccess()
            val message = response.result
            val fileId = message?.document?.fileId
            val messageId = message?.messageId
            if (messageId != null) {
                try {
                    api.pinChatMessage(safeToken, chatId, messageId)
                } catch (_: Exception) {
                    // Pin is best-effort; bot may lack admin rights
                }
            }
            Result.success(fileId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fetches index from the chat's pinned message (works across all devices with the same credentials). */
    suspend fun fetchAndMerge(token: String, chatId: String): Result<Int> = withContext(Dispatchers.IO) {
        val safeToken = sanitizeTokenForPath(token)
        val chatResponse = rateLimiter.withRateLimit {
            api.getChat(safeToken, chatId)
        }
        if (!chatResponse.ok || chatResponse.result == null) {
            if (chatResponse.errorCode == 429) {
                val retryAfterMs = (chatResponse.parameters?.retryAfter ?: 30) * 1_000L
                throw TelegramRateLimitException(retryAfterMs)
            }
            return@withContext Result.failure(Exception(chatResponse.description ?: "getChat failed"))
        }
        val pinnedMessage = chatResponse.result.pinnedMessage
        if (pinnedMessage?.document == null || pinnedMessage.caption != INDEX_CAPTION) {
            return@withContext Result.success(0)
        }
        val fileId = pinnedMessage.document.fileId
        fetchAndMergeFromFileId(token, fileId)
    }

    /** Downloads the backup index by file_id and merges into local DB. Use for "Sync from other device" (index from another phone). */
    suspend fun fetchAndMergeFromFileId(token: String, fileId: String): Result<Int> = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        when (val dr = downloader.download(token, fileId, out)) {
            is DownloadResult.Error -> return@withContext Result.failure(dr.cause)
            is DownloadResult.Success -> { }
        }
        val manifest = try {
            gson.fromJson(out.toString(Charsets.UTF_8.name()), IndexManifest::class.java)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        var merged = 0
        for (entry in manifest.items) {
            if (mediaItemDao.findByTelegramFileId(entry.telegramFileId) != null) continue
            val local = mediaItemDao.findByFileNameSizeDate(entry.fileName, entry.size, entry.dateTaken)
            if (local != null) {
                val status = if (local.contentUri.isBlank()) BackupStatus.CLOUD_ONLY else BackupStatus.BACKED_UP
                mediaItemDao.updateBackupResult(
                    id = local.id,
                    status = status,
                    error = null,
                    syncedAt = System.currentTimeMillis(),
                    fileId = entry.telegramFileId,
                    messageId = entry.telegramMessageId,
                    thumbnailFileId = entry.thumbnailFileId,
                )
                merged++
            } else {
                val cloudId = "cloud_${entry.id}"
                if (mediaItemDao.findById(cloudId) != null) continue
                val entity = MediaItemEntity(
                    id = cloudId,
                    path = "",
                    contentUri = "",
                    fileName = entry.fileName,
                    mimeType = entry.mimeType,
                    size = entry.size,
                    dateModified = entry.dateTaken,
                    dateTaken = entry.dateTaken,
                    bucketName = entry.bucketName,
                    mediaType = MediaType.valueOf(entry.mediaType),
                    durationMs = entry.durationMs,
                    backupStatus = BackupStatus.CLOUD_ONLY,
                    telegramFileId = entry.telegramFileId,
                    telegramMessageId = entry.telegramMessageId,
                    lastSyncedAt = null,
                    errorMessage = null,
                    thumbnailFileId = entry.thumbnailFileId,
                )
                mediaItemDao.upsert(entity)
                merged++
            }
        }
        Result.success(merged)
    }

    private fun MediaItemEntity.toIndexEntry() = IndexEntry(
        id = id,
        telegramFileId = telegramFileId!!,
        telegramMessageId = telegramMessageId,
        fileName = fileName,
        mimeType = mimeType,
        size = size,
        dateTaken = dateTaken,
        bucketName = bucketName,
        mediaType = mediaType.name,
        durationMs = durationMs,
        thumbnailFileId = thumbnailFileId,
    )
}
