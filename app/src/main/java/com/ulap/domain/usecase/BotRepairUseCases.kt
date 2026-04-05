package com.ulap.domain.usecase

import com.ulap.data.remote.BackupIndexManager
import com.ulap.data.remote.BotPool
import com.ulap.data.remote.BotRepairManager
import com.ulap.data.remote.RepairProgress
import com.ulap.domain.health.BotHealthMonitor
import com.ulap.domain.health.BotHealthStatus
import com.ulap.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Triggers a fresh [BotHealthMonitor.checkAll] pass and awaits completion. */
class CheckBotHealthUseCase @Inject constructor(
    private val botHealthMonitor: BotHealthMonitor,
) {
    suspend operator fun invoke() = botHealthMonitor.checkAll()
}

/** Fires an async health check that does not block the caller. */
class RefreshBotHealthAsyncUseCase @Inject constructor(
    private val botHealthMonitor: BotHealthMonitor,
) {
    operator fun invoke() = botHealthMonitor.checkAllAsync()
}

/** Exposes the [BotHealthMonitor.healthState] flow for UI observation. */
class GetBotHealthStateUseCase @Inject constructor(
    private val botHealthMonitor: BotHealthMonitor,
) {
    operator fun invoke(): StateFlow<Map<Int, BotHealthStatus>> = botHealthMonitor.healthState
}

/**
 * Orchestrates the full repair flow for a banned secondary (non-primary) bot:
 *
 * 1. Picks the best healthy bot for re-forwarding.
 * 2. Runs [BotRepairManager.repairItemsForBannedBot] for the banned bot.
 * 3. Re-exports the backup index so other devices see the updated `uploadBotIndex` values.
 *
 * Returns a [RepairResult] summarising the outcome.
 */
class RepairBannedBotFilesUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val botPool: BotPool,
    private val botHealthMonitor: BotHealthMonitor,
    private val botRepairManager: BotRepairManager,
    private val backupIndexManager: BackupIndexManager,
) {
    val repairProgress: StateFlow<RepairProgress> get() = botRepairManager.repairProgress

    suspend operator fun invoke(bannedBotIndex: Int): RepairResult {
        val token = credentialRepository.getBotToken()
            ?: return RepairResult.NoCredentials
        val chatId = credentialRepository.getChatId()
            ?: return RepairResult.NoCredentials

        val health = botHealthMonitor.healthState.value
        val healthyBot = botPool.allBots()
            .filter { it.index != bannedBotIndex }
            .sortedBy { it.index }
            .firstOrNull { health[it.index] != BotHealthStatus.BANNED }
            ?: return RepairResult.NoHealthyBot

        botRepairManager.reset()
        botRepairManager.repairItemsForBannedBot(
            bannedBotIndex = bannedBotIndex,
            healthyBot = healthyBot,
            chatId = chatId,
        )

        // Re-export so other devices see updated uploadBotIndex values.
        backupIndexManager.exportAndUpload(token, chatId)

        val progress = botRepairManager.repairProgress.value
        return RepairResult.Done(
            repairedCount = progress.repairedItems,
            failedCount = progress.failedItems,
            needsReuploadCount = progress.needsReuploadItems,
        )
    }
}

/**
 * Full flow when the primary bot is banned:
 * 1. Promotes the best healthy alt to primary ([PromoteBotUseCase]).
 * 2. Runs [BotRepairManager] for items previously owned by the banned primary (index -1 sentinel).
 * 3. Re-exports the backup index with the new primary token.
 */
class HandlePrimaryBotBannedUseCase @Inject constructor(
    private val promoteBotUseCase: PromoteBotUseCase,
    private val credentialRepository: CredentialRepository,
    private val botPool: BotPool,
    private val botHealthMonitor: BotHealthMonitor,
    private val botRepairManager: BotRepairManager,
    private val backupIndexManager: BackupIndexManager,
) {
    val repairProgress: StateFlow<RepairProgress> get() = botRepairManager.repairProgress

    suspend operator fun invoke(): HandlePrimaryBanResult {
        // Promote first — this also compacts indices and saves new primary credentials.
        val newPrimary = promoteBotUseCase() ?: return HandlePrimaryBanResult.NoHealthyAlt

        val chatId = credentialRepository.getChatId()
            ?: return HandlePrimaryBanResult.NoCredentials

        // Items that belonged to the banned primary got sentinel index -1.
        botRepairManager.reset()
        botRepairManager.repairItemsForBannedBot(
            bannedBotIndex = -1,
            healthyBot = newPrimary,
            chatId = chatId,
        )

        backupIndexManager.exportAndUpload(newPrimary.token, chatId)

        val progress = botRepairManager.repairProgress.value
        return HandlePrimaryBanResult.Done(
            newPrimaryToken = newPrimary.token,
            repairedCount = progress.repairedItems,
            failedCount = progress.failedItems,
            needsReuploadCount = progress.needsReuploadItems,
        )
    }
}

sealed class RepairResult {
    object NoCredentials : RepairResult()
    object NoHealthyBot : RepairResult()
    data class Done(
        val repairedCount: Int,
        val failedCount: Int,
        val needsReuploadCount: Int,
    ) : RepairResult()
}

sealed class HandlePrimaryBanResult {
    object NoCredentials : HandlePrimaryBanResult()
    object NoHealthyAlt : HandlePrimaryBanResult()
    data class Done(
        val newPrimaryToken: String,
        val repairedCount: Int,
        val failedCount: Int,
        val needsReuploadCount: Int,
    ) : HandlePrimaryBanResult()
}
