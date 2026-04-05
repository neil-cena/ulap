package com.ulap.domain.usecase

import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.remote.BackupIndexManager
import com.ulap.data.remote.BotPool
import com.ulap.data.remote.BotRepairManager
import com.ulap.domain.health.BotHealthMonitor
import com.ulap.domain.health.BotHealthStatus
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import javax.inject.Inject

/**
 * Promotes the first healthy secondary bot to become the new primary bot.
 *
 * Executed when the current primary bot is detected as permanently banned.
 *
 * Steps:
 * 1. Identify the first healthy alt bot via [BotHealthMonitor.healthState].
 * 2. Swap credentials: the alt's token becomes the primary token; remove the promoted alt
 *    from the additional bots list; re-index the remaining alts to fill the gap.
 * 3. Remap all `MediaItemEntity.uploadBotIndex` values in the DB so that:
 *    - The promoted alt's items → 0 (new primary)
 *    - The banned primary's items → -1 (sentinel: needs repair)
 *    - Items of bots with higher indices → decremented by 1 (index compaction)
 * 4. Clear BotPool in-memory state (cooldowns + bans).
 * 5. Return the new primary [BotCredential] for the caller to trigger repair/index re-export.
 *
 * Returns `null` if promotion is not possible (no healthy alts or no alts at all).
 */
class PromoteBotUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val botPool: BotPool,
    private val mediaItemDao: MediaItemDao,
    private val botHealthMonitor: BotHealthMonitor,
) {
    suspend operator fun invoke(): BotCredential? {
        val chatId = credentialRepository.getChatId() ?: return null
        val additionalBots = credentialRepository.getAdditionalBotTokens()
        if (additionalBots.isEmpty()) return null

        val healthyAlt = pickHealthyAlt(additionalBots) ?: return null

        val bannedPrimaryIndex = 0
        val promotedAltIndex = healthyAlt.index

        // Build the new additional bots list:
        //  - Remove the promoted alt.
        //  - Compaction: bots with index > promotedAltIndex are decremented.
        val remaining = additionalBots
            .filter { it.index != promotedAltIndex }
            .map { bot ->
                if (bot.index > promotedAltIndex) bot.copy(index = bot.index - 1) else bot
            }
        credentialRepository.saveCredentials(healthyAlt.token, chatId)
        credentialRepository.saveAdditionalBotTokens(remaining)

        // Remap DB indices: include all indices that could be affected.
        val allCurrentIndices = (listOf(0) + additionalBots.map { it.index }).distinct()
        if (allCurrentIndices.isNotEmpty()) {
            mediaItemDao.remapBotIndices(
                bannedPrimaryIndex = bannedPrimaryIndex,
                promotedAltIndex = promotedAltIndex,
                affectedIndices = allCurrentIndices,
            )
        }

        // Clear pool state so the new primary is used immediately.
        botPool.clearCooldowns()

        return BotCredential(index = 0, token = healthyAlt.token, label = healthyAlt.label)
    }

    private fun pickHealthyAlt(alts: List<BotCredential>): BotCredential? {
        val health = botHealthMonitor.healthState.value
        return alts
            .sortedBy { it.index }
            .firstOrNull { bot ->
                health[bot.index] == BotHealthStatus.HEALTHY ||
                    health[bot.index] == BotHealthStatus.UNKNOWN // not yet checked; give benefit of doubt
            }
    }
}
