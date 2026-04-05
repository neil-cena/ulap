package com.ulap.data.remote

import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.domain.model.BotCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class RepairPhase { IDLE, RUNNING, COMPLETE, PARTIAL_FAILURE, FAILED }

data class RepairProgress(
    val totalItems: Int = 0,
    val repairedItems: Int = 0,
    val failedItems: Int = 0,
    val needsReuploadItems: Int = 0,
    val currentItemName: String? = null,
    val phase: RepairPhase = RepairPhase.IDLE,
)

/**
 * Re-forward engine that restores accessibility of files uploaded by a banned bot.
 *
 * Telegram `file_id` values are scoped to the uploading bot. When that bot is banned,
 * its `getFile` calls fail. Because all bots are admins in the same channel, a healthy
 * bot can call `forwardMessage` on the original message to produce a new message whose
 * `file_id` is valid for the healthy bot. After updating the DB, the forwarded message
 * is deleted to keep the channel tidy.
 *
 * Scope of repair per item:
 *  - Non-chunked: forward the single message → new `telegramFileId`.
 *  - Chunked: forward each chunk message → new `ChunkMetadataEntity.telegramFileId`.
 *  - Thumbnail: forward the thumbnail message → new `thumbnailFileId`.
 *
 * Items whose original message has been deleted from the channel cannot be repaired this
 * way — they are marked with an error message so the user can see they need re-upload.
 */
@Singleton
class BotRepairManager @Inject constructor(
    private val api: TelegramBotApi,
    private val rateLimiter: TelegramRateLimiter,
    private val mediaItemDao: MediaItemDao,
    private val chunkMetadataDao: ChunkMetadataDao,
) {
    private val _repairProgress = MutableStateFlow(RepairProgress())
    val repairProgress: StateFlow<RepairProgress> = _repairProgress.asStateFlow()

    /**
     * Repairs all backed-up items that were uploaded by [bannedBotIndex] using [healthyBot]
     * to perform the re-forward operations in [chatId].
     *
     * Emits progress updates to [repairProgress] throughout. Suspended; call from a coroutine.
     */
    suspend fun repairItemsForBannedBot(
        bannedBotIndex: Int,
        healthyBot: BotCredential,
        chatId: String,
    ) {
        val items = mediaItemDao.getBackedUpItemsByBotIndex(bannedBotIndex)
        _repairProgress.value = RepairProgress(
            totalItems = items.size,
            phase = RepairPhase.RUNNING,
        )

        for (item in items) {
            _repairProgress.update { it.copy(currentItemName = item.fileName) }
            try {
                repairSingleItem(item, healthyBot, chatId)
                _repairProgress.update { it.copy(repairedItems = it.repairedItems + 1) }
            } catch (e: RepairNeedsReuploadException) {
                _repairProgress.update {
                    it.copy(needsReuploadItems = it.needsReuploadItems + 1)
                }
                // Mark the item with the error reason so the user can see why it needs re-upload.
                // Only set the error message; leave status and file_ids intact.
                mediaItemDao.markRepairItemNeedsReupload(item.id, e.message ?: "Re-upload required")
            } catch (_: Exception) {
                _repairProgress.update { it.copy(failedItems = it.failedItems + 1) }
            }
        }

        val final = _repairProgress.value
        _repairProgress.update {
            it.copy(
                phase = when {
                    final.failedItems == 0 && final.needsReuploadItems == 0 -> RepairPhase.COMPLETE
                    final.repairedItems > 0 -> RepairPhase.PARTIAL_FAILURE
                    else -> RepairPhase.FAILED
                },
                currentItemName = null,
            )
        }
    }

    /** Resets progress state to IDLE. Call before starting a new repair session. */
    fun reset() {
        _repairProgress.value = RepairProgress()
    }

    // ── Internal logic ────────────────────────────────────────────────────────

    private suspend fun repairSingleItem(
        item: MediaItemEntity,
        healthyBot: BotCredential,
        chatId: String,
    ) {
        val healthyToken = sanitizeTokenForPath(healthyBot.token)
        val isChunked = item.telegramFileId?.startsWith(CHUNKED_FILE_ID_PREFIX) == true

        val newTelegramFileId: String
        if (isChunked) {
            repairChunks(item, healthyToken, chatId)
            newTelegramFileId = item.telegramFileId!! // sentinel is preserved; chunk rows updated
        } else {
            val msgId = item.telegramMessageId?.takeIf { it > 0L }
                ?: throw RepairNeedsReuploadException("Message ID missing — re-upload required")
            newTelegramFileId = forwardAndExtractFileId(healthyToken, chatId, msgId)
        }

        val newThumbnailFileId = repairThumbnail(item, healthyToken, chatId)

        mediaItemDao.updateRepairResult(
            id = item.id,
            telegramFileId = newTelegramFileId,
            thumbnailFileId = newThumbnailFileId,
            uploadBotIndex = healthyBot.index,
        )
    }

    private suspend fun repairChunks(
        item: MediaItemEntity,
        healthyToken: String,
        chatId: String,
    ) {
        val chunks = chunkMetadataDao.getChunksForMedia(item.id)
        if (chunks.isEmpty()) {
            throw RepairNeedsReuploadException("Chunk metadata missing — re-upload required")
        }
        for (chunk in chunks) {
            val msgId = chunk.telegramMessageId.takeIf { it > 0L }
                ?: throw RepairNeedsReuploadException("Chunk message ID missing for part ${chunk.chunkIndex} — re-upload required")
            val newFileId = forwardAndExtractFileId(healthyToken, chatId, msgId)
            chunkMetadataDao.updateChunkFileId(chunk.id, newFileId)
        }
    }

    private suspend fun repairThumbnail(
        item: MediaItemEntity,
        healthyToken: String,
        chatId: String,
    ): String? {
        val thumbMsgId = item.thumbnailMessageId?.takeIf { it > 0L } ?: return item.thumbnailFileId
        return try {
            forwardAndExtractFileId(healthyToken, chatId, thumbMsgId)
        } catch (_: Exception) {
            // Thumbnail repair failure is non-fatal; keep existing (possibly stale) value.
            item.thumbnailFileId
        }
    }

    /**
     * Forwards message [messageId] to the same [chatId] using [healthyToken], extracts the
     * document's `file_id` from the newly forwarded message, then deletes the forwarded
     * message to keep the channel clean.
     *
     * Throws [RepairNeedsReuploadException] if the original message is no longer in the chat
     * (Telegram returns a "message not found" error).
     */
    private suspend fun forwardAndExtractFileId(
        healthyToken: String,
        chatId: String,
        messageId: Long,
    ): String {
        val forwardResp = rateLimiter.withRateLimit {
            api.forwardMessage(
                token = healthyToken,
                chatId = chatId,
                fromChatId = chatId,
                messageId = messageId,
            )
        }

        if (!forwardResp.ok || forwardResp.result == null) {
            val desc = forwardResp.description ?: ""
            if (desc.contains("message not found", ignoreCase = true) ||
                forwardResp.errorCode == 400
            ) {
                throw RepairNeedsReuploadException("Original message $messageId not found — re-upload required")
            }
            throw Exception("forwardMessage failed: $desc (code ${forwardResp.errorCode})")
        }

        val forwardedMsg = forwardResp.result
        val newFileId = forwardedMsg.document?.fileId
            ?: forwardedMsg.video?.fileId
            ?: throw RepairNeedsReuploadException("Forwarded message $messageId has no document — re-upload required")

        // Best-effort cleanup of the forwarded message.
        try {
            rateLimiter.withRateLimit {
                api.deleteMessage(healthyToken, chatId, forwardedMsg.messageId)
            }
        } catch (_: Exception) { /* non-fatal */ }

        return newFileId
    }
}

/** Thrown when an item cannot be repaired via re-forward and must be re-uploaded instead. */
class RepairNeedsReuploadException(message: String) : Exception(message)
