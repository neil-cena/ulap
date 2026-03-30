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
 * cooling down after a 429 rate-limit response or permanently banned. If every eligible bot
 * is cooling down the one with the soonest expiry is returned so uploads can resume as quickly
 * as possible. Returns null when all bots are permanently banned.
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

    /** Set of bot indices that are permanently banned and must never be selected for upload. */
    private val permanentBans = ConcurrentHashMap.newKeySet<Int>()

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
     * Permanently banned bots and bots still within their rate-limit cooldown window are skipped.
     * If all eligible (non-banned) bots are cooling down the one with the soonest recovery time
     * is returned so the upload pipeline can proceed with minimal extra delay.
     *
     * Returns null when no primary bot is configured or when ALL bots are permanently banned.
     */
    fun selectForUpload(): BotCredential? {
        val bots = allBots()
        if (bots.isEmpty()) return null

        val eligible = bots.filter { !permanentBans.contains(it.index) }
        if (eligible.isEmpty()) return null

        val now = System.currentTimeMillis()
        val startPos = roundRobinCounter.getAndIncrement() % eligible.size

        for (offset in 0 until eligible.size) {
            val bot = eligible[(startPos + offset) % eligible.size]
            val expiry = cooldowns[bot.index]
            if (expiry == null || now >= expiry) {
                return bot
            }
        }

        // Every eligible bot is rate-limited — return the one that recovers soonest.
        return eligible.minByOrNull { cooldowns[it.index] ?: 0L }
    }

    /**
     * Selects the next available bot for an upload, excluding bots whose indices are in
     * [excludeIndices]. Also skips permanently banned bots and bots in cooldown.
     *
     * Returns null if no eligible bot exists after applying all filters.
     */
    fun selectForUploadExcluding(excludeIndices: Set<Int>): BotCredential? {
        val bots = allBots()
        if (bots.isEmpty()) return null

        val eligible = bots.filter { !permanentBans.contains(it.index) && !excludeIndices.contains(it.index) }
        if (eligible.isEmpty()) return null

        val now = System.currentTimeMillis()
        val startPos = roundRobinCounter.getAndIncrement() % eligible.size

        for (offset in 0 until eligible.size) {
            val bot = eligible[(startPos + offset) % eligible.size]
            val expiry = cooldowns[bot.index]
            if (expiry == null || now >= expiry) {
                return bot
            }
        }

        return null
    }

    /**
     * Records a rate-limit penalty for [botIndex]. That bot will be skipped by [selectForUpload]
     * for [retryAfterMs] milliseconds.
     */
    fun markRateLimited(botIndex: Int, retryAfterMs: Long) {
        cooldowns[botIndex] = System.currentTimeMillis() + retryAfterMs
    }

    /**
     * Permanently bans [botIndex] from being selected for uploads. This is tracked separately
     * from temporary cooldowns and persists until [clearCooldowns] is called.
     */
    fun markPermanentlyBanned(botIndex: Int) {
        permanentBans.add(botIndex)
    }

    /** Returns true if [botIndex] has been permanently banned. */
    fun isPermanentlyBanned(botIndex: Int): Boolean = permanentBans.contains(botIndex)

    /** Returns true only when every bot in [allBots] is permanently banned. */
    fun isAllPermanentlyBanned(): Boolean {
        val bots = allBots()
        if (bots.isEmpty()) return false
        return bots.all { permanentBans.contains(it.index) }
    }

    /**
     * Returns true only when ALL bots have a future cooldown expiry AND none are permanently
     * banned. If any bot is permanently banned this returns false.
     */
    fun isAllBotsTemporarilyCooledDown(): Boolean {
        val bots = allBots()
        if (bots.isEmpty()) return false
        if (bots.any { permanentBans.contains(it.index) }) return false
        val now = System.currentTimeMillis()
        return bots.all { bot ->
            val expiry = cooldowns[bot.index]
            expiry != null && expiry > now
        }
    }

    /**
     * Returns the maximum cooldown expiry timestamp among bots that have a future expiry and
     * are NOT permanently banned. Returns 0L when no such bot exists.
     */
    fun maxTempCooldownExpiryMs(): Long {
        val now = System.currentTimeMillis()
        return allBots()
            .filter { !permanentBans.contains(it.index) }
            .mapNotNull { bot -> cooldowns[bot.index]?.takeIf { it > now } }
            .maxOrNull() ?: 0L
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

    /** Clears all rate-limit cooldowns and permanent bans. Useful after credentials change. */
    fun clearCooldowns() {
        cooldowns.clear()
        permanentBans.clear()
    }
}
