package com.ulap.domain.usecase

import com.ulap.data.remote.BotPool
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.sanitizeTokenForPath
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import javax.inject.Inject

sealed class VerifyResult {
    data class Success(val botName: String, val correctedChatId: String? = null) : VerifyResult()
    sealed class Error(open val message: String) : VerifyResult() {
        data class InvalidToken(val detail: String) : Error(detail)
        data class ChatNotFound(val detail: String) : Error(detail)
        data class BotNotAdmin(val detail: String) : Error(detail)
        data class BotKicked(val detail: String) : Error(detail)
        data class Network(val detail: String) : Error(detail)
        data class PrivateChatNotAllowed(val detail: String) : Error(detail)
        data class Unknown(val detail: String) : Error(detail)
    }
}

class VerifyBotCredentialsUseCase @Inject constructor(
    private val api: TelegramBotApi,
) {
    suspend operator fun invoke(token: String, chatId: String): VerifyResult {
        val sanitized = sanitizeTokenForPath(token)

        // Step 1 — verify token
        val meResponse = try {
            api.getMe(sanitized)
        } catch (e: retrofit2.HttpException) {
            return VerifyResult.Error.InvalidToken("Bad token (HTTP ${e.code()}): ${e.message()}")
        } catch (e: Exception) {
            return VerifyResult.Error.Network(e.message ?: "Connection failed")
        }
        if (!meResponse.ok || meResponse.result == null) {
            return VerifyResult.Error.InvalidToken(meResponse.description ?: "Invalid bot token")
        }
        val botId = meResponse.result.id
        val botName = meResponse.result.firstName

        // Step 2 — verify chat access (with one migrate_to_chat_id retry)
        when (val chatAccess = verifyChatAccess(sanitized, chatId)) {
            is ChatAccessResult.Ok -> {
                val chatResponse = chatAccess.info
                val effectiveChatId = chatAccess.chatId

                val chatType = chatResponse.type
                if (chatType == "private") {
                    return VerifyResult.Error.PrivateChatNotAllowed(
                        "Private chats with the bot are not supported. Create a group, add your bot as admin, and use the group's chat ID."
                    )
                }
                if (chatType == null) {
                    // Unknown type — skip admin check, allow
                    return VerifyResult.Success(botName = botName, correctedChatId = if (effectiveChatId != chatId) effectiveChatId else null)
                }

                // Step 3 — verify bot is admin
                val memberResponse = try {
                    api.getChatMember(sanitized, effectiveChatId, botId)
                } catch (e: Exception) {
                    return VerifyResult.Error.Network(e.message ?: "Connection failed while checking member status")
                }
                if (!memberResponse.ok || memberResponse.result == null) {
                    return VerifyResult.Error.Unknown(memberResponse.description ?: "Could not check member status")
                }
                return when (memberResponse.result.status) {
                    "creator", "administrator" ->
                        VerifyResult.Success(botName = botName, correctedChatId = if (effectiveChatId != chatId) effectiveChatId else null)
                    "member", "restricted" ->
                        VerifyResult.Error.BotNotAdmin(
                            "Bot is a regular member. Promote it to admin: open the chat → title → Members → find bot → Promote."
                        )
                    else ->
                        VerifyResult.Error.BotKicked("Bot has been removed from this chat. Re-add it.")
                }
            }
            is ChatAccessResult.NotFound ->
                return VerifyResult.Error.ChatNotFound(
                    "Bot can't see that chat. Add the bot, confirm the id with @RawDataBot. Supergroups start with -100."
                )
            is ChatAccessResult.NetworkError ->
                return VerifyResult.Error.Network(chatAccess.message)
        }
        // Unreachable — all ChatAccessResult branches return above
        @Suppress("UNREACHABLE_CODE")
        return VerifyResult.Error.Unknown("Unexpected state")
    }

    private sealed interface ChatAccessResult {
        data class Ok(val info: com.ulap.data.remote.TelegramChatInfo, val chatId: String) : ChatAccessResult
        object NotFound : ChatAccessResult
        data class NetworkError(val message: String) : ChatAccessResult
    }

    /** Returns Ok, NotFound, or NetworkError; never throws. */
    private suspend fun verifyChatAccess(
        sanitizedToken: String,
        chatId: String,
    ): ChatAccessResult {
        val firstResponse = try {
            api.getChat(sanitizedToken, chatId)
        } catch (e: Exception) {
            return ChatAccessResult.NetworkError(e.message ?: "Network error")
        }
        if (firstResponse.ok && firstResponse.result != null) {
            return ChatAccessResult.Ok(firstResponse.result, chatId)
        }
        // Check for supergroup migration hint
        val migratedId = firstResponse.parameters?.migrateToChatId
            ?: return ChatAccessResult.NotFound
        val newChatId = migratedId.toString()
        val secondResponse = try {
            api.getChat(sanitizedToken, newChatId)
        } catch (e: Exception) {
            return ChatAccessResult.NetworkError(e.message ?: "Network error during migration retry")
        }
        return if (secondResponse.ok && secondResponse.result != null) {
            ChatAccessResult.Ok(secondResponse.result, newChatId)
        } else ChatAccessResult.NotFound
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
 * Also verifies chat access and admin status against the saved primary chat_id.
 */
class AddSecondaryBotUseCase @Inject constructor(
    private val api: TelegramBotApi,
    private val credentialRepository: CredentialRepository,
    private val botPool: BotPool,
) {
    suspend operator fun invoke(token: String, label: String): VerifyResult {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return VerifyResult.Error.Unknown("Token cannot be empty")
        if (trimmed == credentialRepository.getBotToken()) {
            return VerifyResult.Error.Unknown("This is already the primary bot")
        }
        val chatId = credentialRepository.getChatId() ?: return VerifyResult.Error.Unknown("No chat_id saved — set up the primary bot first")

        val verifyUseCase = VerifyBotCredentialsUseCase(api)
        val verifyResult = verifyUseCase(trimmed, chatId)

        if (verifyResult is VerifyResult.Success) {
            val current = credentialRepository.getAdditionalBotTokens().toMutableList()
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
