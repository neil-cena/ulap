package com.ulap.data.remote

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

    suspend fun exportAndUpload(token: String, chatId: String): Result<String?> = withContext(Dispatchers.IO) {
        val entities = mediaItemDao.getAllBackedUp()
        if (entities.isEmpty()) return@withContext Result.success(null)

        val manifest = IndexManifest(items = entities.map { it.toIndexEntry() })
        val bytes = gson.toJson(manifest).toByteArray(Charsets.UTF_8)
        val safeToken = sanitizeTokenForPath(token)
        val part = okhttp3.MultipartBody.Part.createFormData(
            "document", INDEX_FILENAME, bytes.toRequestBody("application/json".toMediaType()),
        )
        val chatIdBody = chatId.toRequestBody("text/plain".toMediaType())
        val captionBody = INDEX_CAPTION.toRequestBody("text/plain".toMediaType())

        try {
            val response = rateLimiter.withRateLimit {
                api.sendDocument(safeToken, chatIdBody, part, captionBody)
            }
            if (!response.ok) {
                rateLimiter.recordFailure()
                return@withContext Result.failure(Exception(response.description ?: "Upload failed"))
            }
            rateLimiter.recordSuccess()
            Result.success(response.result?.document?.fileId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
                mediaItemDao.updateBackupResult(
                    id = local.id, status = BackupStatus.BACKED_UP, error = null,
                    syncedAt = System.currentTimeMillis(), fileId = entry.telegramFileId,
                    messageId = entry.telegramMessageId, thumbnailFileId = entry.thumbnailFileId,
                )
                merged++
            } else {
                val cloudId = "cloud_${entry.id}"
                if (mediaItemDao.findById(cloudId) != null) continue
                mediaItemDao.upsert(
                    MediaItemEntity(
                        id = cloudId, path = "", contentUri = "",
                        fileName = entry.fileName, mimeType = entry.mimeType, size = entry.size,
                        dateModified = entry.dateTaken, dateTaken = entry.dateTaken,
                        bucketName = entry.bucketName, mediaType = MediaType.valueOf(entry.mediaType),
                        durationMs = entry.durationMs, backupStatus = BackupStatus.CLOUD_ONLY,
                        telegramFileId = entry.telegramFileId, telegramMessageId = entry.telegramMessageId,
                        thumbnailFileId = entry.thumbnailFileId,
                    )
                )
                merged++
            }
        }
        Result.success(merged)
    }

    private fun MediaItemEntity.toIndexEntry() = IndexEntry(
        id = id, telegramFileId = telegramFileId!!, telegramMessageId = telegramMessageId,
        fileName = fileName, mimeType = mimeType, size = size, dateTaken = dateTaken,
        bucketName = bucketName, mediaType = mediaType.name, durationMs = durationMs,
        thumbnailFileId = thumbnailFileId,
    )
}
