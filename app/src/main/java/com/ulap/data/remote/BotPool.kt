package com.ulap.data.remote

import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages a pool of Telegram bot credentials for upload distribution and download routing.
 *
 * Upload selection: round-robin across all configured bots, skipping any that are currently
 * cooling down after a 429 rate-limit response. If every bot is cooling down the one with
 * the soonest expiry is returned so uploads can resume as quickly as possible.
 *
 * Download routing: a [com.ulap.data.local.entity.MediaItemEntity.uploadBotIndex] of 0
 * always maps to the primary bot; additional bots are looked up by their stored index.
 * If the stored index is no longer in the pool (bot was removed), the primary bot is used
 * as a fallback so existing downloads keep working.
 */
@Singleton
class BotPool @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {
    private val roundRobinCounter = AtomicInteger(0)

    /** Maps bot index → epoch-ms timestamp after which the bot may be used again. */
    private val cooldowns = ConcurrentHashMap<Int, Long>()

    /**
     * Returns all configured bots: index 0 = primary, followed by additional bots in order.
     * Returns an empty list when no primary token is configured.
     */
    fun allBots(): List<BotCredential> {
        val primaryToken = credentialRepository.getBotToken() ?: return emptyList()
        val primary = BotCredential(index = 0, token = primaryToken)
        return listOf(primary) + credentialRepository.getAdditionalBotTokens()
    }

    /**
     * Selects the next available bot for an upload using round-robin order.
     *
     * Bots that are still within their rate-limit cooldown window are skipped.
     * If all bots are cooling down the one with the soonest recovery time is returned
     * so the upload pipeline can proceed with minimal extra delay.
     *
     * Returns null only when no primary bot is configured.
     */
    fun selectForUpload(): BotCredential? {
        val bots = allBots()
        if (bots.isEmpty()) return null

        val now = System.currentTimeMillis()
        val startPos = roundRobinCounter.getAndIncrement() % bots.size

        for (offset in 0 until bots.size) {
            val bot = bots[(startPos + offset) % bots.size]
            val expiry = cooldowns[bot.index]
            if (expiry == null || now >= expiry) {
                return bot
            }
        }

        // Every bot is rate-limited — return the one that recovers soonest.
        return bots.minByOrNull { cooldowns[it.index] ?: 0L }
    }

    /**
     * Records a rate-limit penalty for [botIndex]. That bot will be skipped by [selectForUpload]
     * for [retryAfterMs] milliseconds.
     */
    fun markRateLimited(botIndex: Int, retryAfterMs: Long) {
        cooldowns[botIndex] = System.currentTimeMillis() + retryAfterMs
    }

    /**
     * Returns the bot that performed the upload for a specific item, identified by [botIndex].
     * Falls back to the primary bot (index 0) if [botIndex] is not found — this keeps
     * downloads working even after a secondary bot is removed from the pool.
     */
    fun getForDownload(botIndex: Int): BotCredential? {
        val bots = allBots()
        return bots.find { it.index == botIndex } ?: bots.firstOrNull()
    }

    /** Clears all rate-limit cooldowns. Useful after credentials change. */
    fun clearCooldowns() {
        cooldowns.clear()
    }
}
