package com.ulap.domain.usecase

import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import com.ulap.data.remote.BotBanStore
import com.ulap.data.remote.BotPool
import com.ulap.domain.health.BotHealthMonitor
import com.ulap.domain.health.BotHealthStatus
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PromoteBotUseCaseTest {

    @Test
    fun invoke_promotesFirstHealthyAlt_whenPrimaryIsBanned() = runTest {
        val repo = FakeCredentialRepo(
            primaryToken = "primary:token",
            chatId = "chat123",
            additionals = listOf(
                BotCredential(1, "alt1:token", "Alt 1"),
                BotCredential(2, "alt2:token", "Alt 2"),
            ),
        )
        val dao = FakeMediaItemDao(
            listOf(
                makeItem("a", uploadBotIndex = 0),
                makeItem("b", uploadBotIndex = 1),
            ),
        )
        val health = MutableStateFlow(
            mapOf(
                0 to BotHealthStatus.BANNED,
                1 to BotHealthStatus.HEALTHY,
                2 to BotHealthStatus.HEALTHY,
            )
        )
        val useCase = PromoteBotUseCase(repo, buildPool(repo), dao, fakeMonitor(health))

        val result = useCase()

        assertNotNull(result)
        assertEquals(0, result!!.index)
        assertEquals("alt1:token", result.token)
        // Primary token should now be alt1's token
        assertEquals("alt1:token", repo.getBotToken())
        // DB remapping should have been called
        assertNotNull(dao.lastRemap)
        assertEquals(0, dao.lastRemap!!.bannedPrimaryIndex)
        assertEquals(1, dao.lastRemap!!.promotedAltIndex)
    }

    @Test
    fun invoke_returnsNull_whenNoAltsExist() = runTest {
        val repo = FakeCredentialRepo("primary:token", "chat123", emptyList())
        val dao = FakeMediaItemDao(emptyList())
        val useCase = PromoteBotUseCase(
            repo, buildPool(repo), dao,
            fakeMonitor(MutableStateFlow(mapOf(0 to BotHealthStatus.BANNED))),
        )

        assertNull(useCase())
    }

    @Test
    fun invoke_returnsNull_whenAllAltsAreBanned() = runTest {
        val repo = FakeCredentialRepo(
            "primary:token", "chat123",
            listOf(BotCredential(1, "alt:token", "Alt")),
        )
        val dao = FakeMediaItemDao(emptyList())
        val health = MutableStateFlow(mapOf(0 to BotHealthStatus.BANNED, 1 to BotHealthStatus.BANNED))
        val useCase = PromoteBotUseCase(repo, buildPool(repo), dao, fakeMonitor(health))

        assertNull(useCase())
    }

    @Test
    fun invoke_compactsRemainingAltIndices_afterPromotion() = runTest {
        val repo = FakeCredentialRepo(
            "primary:token", "chat123",
            listOf(
                BotCredential(1, "alt1:token", "Alt 1"),
                BotCredential(2, "alt2:token", "Alt 2"),
                BotCredential(3, "alt3:token", "Alt 3"),
            ),
        )
        val dao = FakeMediaItemDao(emptyList())
        val health = MutableStateFlow(
            mapOf(
                0 to BotHealthStatus.BANNED,
                1 to BotHealthStatus.HEALTHY,
                2 to BotHealthStatus.HEALTHY,
                3 to BotHealthStatus.HEALTHY,
            )
        )
        val useCase = PromoteBotUseCase(repo, buildPool(repo), dao, fakeMonitor(health))

        useCase()

        // Remaining alts after promoting index 1: alt2 (was 2 → 1), alt3 (was 3 → 2)
        val remaining = repo.getAdditionalBotTokens()
        assertEquals(2, remaining.size)
        assertEquals(1, remaining[0].index)
        assertEquals("alt2:token", remaining[0].token)
        assertEquals(2, remaining[1].index)
        assertEquals("alt3:token", remaining[1].token)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeItem(id: String, uploadBotIndex: Int) = MediaItemEntity(
        id = id, path = "/tmp/$id", contentUri = "", fileName = "$id.jpg", mimeType = "image/jpeg",
        size = 100L, dateModified = 0L, dateTaken = 0L, bucketName = "Camera",
        mediaType = MediaType.IMAGE, backupStatus = BackupStatus.BACKED_UP,
        telegramFileId = "fid_$id", telegramMessageId = 1L, uploadBotIndex = uploadBotIndex,
    )

    private fun buildPool(repo: CredentialRepository) = BotPool(repo, BotBanStore.noOpForTest())

    private fun fakeMonitor(health: StateFlow<Map<Int, BotHealthStatus>>) =
        object : BotHealthMonitor(
            object : com.ulap.data.remote.TelegramBotApi {
                override suspend fun getMe(token: String) = throw NotImplementedError()
                override suspend fun sendPhoto(token: String, chatId: okhttp3.RequestBody, photo: okhttp3.MultipartBody.Part, caption: okhttp3.RequestBody?) = throw NotImplementedError()
                override suspend fun sendPhotoFromUrl(token: String, chatId: okhttp3.RequestBody, photoUrl: okhttp3.RequestBody, caption: okhttp3.RequestBody?) = throw NotImplementedError()
                override suspend fun sendVideo(token: String, chatId: okhttp3.RequestBody, video: okhttp3.MultipartBody.Part, caption: okhttp3.RequestBody?, supportsStreaming: okhttp3.RequestBody?) = throw NotImplementedError()
                override suspend fun sendDocument(token: String, chatId: okhttp3.RequestBody, document: okhttp3.MultipartBody.Part, caption: okhttp3.RequestBody?, thumbnail: okhttp3.MultipartBody.Part?) = throw NotImplementedError()
                override suspend fun getFile(token: String, fileId: String) = throw NotImplementedError()
                override suspend fun deleteMessage(token: String, chatId: String, messageId: Long) = throw NotImplementedError()
                override suspend fun forwardMessage(token: String, chatId: String, fromChatId: String, messageId: Long) = throw NotImplementedError()
                override suspend fun sendMessage(token: String, chatId: String, text: String) = throw NotImplementedError()
                override suspend fun deleteMessages(token: String, chatId: String, messageIdsJson: String) = throw NotImplementedError()
                override suspend fun getUpdates(token: String, offset: Int?, limit: Int?) = throw NotImplementedError()
                override suspend fun getChat(token: String, chatId: String) = throw NotImplementedError()
                override suspend fun pinChatMessage(token: String, chatId: String, messageId: Long, disableNotification: Boolean) = throw NotImplementedError()
                override suspend fun getChatMember(token: String, chatId: String, userId: Long) = throw NotImplementedError()
            },
            BotPool(object : CredentialRepository {
                override fun getBotToken() = null
                override fun getChatId() = null
                override fun saveCredentials(token: String, chatId: String) {}
                override fun clearCredentials() {}
                override fun hasCredentials() = false
                override fun getLastIndexFileId() = null
                override fun setLastIndexFileId(fileId: String?) {}
                override fun getLastIndexMessageId(): Long? = null
                override fun setLastIndexMessageId(messageId: Long?) {}
                override fun getAdditionalBotTokens() = emptyList<BotCredential>()
                override fun saveAdditionalBotTokens(bots: List<BotCredential>) {}
                override fun clearAdditionalBots() {}
            }, BotBanStore.noOpForTest()),
            BotBanStore.noOpForTest(),
        ) {
            override val healthState: StateFlow<Map<Int, BotHealthStatus>> = health
        }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    data class RemapCall(val bannedPrimaryIndex: Int, val promotedAltIndex: Int, val affectedIndices: List<Int>)

    private class FakeCredentialRepo(
        private var primaryToken: String?,
        private val chatId: String,
        additionals: List<BotCredential>,
    ) : CredentialRepository {
        private var additionals: MutableList<BotCredential> = additionals.toMutableList()

        override fun getBotToken() = primaryToken
        override fun getChatId() = chatId
        override fun saveCredentials(token: String, chatId: String) { primaryToken = token }
        override fun clearCredentials() {}
        override fun hasCredentials() = primaryToken != null
        override fun getLastIndexFileId() = null
        override fun setLastIndexFileId(fileId: String?) {}
        override fun getLastIndexMessageId(): Long? = null
        override fun setLastIndexMessageId(messageId: Long?) {}
        override fun getAdditionalBotTokens(): List<BotCredential> = additionals.toList()
        override fun saveAdditionalBotTokens(bots: List<BotCredential>) { additionals = bots.toMutableList() }
        override fun clearAdditionalBots() { additionals.clear() }
    }

    private class FakeMediaItemDao(items: List<MediaItemEntity>) : MediaItemDao {
        private val store = items.associateBy { it.id }.toMutableMap()
        var lastRemap: RemapCall? = null

        override suspend fun remapBotIndices(bannedPrimaryIndex: Int, promotedAltIndex: Int, affectedIndices: List<Int>) {
            lastRemap = RemapCall(bannedPrimaryIndex, promotedAltIndex, affectedIndices)
        }

        override suspend fun getBackedUpItemsByBotIndex(botIndex: Int) =
            store.values.filter { it.uploadBotIndex == botIndex }

        // Stub all unused methods
        override suspend fun upsertAll(items: List<MediaItemEntity>) {}
        override suspend fun upsert(item: MediaItemEntity) {}
        override suspend fun update(item: MediaItemEntity) {}
        override suspend fun updateAll(items: List<MediaItemEntity>) {}
        override fun observeByBuckets(buckets: List<String>) = kotlinx.coroutines.flow.flowOf(emptyList<MediaItemEntity>())
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<MediaItemEntity>())
        override fun observeByStatus(status: BackupStatus) = kotlinx.coroutines.flow.flowOf(emptyList<MediaItemEntity>())
        override suspend fun findById(id: String) = store[id]
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
        override suspend fun updateRepairResult(id: String, telegramFileId: String, thumbnailFileId: String?, uploadBotIndex: Int) {}
        override suspend fun markRepairItemNeedsReupload(id: String, reason: String) {}
    }
}
