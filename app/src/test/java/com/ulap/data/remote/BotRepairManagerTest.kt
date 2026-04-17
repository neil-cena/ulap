package com.ulap.data.remote

import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import com.ulap.domain.model.BotCredential
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock

class BotRepairManagerTest {

    private val healthyBot = BotCredential(index = 1, token = "healthy:token")
    private val chatId = "12345"

    @Test
    fun repairNonChunked_updatesFileIdAndBotIndex() = runTest {
        val item = makeItem(
            id = "item1",
            telegramFileId = "OLD_FILE_ID",
            telegramMessageId = 42L,
            uploadBotIndex = 0,
        )
        val dao = FakeMediaItemDao(listOf(item))
        val chunkDao = FakeChunkMetadataDao()
        val api = FakeApi(forwardedFileId = "NEW_FILE_ID", forwardedMsgId = 999L)

        val manager = BotRepairManager(api, fakeLimiter(), dao, chunkDao)
        manager.repairItemsForBannedBot(0, healthyBot, chatId)

        val updated = dao.updatedItems["item1"]!!
        assertEquals("NEW_FILE_ID", updated.telegramFileId)
        assertEquals(1, updated.uploadBotIndex)
    }

    @Test
    fun repairNonChunked_marksNeedsReupload_whenMessageIdMissing() = runTest {
        val item = makeItem(
            id = "item2",
            telegramFileId = "SOME_ID",
            telegramMessageId = null,
            uploadBotIndex = 0,
        )
        val dao = FakeMediaItemDao(listOf(item))
        val chunkDao = FakeChunkMetadataDao()
        val api = FakeApi(forwardedFileId = "X", forwardedMsgId = 1L)

        val manager = BotRepairManager(api, fakeLimiter(), dao, chunkDao)
        manager.repairItemsForBannedBot(0, healthyBot, chatId)

        val progress = manager.repairProgress.value
        assertEquals(1, progress.needsReuploadItems)
        assertEquals(0, progress.repairedItems)
        assertTrue(dao.needsReuploadAnnotations.containsKey("item2"))
    }

    @Test
    fun repairNonChunked_marksNeedsReupload_whenForwardFails() = runTest {
        val item = makeItem(
            id = "item3",
            telegramFileId = "SOME_ID",
            telegramMessageId = 100L,
            uploadBotIndex = 0,
        )
        val dao = FakeMediaItemDao(listOf(item))
        val chunkDao = FakeChunkMetadataDao()
        val api = FakeApi(
            forwardedFileId = null, // simulates message not found
            forwardedMsgId = 0L,
            forwardErrorCode = 400,
        )

        val manager = BotRepairManager(api, fakeLimiter(), dao, chunkDao)
        manager.repairItemsForBannedBot(0, healthyBot, chatId)

        assertEquals(1, manager.repairProgress.value.needsReuploadItems)
    }

    @Test
    fun repairChunked_updatesAllChunkFileIds() = runTest {
        val item = makeItem(
            id = "item4",
            telegramFileId = "chunked:2",
            telegramMessageId = 0L,
            uploadBotIndex = 0,
        )
        val chunks = listOf(
            makeChunk(id = 1L, mediaItemId = "item4", chunkIndex = 0, messageId = 201L, fileId = "OLD_CHUNK_0"),
            makeChunk(id = 2L, mediaItemId = "item4", chunkIndex = 1, messageId = 202L, fileId = "OLD_CHUNK_1"),
        )
        val dao = FakeMediaItemDao(listOf(item))
        val chunkDao = FakeChunkMetadataDao(chunks)
        // Each forward returns a predictable new ID based on originalMsgId
        val api = FakeApi(forwardedFileId = "NEW_CHUNK", forwardedMsgId = 999L)

        val manager = BotRepairManager(api, fakeLimiter(), dao, chunkDao)
        manager.repairItemsForBannedBot(0, healthyBot, chatId)

        val progress = manager.repairProgress.value
        assertEquals(RepairPhase.COMPLETE, progress.phase)
        assertEquals(1, progress.repairedItems)
        // Both chunk file IDs should have been updated
        assertEquals(2, chunkDao.updatedChunks.size)
        chunkDao.updatedChunks.values.forEach { assertEquals("NEW_CHUNK", it) }
        // Parent uploadBotIndex updated to healthy bot
        assertEquals(1, dao.updatedItems["item4"]?.uploadBotIndex)
    }

    @Test
    fun reset_clearsProgressState() = runTest {
        val dao = FakeMediaItemDao(emptyList())
        val chunkDao = FakeChunkMetadataDao()
        val manager = BotRepairManager(FakeApi("X", 1L), fakeLimiter(), dao, chunkDao)

        manager.repairItemsForBannedBot(0, healthyBot, chatId) // runs with empty list → COMPLETE
        assertEquals(RepairPhase.COMPLETE, manager.repairProgress.value.phase)

        manager.reset()
        assertEquals(RepairPhase.IDLE, manager.repairProgress.value.phase)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeItem(
        id: String,
        telegramFileId: String?,
        telegramMessageId: Long?,
        uploadBotIndex: Int,
    ) = MediaItemEntity(
        id = id,
        path = "/tmp/file",
        contentUri = "",
        fileName = "$id.jpg",
        mimeType = "image/jpeg",
        size = 1024L,
        dateModified = 0L,
        dateTaken = 0L,
        bucketName = "Camera",
        mediaType = MediaType.IMAGE,
        backupStatus = BackupStatus.BACKED_UP,
        telegramFileId = telegramFileId,
        telegramMessageId = telegramMessageId,
        uploadBotIndex = uploadBotIndex,
    )

    private fun makeChunk(
        id: Long,
        mediaItemId: String,
        chunkIndex: Int,
        messageId: Long,
        fileId: String,
    ) = ChunkMetadataEntity(
        id = id,
        mediaItemId = mediaItemId,
        chunkIndex = chunkIndex,
        telegramFileId = fileId,
        telegramMessageId = messageId,
        byteOffset = chunkIndex * 19L * 1024 * 1024,
        byteLength = 19 * 1024 * 1024,
        status = ChunkStatus.UPLOADED,
    )

    private fun fakeLimiter(): TelegramRateLimiter = mock { rl ->
        onBlocking {
            @Suppress("UNCHECKED_CAST")
            rl.withRateLimit(any<suspend () -> Any>())
        }.doSuspendableAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            (inv.getArgument(0) as suspend () -> Any).invoke()
        }
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeApi(
        private val forwardedFileId: String?,
        private val forwardedMsgId: Long,
        private val forwardErrorCode: Int? = null,
    ) : TelegramBotApi {
        override suspend fun forwardMessage(
            token: String, chatId: String, fromChatId: String, messageId: Long,
        ): TelegramResponse<com.ulap.data.remote.TelegramMessage> {
            if (forwardErrorCode != null || forwardedFileId == null) {
                return TelegramResponse(
                    ok = false,
                    result = null,
                    description = "message not found",
                    errorCode = forwardErrorCode ?: 400,
                    parameters = null,
                )
            }
            val doc = com.ulap.data.remote.TelegramDocument(
                fileId = forwardedFileId,
                fileSize = null,
                fileName = "file.jpg",
                mimeType = "image/jpeg",
                thumbnail = null,
            )
            val msg = com.ulap.data.remote.TelegramMessage(
                messageId = forwardedMsgId,
                document = doc,
                video = null,
                photo = null,
                caption = null,
            )
            return TelegramResponse(ok = true, result = msg, description = null, errorCode = null, parameters = null)
        }

        override suspend fun deleteMessage(token: String, chatId: String, messageId: Long) =
            TelegramResponse<Boolean>(ok = true, result = true, description = null, errorCode = null, parameters = null)

        override suspend fun getMe(token: String) = throw NotImplementedError()
        override suspend fun sendPhoto(token: String, chatId: RequestBody, photo: MultipartBody.Part, caption: RequestBody?) = throw NotImplementedError()
        override suspend fun sendPhotoFromUrl(token: String, chatId: RequestBody, photoUrl: RequestBody, caption: RequestBody?) = throw NotImplementedError()
        override suspend fun sendVideo(token: String, chatId: RequestBody, video: MultipartBody.Part, caption: RequestBody?, supportsStreaming: RequestBody?) = throw NotImplementedError()
        override suspend fun sendDocument(token: String, chatId: RequestBody, document: MultipartBody.Part, caption: RequestBody?, thumbnail: MultipartBody.Part?) = throw NotImplementedError()
        override suspend fun getFile(token: String, fileId: String) = throw NotImplementedError()
        override suspend fun sendMessage(token: String, chatId: String, text: String) = throw NotImplementedError()
        override suspend fun deleteMessages(token: String, chatId: String, messageIdsJson: String) = throw NotImplementedError()
        override suspend fun getUpdates(token: String, offset: Int?, limit: Int?) = throw NotImplementedError()
        override suspend fun getChat(token: String, chatId: String) = throw NotImplementedError()
        override suspend fun pinChatMessage(token: String, chatId: String, messageId: Long, disableNotification: Boolean) = throw NotImplementedError()
        override suspend fun getChatMember(token: String, chatId: String, userId: Long) = throw NotImplementedError()
    }

    private class FakeMediaItemDao(initialItems: List<MediaItemEntity>) : MediaItemDao {
        private val items = initialItems.associateBy { it.id }.toMutableMap()
        val updatedItems = mutableMapOf<String, MediaItemEntity>()
        val needsReuploadAnnotations = mutableMapOf<String, String>()

        override suspend fun getBackedUpItemsByBotIndex(botIndex: Int) =
            items.values.filter { it.uploadBotIndex == botIndex && it.backupStatus in setOf(BackupStatus.BACKED_UP, BackupStatus.CLOUD_ONLY) }

        override suspend fun updateRepairResult(id: String, telegramFileId: String, thumbnailFileId: String?, uploadBotIndex: Int) {
            val existing = items[id] ?: return
            val updated = existing.copy(telegramFileId = telegramFileId, thumbnailFileId = thumbnailFileId, uploadBotIndex = uploadBotIndex)
            items[id] = updated
            updatedItems[id] = updated
        }

        override suspend fun markRepairItemNeedsReupload(id: String, reason: String) {
            needsReuploadAnnotations[id] = reason
        }

        // Stub all other methods — only repair-related ones are needed here.
        override suspend fun upsertAll(items: List<MediaItemEntity>) {}
        override suspend fun upsert(item: MediaItemEntity) {}
        override suspend fun update(item: MediaItemEntity) {}
        override suspend fun updateAll(items: List<MediaItemEntity>) {}
        override fun observeByBuckets(buckets: List<String>) = kotlinx.coroutines.flow.flowOf(emptyList<MediaItemEntity>())
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<MediaItemEntity>())
        override fun observeByStatus(status: BackupStatus) = kotlinx.coroutines.flow.flowOf(emptyList<MediaItemEntity>())
        override suspend fun findById(id: String) = items[id]
        override suspend fun countItemsMatchingImportFingerprint(fileName: String, mimeType: String, widthPx: Int?, heightPx: Int?) = 0
        override suspend fun getPendingOrFailed() = emptyList<MediaItemEntity>()
        override suspend fun getPendingOrFailedInBuckets(buckets: List<String>) = emptyList<MediaItemEntity>()
        override suspend fun markExcludedNotOnDevice(ids: List<String>, message: String) {}
        override suspend fun excludeItemsNotInBuckets(enabledBuckets: List<String>) {}
        override suspend fun updateBackupResult(id: String, status: BackupStatus, error: String?, syncedAt: Long?, fileId: String?, messageId: Long?, thumbnailFileId: String?, thumbnailMessageId: Long?, chunkMessageIds: String?, contentHash: String?, uploadBotIndex: Int) {}
        override suspend fun resetFailedToPending() {}
        override suspend fun resetStaleUploadingToPending() {}
        override suspend fun resetItemToPending(id: String) {}
        override suspend fun markOversizedChunkedItemsAsFailed() {}
        override suspend fun markSlowStartChunkedItemsAsFailed() {}
        override suspend fun countBackedUp(bucket: String) = 0
        override suspend fun getModifiedSince(bucket: String, since: Long) = emptyList<MediaItemEntity>()
        override fun countByStatus(status: BackupStatus) = kotlinx.coroutines.flow.flowOf(0)
        override fun sumSizeByStatus(status: BackupStatus) = kotlinx.coroutines.flow.flowOf(0L)
        override fun observeBackupStatsGrouped() = kotlinx.coroutines.flow.flowOf(emptyList<com.ulap.data.local.dao.BackupStatsRow>())
        override fun observeByMediaType(type: MediaType) = kotlinx.coroutines.flow.flowOf(emptyList<MediaItemEntity>())
        override suspend fun getAllBackedUp() = emptyList<MediaItemEntity>()
        override suspend fun markAsCloudOnly(ids: List<String>) {}
        override suspend fun getAllIndexedItems() = emptyList<MediaItemEntity>()
        override suspend fun getAllCloudOnlyItems() = emptyList<MediaItemEntity>()
        override suspend fun findByFileNameSizeDate(fileName: String, size: Long, dateTaken: Long) = null
        override suspend fun findByContentHash(hash: String) = null
        override suspend fun findBackedUpByImportFingerprint(fileName: String, mimeType: String, widthPx: Int?, heightPx: Int?, excludeId: String): MediaItemEntity? = null
        override suspend fun findByTelegramFileId(fileId: String) = null
        override suspend fun updateUploadBotIndexByFileId(telegramFileId: String, uploadBotIndex: Int) {}
        override suspend fun findByIds(ids: List<String>) = emptyList<MediaItemEntity>()
        override suspend fun findExistingTelegramFileIds(fileIds: List<String>) = emptyList<String>()
        override suspend fun findExistingIds(ids: List<String>) = emptyList<String>()
        override suspend fun saveChunkProgress(id: String, chunks: String, count: Int) {}
        override suspend fun clearChunkProgress(id: String) {}
        override suspend fun clearOrphanedChunkProgress() {}
        override suspend fun getAllBackupMessageIds() = emptyList<Long>()
        override suspend fun getAllThumbnailMessageIds() = emptyList<Long>()
        override suspend fun getAllChunkMessageIdsJson() = emptyList<String>()
        override suspend fun resetBackedUpToPending() {}
        override suspend fun deleteCloudOnlyItems() {}
        override suspend fun markCorruptChunkedItemsForReupload() = 0
        override fun observeCorruptChunkedBackupCount() = kotlinx.coroutines.flow.flowOf(0)
        override suspend fun getCorruptChunkedBackedUpItems() = emptyList<MediaItemEntity>()
        override suspend fun remapBotIndices(bannedPrimaryIndex: Int, promotedAltIndex: Int, affectedIndices: List<Int>) {}
    }

    private class FakeChunkMetadataDao(chunks: List<ChunkMetadataEntity> = emptyList()) : ChunkMetadataDao {
        private val chunksByMedia = chunks.groupBy { it.mediaItemId }.toMutableMap()
        val updatedChunks = mutableMapOf<Long, String>() // chunkId -> newFileId

        override suspend fun getChunksForMedia(mediaItemId: String) =
            chunksByMedia[mediaItemId] ?: emptyList()

        override suspend fun updateChunkFileId(id: Long, newFileId: String) {
            updatedChunks[id] = newFileId
        }

        override suspend fun insertChunk(chunk: ChunkMetadataEntity) {}
        override suspend fun getUploadedCount(mediaItemId: String) = 0
        override suspend fun getAllFileIdsForMedia(mediaItemId: String) = emptyList<String>()
        override suspend fun getAllMessageIdsForMedia(mediaItemId: String) = emptyList<Long>()
        override suspend fun getChunkAtByteOffset(mediaItemId: String, byteOffset: Long) = null
        override suspend fun deleteChunksForMedia(mediaItemId: String) {}
        override suspend fun hasChunks(mediaItemId: String) = if (chunksByMedia[mediaItemId]?.isNotEmpty() == true) 1 else 0
    }
}
