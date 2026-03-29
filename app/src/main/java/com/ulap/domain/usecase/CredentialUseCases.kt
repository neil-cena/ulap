package com.ulap.domain.usecase

import com.ulap.data.remote.BotPool
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.sanitizeTokenForPath
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import javax.inject.Inject

sealed class VerifyResult {
    data class Success(val botName: String) : VerifyResult()
    data class Error(val message: String) : VerifyResult()
}

class VerifyBotCredentialsUseCase @Inject constructor(
    private val api: TelegramBotApi,
) {
    suspend operator fun invoke(token: String): VerifyResult {
        return try {
            val response = api.getMe(sanitizeTokenForPath(token))
            if (response.ok && response.result != null) {
                VerifyResult.Success(response.result.firstName)
            } else {
                VerifyResult.Error(response.description ?: "Unknown error")
            }
        } catch (e: Exception) {
            VerifyResult.Error(e.message ?: "Connection failed")
        }
    }
}

class SaveCredentialsUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {
    operator fun invoke(token: String, chatId: String) =
        credentialRepository.saveCredentials(token, chatId)
}

class GetCredentialsUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {
    fun hasCredentials() = credentialRepository.hasCredentials()
    fun getToken() = credentialRepository.getBotToken()
    fun getChatId() = credentialRepository.getChatId()
    fun getLastIndexFileId() = credentialRepository.getLastIndexFileId()
    fun getTokenForBot(index: Int): String? {
        if (index < 0) return null
        if (index == 0) return credentialRepository.getBotToken()
        val additionalBots = credentialRepository.getAdditionalBotTokens()
        return additionalBots.find { it.index == index }?.token
    }
}

class ClearCredentialsUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {
    operator fun invoke() = credentialRepository.clearCredentials()
}

/** Returns the full bot pool: primary (index 0) followed by additional bots. */
class GetBotPoolUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {
    operator fun invoke(): List<BotCredential> {
        val primaryToken = credentialRepository.getBotToken() ?: return emptyList()
        val primary = BotCredential(index = 0, token = primaryToken)
        return listOf(primary) + credentialRepository.getAdditionalBotTokens()
    }
}

/**
 * Verifies a new bot token and, on success, appends it to the additional-bots list.
 * The token must not already be the primary bot token.
 */
class AddSecondaryBotUseCase @Inject constructor(
    private val api: TelegramBotApi,
    private val credentialRepository: CredentialRepository,
    private val botPool: BotPool,
) {
    suspend operator fun invoke(token: String, label: String): VerifyResult {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return VerifyResult.Error("Token cannot be empty")
        if (trimmed == credentialRepository.getBotToken()) {
            return VerifyResult.Error("This is already the primary bot")
        }
        val verifyResult = try {
            val response = api.getMe(sanitizeTokenForPath(trimmed))
            if (response.ok && response.result != null) {
                VerifyResult.Success(response.result.firstName)
            } else {
                VerifyResult.Error(response.description ?: "Unknown error")
            }
        } catch (e: Exception) {
            VerifyResult.Error(e.message ?: "Connection failed")
        }
        if (verifyResult is VerifyResult.Success) {
            val current = credentialRepository.getAdditionalBotTokens().toMutableList()
            // Deduplicate: replace existing entry for the same token rather than adding a duplicate.
            val existingIdx = current.indexOfFirst { it.token == trimmed }
            val newEntry = BotCredential(
                index = if (existingIdx >= 0) current[existingIdx].index else current.size + 1,
                token = trimmed,
                label = label.trim(),
            )
            if (existingIdx >= 0) current[existingIdx] = newEntry else current.add(newEntry)
            credentialRepository.saveAdditionalBotTokens(current)
            botPool.clearCooldowns()
        }
        return verifyResult
    }
}

/** Removes a secondary bot by its [BotCredential.index]. Primary bot (index 0) cannot be removed. */
class RemoveSecondaryBotUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val botPool: BotPool,
) {
    operator fun invoke(botIndex: Int) {
        if (botIndex == 0) return
        val current = credentialRepository.getAdditionalBotTokens()
            .filter { it.index != botIndex }
        credentialRepository.saveAdditionalBotTokens(current)
        botPool.clearCooldowns()
    }
}
