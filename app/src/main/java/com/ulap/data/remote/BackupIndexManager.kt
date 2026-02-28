package com.ulap.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import com.ulap.debug.DebugLogBuffer
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
private const val MAX_EXPORT_RECONCILIATION_ATTEMPTS = 2

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
    private val debugLog: DebugLogBuffer,
) {

    private val gson = Gson()

    /** Uploads the backup index to the chat. Returns the document file_id on success (for "Sync from other device"). */
    suspend fun exportAndUpload(token: String, chatId: String): Result<String?> = withContext(Dispatchers.IO) {
        val localItems = mediaItemDao.getAllIndexedItems().map { it.toIndexEntry() }
        var seedItems = localItems
        var lastFileId: String? = null

        repeat(MAX_EXPORT_RECONCILIATION_ATTEMPTS) {
            val mergedItems = mergeWithPinnedIndex(token, chatId, seedItems)
            if (mergedItems.isEmpty()) return@withContext Result.success(null)

            val uploadResult = uploadAndPinIndex(token, chatId, mergedItems)
            if (uploadResult.isFailure) {
                return@withContext Result.failure(
                    uploadResult.exceptionOrNull() ?: Exception("Index upload failed")
                )
            }
            lastFileId = uploadResult.getOrNull()

            val latestPinnedItems = loadPinnedIndexEntries(token, chatId).getOrElse {
                // If we cannot read the pin now, keep previous behavior and do not fail export.
                return@withContext Result.success(lastFileId)
            }

            // If pinned index already contains everything we exported, reconciliation is complete.
            if (hasAllFileIds(latestPinnedItems, mergedItems)) {
                return@withContext Result.success(lastFileId)
            }

            // Another device likely pinned concurrently; merge and retry once.
            seedItems = mergeEntriesByFileId(latestPinnedItems, mergedItems)
        }

        Result.success(lastFileId)
    }

    /** Fetches index from the chat's pinned message (works across all devices with the same credentials). */
    suspend fun fetchAndMerge(token: String, chatId: String): Result<Int> = withContext(Dispatchers.IO) {
        debugLog.log("IndexManager", "fetchAndMerge: querying pinned message for chatId=$chatId")
        val safeToken = sanitizeTokenForPath(token)
        val chatResponse = rateLimiter.withRateLimit {
            api.getChat(safeToken, chatId)
        }
        if (!chatResponse.ok || chatResponse.result == null) {
            if (chatResponse.errorCode == 429) {
                val retryAfterMs = (chatResponse.parameters?.retryAfter ?: 30) * 1_000L
                throw TelegramRateLimitException(retryAfterMs)
            }
            debugLog.log("IndexManager", "fetchAndMerge: getChat failed — ${chatResponse.description}")
            return@withContext Result.failure(Exception(chatResponse.description ?: "getChat failed"))
        }
        val pinnedMessage = chatResponse.result.pinnedMessage
        if (pinnedMessage?.document == null || pinnedMessage.caption != INDEX_CAPTION) {
            debugLog.log("IndexManager", "fetchAndMerge: no valid pinned index found")
            return@withContext Result.success(0)
        }
        val fileId = pinnedMessage.document.fileId
        debugLog.log("IndexManager", "fetchAndMerge: found index document fileId=$fileId")
        fetchAndMergeFromFileId(token, fileId)
    }

    /** Downloads the backup index by file_id and merges into local DB. Use for "Sync from other device" (index from another phone). */
    suspend fun fetchAndMergeFromFileId(token: String, fileId: String): Result<Int> = withContext(Dispatchers.IO) {
        val manifestResult = downloadIndexManifest(token, fileId)
        if (manifestResult.isFailure) {
            return@withContext Result.failure(
                manifestResult.exceptionOrNull() ?: Exception("Unknown index download error")
            )
        }
        val manifest = manifestResult.getOrThrow()
        debugLog.log("IndexManager", "fetchAndMergeFromFileId: index has ${manifest.items.size} entries, exportedAt=${manifest.exportedAt}")
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
        debugLog.log("IndexManager", "fetchAndMergeFromFileId: merged $merged new items")
        Result.success(merged)
    }

    private suspend fun mergeWithPinnedIndex(
        token: String,
        chatId: String,
        localItems: List<IndexEntry>,
    ): List<IndexEntry> {
        val pinnedItems = try {
            loadPinnedIndexEntries(token, chatId).getOrElse {
                debugLog.log("IndexManager", "exportAndUpload: pinned index merge skipped — ${it.message}")
                emptyList()
            }
        } catch (e: Exception) {
            debugLog.log("IndexManager", "exportAndUpload: pinned index merge failed — ${e.message}")
            emptyList()
        }
        return mergeEntriesByFileId(pinnedItems, localItems)
    }

    private suspend fun loadPinnedIndexEntries(token: String, chatId: String): Result<List<IndexEntry>> = withContext(Dispatchers.IO) {
        val safeToken = sanitizeTokenForPath(token)
        val chatResponse = rateLimiter.withRateLimit {
            api.getChat(safeToken, chatId)
        }
        if (!chatResponse.ok || chatResponse.result == null) {
            if (chatResponse.errorCode == 429) {
                val retryAfterMs = (chatResponse.parameters?.retryAfter ?: 30) * 1_000L
                return@withContext Result.failure(TelegramRateLimitException(retryAfterMs))
            }
            return@withContext Result.failure(Exception(chatResponse.description ?: "getChat failed"))
        }

        val pinnedMessage = chatResponse.result.pinnedMessage
        if (pinnedMessage?.document == null || pinnedMessage.caption != INDEX_CAPTION) {
            return@withContext Result.success(emptyList())
        }

        val fileId = pinnedMessage.document.fileId
        val manifest = downloadIndexManifest(token, fileId)
        if (manifest.isFailure) return@withContext Result.failure(manifest.exceptionOrNull() ?: Exception("Index download failed"))
        Result.success(manifest.getOrThrow().items)
    }

    private suspend fun downloadIndexManifest(token: String, fileId: String): Result<IndexManifest> = withContext(Dispatchers.IO) {
        debugLog.log("IndexManager", "downloadIndexManifest: downloading index fileId=$fileId")
        val out = ByteArrayOutputStream()
        when (val dr = downloader.download(token, fileId, out)) {
            is DownloadResult.Error -> {
                debugLog.log("IndexManager", "downloadIndexManifest: download error — ${dr.cause.message}")
                return@withContext Result.failure(dr.cause)
            }
            is DownloadResult.Success -> { }
        }
        val manifest = try {
            gson.fromJson(out.toString(Charsets.UTF_8.name()), IndexManifest::class.java)
        } catch (e: Exception) {
            debugLog.log("IndexManager", "downloadIndexManifest: parse error — ${e.message}")
            return@withContext Result.failure(e)
        }
        Result.success(manifest)
    }

    private suspend fun uploadAndPinIndex(
        token: String,
        chatId: String,
        items: List<IndexEntry>,
    ): Result<String?> = withContext(Dispatchers.IO) {
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
                    // Pin is best-effort; bot may lack admin rights.
                }
            }
            Result.success(fileId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mergeEntriesByFileId(base: List<IndexEntry>, overlay: List<IndexEntry>): List<IndexEntry> {
        val mergedByFileId = LinkedHashMap<String, IndexEntry>()
        for (entry in base) {
            mergedByFileId.putIfAbsent(entry.telegramFileId, entry)
        }
        for (entry in overlay) {
            mergedByFileId[entry.telegramFileId] = entry
        }
        return mergedByFileId.values.sortedByDescending { it.dateTaken }
    }

    private fun hasAllFileIds(source: List<IndexEntry>, expected: List<IndexEntry>): Boolean {
        val sourceIds = source.map { it.telegramFileId }.toHashSet()
        return expected.all { sourceIds.contains(it.telegramFileId) }
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
