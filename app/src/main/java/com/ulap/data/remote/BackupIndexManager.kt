package com.ulap.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.db.ROOM_BATCH_SIZE
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import com.ulap.debug.DebugLogBuffer
import com.ulap.data.remote.CHUNKED_FILE_ID_PREFIX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val INDEX_CAPTION = "[ulap-backup-index]"
private const val INDEX_FILENAME = "ulap_index_latest.json"
private const val SCHEMA_VERSION = 2
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
    @SerializedName("chunkMessageIds") val chunkMessageIds: List<Long>? = null,
    // Schema v2: full chunk data for cross-device restoration without needing the uploader's chat history
    @SerializedName("chunkCount") val chunkCount: Int? = null,
    @SerializedName("chunkFileIds") val chunkFileIds: List<String>? = null,
    @SerializedName("chunkByteLengths") val chunkByteLengths: List<Int>? = null,
    @SerializedName("uploadBotIndex") val uploadBotIndex: Int? = null,
)

data class IndexManifest(
    @SerializedName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerializedName("exportedAt") val exportedAt: Long = System.currentTimeMillis(),
    @SerializedName("items") val items: List<IndexEntry>,
)

/** Carries both the Telegram file_id and message_id of a successfully uploaded index document. */
data class IndexUploadResult(val fileId: String?, val messageId: Long?)

@Singleton
class BackupIndexManager @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val chunkMetadataDao: ChunkMetadataDao,
    private val api: TelegramBotApi,
    private val rateLimiter: TelegramRateLimiter,
    private val downloader: TelegramDownloader,
    private val debugLog: DebugLogBuffer,
) {

    private val gson = Gson()

    /** Uploads the backup index to the chat. Returns the document file_id on success (for "Sync from other device"). */
    suspend fun exportAndUpload(token: String, chatId: String): Result<IndexUploadResult?> = withContext(Dispatchers.IO) {
        val allEntities = mediaItemDao.getAllIndexedItems()
        val localItems = buildList { for (e in allEntities) add(e.toIndexEntry()) }
        var seedItems = localItems
        var lastUploadResult: IndexUploadResult? = null

        repeat(MAX_EXPORT_RECONCILIATION_ATTEMPTS) {
            val mergedItems = mergeWithPinnedIndex(token, chatId, seedItems)
            if (mergedItems.isEmpty()) return@withContext Result.success(null)

            val uploadResult = uploadAndPinIndex(token, chatId, mergedItems)
            if (uploadResult.isFailure) {
                return@withContext Result.failure(
                    uploadResult.exceptionOrNull() ?: Exception("Index upload failed")
                )
            }
            lastUploadResult = uploadResult.getOrNull()

            val latestPinnedItems = loadPinnedIndexEntries(token, chatId).getOrElse {
                // If we cannot read the pin now, keep previous behavior and do not fail export.
                return@withContext Result.success(lastUploadResult)
            }

            // If pinned index already contains everything we exported, reconciliation is complete.
            if (hasAllFileIds(latestPinnedItems, mergedItems)) {
                return@withContext Result.success(lastUploadResult)
            }

            // Another device likely pinned concurrently; merge and retry once.
            seedItems = mergeEntriesByFileId(latestPinnedItems, mergedItems)
        }

        Result.success(lastUploadResult)
    }

    /** Fetches index from the chat's pinned message (works across all devices with the same credentials). */
    suspend fun fetchAndMerge(
        token: String,
        chatId: String,
        fallbackFileId: String? = null,
        fallbackMessageId: Long? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        debugLog.log("IndexManager", "fetchAndMerge: querying pinned message for chatId=$chatId")
        val safeToken = sanitizeTokenForPath(token)
        // Transport failures (HttpException from non-2xx, IOException from socket errors / DNS / reset)
        // must be captured here. `rateLimiter.withRateLimit` only catches TelegramRateLimitException;
        // anything else would propagate into SyncEngine.runUploadPipeline (no catch) and crash the
        // engineScope's StandaloneCoroutine — see crash report HTTP 400 on 25053RT47C.
        val chatResponse = try {
            rateLimiter.withRateLimit {
                api.getChat(safeToken, chatId)
            }
        } catch (e: TelegramRateLimitException) {
            throw e
        } catch (e: Exception) {
            debugLog.log("IndexManager", "fetchAndMerge: getChat transport error — ${e.javaClass.simpleName}: ${e.message}")
            return@withContext Result.failure(e)
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
            if (fallbackFileId != null) {
                debugLog.log("IndexManager", "fetchAndMerge: no valid pinned index — using fallback fileId=$fallbackFileId")
                return@withContext fetchAndMergeFromFileId(token, fallbackFileId)
            }
            debugLog.log("IndexManager", "fetchAndMerge: no valid pinned index found")
            return@withContext Result.success(0)
        }
        val fileId = pinnedMessage.document.fileId
        // Stale-pin guard: if the caller supplied a newer fallback that differs from the pin,
        // pinChatMessage likely failed silently after the last export.  Use the fallback index
        // and attempt to re-pin now so that future syncs on all devices read the correct index.
        if (fallbackFileId != null && fileId != fallbackFileId) {
            debugLog.log("IndexManager", "fetchAndMerge: stale pin detected (pin=$fileId, latest=$fallbackFileId) — using fallback")
            if (fallbackMessageId != null) {
                try {
                    api.pinChatMessage(sanitizeTokenForPath(token), chatId, fallbackMessageId)
                    debugLog.log("IndexManager", "fetchAndMerge: re-pinned msgId=$fallbackMessageId")
                } catch (e: Exception) {
                    debugLog.log("IndexManager", "fetchAndMerge: re-pin failed — ${e.message}")
                }
            }
            return@withContext fetchAndMergeFromFileId(token, fallbackFileId)
        }
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
        if (manifest.items.isEmpty()) return@withContext Result.success(0)

        val manifestFileIds = manifest.items.map { it.telegramFileId }
        val knownFileIds = manifestFileIds
            .chunked(ROOM_BATCH_SIZE)
            .flatMap { mediaItemDao.findExistingTelegramFileIds(it) }
            .toHashSet()

        val potentialCloudIds = manifest.items.map { "cloud_${it.id}" }
        val knownCloudIds = potentialCloudIds
            .chunked(ROOM_BATCH_SIZE)
            .flatMap { mediaItemDao.findExistingIds(it) }
            .toHashSet()

        var merged = 0
        val newEntities = mutableListOf<MediaItemEntity>()
        val pendingChunkInsertions = mutableListOf<Pair<String, IndexEntry>>()

        for (entry in manifest.items) {
            if (entry.telegramFileId in knownFileIds) {
                Log.d("UlapChunkPlay", "fetchAndMerge: skip known fileId=${entry.telegramFileId.take(20)} for ${entry.fileName}")
                val indexBotIndex = entry.uploadBotIndex
                if (indexBotIndex != null) {
                    mediaItemDao.updateUploadBotIndexByFileId(entry.telegramFileId, indexBotIndex)
                }
                continue
            }
            val local = mediaItemDao.findByFileNameSizeDate(entry.fileName, entry.size, entry.dateTaken)
            val targetId: String
            if (local != null) {
                val status = if (local.contentUri.isBlank()) BackupStatus.CLOUD_ONLY else BackupStatus.BACKED_UP
                Log.d("UlapChunkPlay", "fetchAndMerge: matched local=${local.id} for ${entry.fileName} status=$status chunkFileIds=${entry.chunkFileIds?.size}")
                mediaItemDao.updateBackupResult(
                    id = local.id,
                    status = status,
                    error = null,
                    syncedAt = System.currentTimeMillis(),
                    fileId = entry.telegramFileId,
                    messageId = entry.telegramMessageId,
                    thumbnailFileId = entry.thumbnailFileId,
                    uploadBotIndex = entry.uploadBotIndex ?: 0,
                )
                targetId = local.id
                merged++
                // Populate chunk_metadata table from schema v2 index entries.
                // Case A: parent already in DB, FK satisfied — insert immediately.
                val chunkFileIds = entry.chunkFileIds
                if (!chunkFileIds.isNullOrEmpty() && chunkMetadataDao.hasChunks(targetId) == 0) {
                    insertChunkRowsFromIndexEntry(targetId, entry)
                }
            } else {
                val cloudId = "cloud_${entry.id}"
                if (cloudId in knownCloudIds) continue
                Log.d("UlapChunkPlay", "fetchAndMerge: creating cloud entity $cloudId for ${entry.fileName} chunkFileIds=${entry.chunkFileIds?.size}")
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
                    uploadBotIndex = entry.uploadBotIndex ?: 0,
                )
                newEntities.add(entity)
                targetId = cloudId
                merged++
                // Populate chunk_metadata table from schema v2 index entries.
                // Case B: parent not yet in DB — defer until after upsertAll to satisfy FK.
                val chunkFileIds = entry.chunkFileIds
                if (!chunkFileIds.isNullOrEmpty() && chunkMetadataDao.hasChunks(targetId) == 0) {
                    pendingChunkInsertions.add(Pair(targetId, entry))
                }
            }
        }
        if (newEntities.isNotEmpty()) {
            newEntities.chunked(ROOM_BATCH_SIZE).forEach { batch ->
                mediaItemDao.upsertAll(batch)
            }
        }
        for ((pendingId, pendingEntry) in pendingChunkInsertions) {
            if (chunkMetadataDao.hasChunks(pendingId) == 0) {
                insertChunkRowsFromIndexEntry(pendingId, pendingEntry)
            }
        }
        debugLog.log("IndexManager", "fetchAndMergeFromFileId: merged $merged new items")
        Result.success(merged)
    }

    /**
     * For items that already have `chunked:` in DB but lost `chunk_metadata` (legacy bug), the normal
     * merge skips them because `telegramFileId` is already "known". Recovery order:
     * 1. Pinned index [IndexEntry.chunkFileIds] (unchanged).
     * 2. Pinned index [IndexEntry.chunkMessageIds] only — resolve current `file_id` via forward+delete (Bot API has no getMessage).
     * 3. Legacy JSON [MediaItemEntity.uploadedChunks] (file_id list) if count matches sentinel.
     * 4. [MediaItemEntity.chunkMessageIds] JSON — same forward path as (2).
     *
     * Pinned index load failure does not abort repair; other sources may still work.
     */
    suspend fun repairCorruptChunkMetadataFromPinnedIndex(token: String, chatId: String): Result<Int> =
        withContext(Dispatchers.IO) {
            val corrupt = mediaItemDao.getCorruptChunkedBackedUpItems()
            if (corrupt.isEmpty()) {
                debugLog.log("IndexManager", "repairCorruptChunkMetadata: no corrupt chunked rows")
                return@withContext Result.success(0)
            }
            val entries = loadPinnedIndexEntries(token, chatId).getOrElse {
                debugLog.log("IndexManager", "repairCorruptChunkMetadata: pinned index unavailable — ${it.message}")
                emptyList()
            }
            var repaired = 0
            for (local in corrupt) {
                if (chunkMetadataDao.hasChunks(local.id) != 0) continue

                val entry = findIndexEntryForLocal(local, entries)
                if (entry != null && !entry.chunkFileIds.isNullOrEmpty()) {
                    insertChunkRowsFromIndexEntry(local.id, entry)
                }
                if (chunkMetadataDao.hasChunks(local.id) != 0) {
                    repaired++
                    continue
                }

                if (entry != null && !entry.chunkMessageIds.isNullOrEmpty() && entry.chunkFileIds.isNullOrEmpty()) {
                    tryRecoverFromMessageIds(token, chatId, local, entry.chunkMessageIds!!)
                }
                if (chunkMetadataDao.hasChunks(local.id) != 0) {
                    repaired++
                    continue
                }

                if (tryRecoverFromUploadedChunksJson(local)) {
                    repaired++
                    continue
                }

                val localMsgIds = parseLongJsonArray(local.chunkMessageIds)
                if (!localMsgIds.isNullOrEmpty()) {
                    tryRecoverFromMessageIds(token, chatId, local, localMsgIds)
                }
                if (chunkMetadataDao.hasChunks(local.id) != 0) {
                    repaired++
                }
            }
            debugLog.log("IndexManager", "repairCorruptChunkMetadata: repaired $repaired item(s)")
            Result.success(repaired)
        }

    /** Only index rows that share the same [MediaItemEntity.telegramFileId] as the local row (avoids wrong merge). */
    private fun findIndexEntryForLocal(local: MediaItemEntity, entries: List<IndexEntry>): IndexEntry? {
        val sentinel = local.telegramFileId ?: return null
        val same = entries.filter { it.telegramFileId == sentinel }
        if (same.isEmpty()) return null
        return same.firstOrNull { !it.chunkFileIds.isNullOrEmpty() }
            ?: same.firstOrNull { !it.chunkMessageIds.isNullOrEmpty() }
            ?: same.firstOrNull()
    }

    private suspend fun insertChunkRowsFromIndexEntry(targetId: String, entry: IndexEntry) {
        val chunkFileIds = entry.chunkFileIds ?: return
        if (chunkFileIds.isEmpty()) return
        insertChunkRowsFromParts(
            targetId = targetId,
            chunkFileIds = chunkFileIds,
            chunkMessageIds = entry.chunkMessageIds,
            chunkByteLengths = entry.chunkByteLengths,
            totalSize = entry.size,
        )
    }

    private suspend fun insertChunkRowsFromParts(
        targetId: String,
        chunkFileIds: List<String>,
        chunkMessageIds: List<Long>?,
        chunkByteLengths: List<Int>? = null,
        totalSize: Long,
    ): Boolean {
        if (chunkFileIds.isEmpty()) return false
        if (chunkMetadataDao.hasChunks(targetId) != 0) return false
        val lengths = if (chunkByteLengths != null && chunkByteLengths.size == chunkFileIds.size) {
            chunkByteLengths
        } else {
            ChunkMetadataLayout.byteLengthsForChunkedFile(totalSize, chunkFileIds.size)
        }
        Log.d("UlapChunkPlay", "insertChunkRows targetId=$targetId chunks=${chunkFileIds.size} lengths=${lengths.take(3)} totalSize=$totalSize")
        var byteOffset = 0L
        chunkFileIds.forEachIndexed { idx, cFileId ->
            val msgId = chunkMessageIds?.getOrNull(idx) ?: 0L
            chunkMetadataDao.insertChunk(
                ChunkMetadataEntity(
                    mediaItemId = targetId,
                    chunkIndex = idx,
                    telegramFileId = cFileId,
                    telegramMessageId = msgId,
                    byteOffset = byteOffset,
                    byteLength = lengths[idx],
                ),
            )
            byteOffset += lengths[idx]
        }
        return true
    }

    private suspend fun tryRecoverFromUploadedChunksJson(local: MediaItemEntity): Boolean {
        val json = local.uploadedChunks ?: return false
        val ids = parseStringJsonArray(json) ?: return false
        val total = ChunkMetadataLayout.totalChunksFromSentinel(local.telegramFileId) ?: return false
        if (ids.size != total) return false
        return insertChunkRowsFromParts(local.id, ids, null, totalSize = local.size)
    }

    private suspend fun tryRecoverFromMessageIds(
        token: String,
        chatId: String,
        local: MediaItemEntity,
        messageIds: List<Long>,
    ) {
        val total = ChunkMetadataLayout.totalChunksFromSentinel(local.telegramFileId) ?: return
        if (messageIds.size != total) return
        val fileIds = resolveDocumentFileIdsByForwarding(token, chatId, messageIds).getOrElse {
            debugLog.log("IndexManager", "repairCorruptChunkMetadata: message-id resolve failed — ${it.message}")
            return
        }
        if (fileIds.size != total) return
        insertChunkRowsFromParts(local.id, fileIds, messageIds, totalSize = local.size)
    }

    private suspend fun resolveDocumentFileIdsByForwarding(
        token: String,
        chatId: String,
        messageIds: List<Long>,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val safeToken = sanitizeTokenForPath(token)
        val out = ArrayList<String>(messageIds.size)
        for (mid in messageIds) {
            if (mid <= 0L) return@withContext Result.failure(IllegalArgumentException("Invalid message id: $mid"))
            val resp = rateLimiter.withRateLimit {
                api.forwardMessage(safeToken, chatId, chatId, mid)
            }
            if (!resp.ok || resp.result == null) {
                if (resp.errorCode == 429) {
                    val retryAfterMs = (resp.parameters?.retryAfter ?: 30) * 1_000L
                    return@withContext Result.failure(TelegramRateLimitException(retryAfterMs))
                }
                return@withContext Result.failure(Exception(resp.description ?: "forwardMessage failed for message $mid"))
            }
            val fileId = resp.result.document?.fileId
                ?: return@withContext Result.failure(Exception("No document in forwarded message for $mid"))
            out.add(fileId)
            val newMid = resp.result.messageId
            try {
                api.deleteMessage(safeToken, chatId, newMid)
            } catch (_: Exception) {
                // Best-effort cleanup of transient forward.
            }
        }
        Result.success(out)
    }

    private fun parseStringJsonArray(json: String): List<String>? =
        runCatching { gson.fromJson(json, Array<String>::class.java)?.toList() }.getOrNull()

    private fun parseLongJsonArray(json: String?): List<Long>? {
        if (json.isNullOrBlank()) return null
        runCatching { gson.fromJson(json, Array<Long>::class.java)?.toList() }.getOrNull()?.let { return it }
        return runCatching {
            gson.fromJson(json, Array<Double>::class.java)?.map { it.toLong() }
        }.getOrNull()
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

    /** Returns all message IDs from the pinned backup index (non-null, non-zero). Used for "delete all backups". */
    suspend fun loadMessageIdsFromPinnedIndex(token: String, chatId: String): List<Long> {
        val result = loadPinnedIndexEntries(token, chatId)
        if (result.isFailure) {
            debugLog.log("IndexManager", "loadMessageIdsFromPinnedIndex: failed — ${result.exceptionOrNull()?.message}")
            return emptyList()
        }
        val entries = result.getOrThrow()
        val directIds = entries.mapNotNull { it.telegramMessageId }.filter { it > 0L }
        val chunkIds = entries.flatMap { it.chunkMessageIds.orEmpty() }.filter { it > 0L }
        val all = (directIds + chunkIds).distinct()
        debugLog.log("IndexManager", "loadMessageIdsFromPinnedIndex: ${entries.size} entries, ${directIds.size} direct, ${chunkIds.size} chunk IDs, total=${all.size}")
        return all
    }

    private suspend fun loadPinnedIndexEntries(token: String, chatId: String): Result<List<IndexEntry>> = withContext(Dispatchers.IO) {
        val safeToken = sanitizeTokenForPath(token)
        // See fetchAndMerge for why transport failures (HttpException / IOException) must be caught
        // here.  Callers already treat Result.failure as "pinned index unavailable" and fall back
        // safely; an uncaught throw would crash the enclosing coroutine scope.
        val chatResponse = try {
            rateLimiter.withRateLimit {
                api.getChat(safeToken, chatId)
            }
        } catch (e: TelegramRateLimitException) {
            return@withContext Result.failure(e)
        } catch (e: Exception) {
            debugLog.log("IndexManager", "loadPinnedIndexEntries: getChat transport error — ${e.javaClass.simpleName}: ${e.message}")
            return@withContext Result.failure(e)
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
    ): Result<IndexUploadResult?> = withContext(Dispatchers.IO) {
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
                var pinned = false
                for (attempt in 1..3) {
                    try {
                        api.pinChatMessage(safeToken, chatId, messageId)
                        pinned = true
                        break
                    } catch (e: TelegramRateLimitException) {
                        throw e
                    } catch (e: Exception) {
                        debugLog.log("IndexManager", "uploadAndPinIndex: pin attempt $attempt failed — ${e.message}")
                        if (attempt < 3) delay(attempt * 2_000L)
                    }
                }
                if (!pinned) {
                    debugLog.log("IndexManager", "uploadAndPinIndex: all pin attempts failed — index uploaded but not pinned")
                }
            }
            Result.success(IndexUploadResult(fileId, messageId))
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

    private suspend fun MediaItemEntity.toIndexEntry(): IndexEntry {
        val chunks = if (telegramFileId?.startsWith(CHUNKED_FILE_ID_PREFIX) == true) {
            chunkMetadataDao.getChunksForMedia(id)
        } else emptyList()

        return IndexEntry(
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
            chunkMessageIds = if (chunks.isNotEmpty()) chunks.map { it.telegramMessageId }
                else chunkMessageIds?.let {
                    runCatching { gson.fromJson(it, Array<Long>::class.java)?.toList() }.getOrNull()
                },
            chunkCount = chunks.size.takeIf { it > 0 },
            chunkFileIds = chunks.map { it.telegramFileId }.takeIf { it.isNotEmpty() },
            chunkByteLengths = chunks.map { it.byteLength }.takeIf { it.isNotEmpty() },
            uploadBotIndex = uploadBotIndex.takeIf { it != 0 },
        )
    }
}
