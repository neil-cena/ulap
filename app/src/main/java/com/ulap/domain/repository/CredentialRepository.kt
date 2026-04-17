package com.ulap.domain.repository

import com.ulap.domain.model.BotCredential

interface CredentialRepository {
    fun getBotToken(): String?
    fun getChatId(): String?
    fun saveCredentials(token: String, chatId: String)
    fun clearCredentials()
    fun hasCredentials(): Boolean

    /** Last uploaded backup index document file_id (for "Sync from other device" fallback). */
    fun getLastIndexFileId(): String?
    fun setLastIndexFileId(fileId: String?)

    /** Telegram message_id of the last uploaded index document (used to re-pin a stale pin). */
    fun getLastIndexMessageId(): Long?
    fun setLastIndexMessageId(messageId: Long?)

    /** Returns additional (non-primary) bot credentials, ordered by their assigned index. */
    fun getAdditionalBotTokens(): List<BotCredential>

    /** Persists the list of additional bots, overwriting any previously saved list. */
    fun saveAdditionalBotTokens(bots: List<BotCredential>)

    /** Removes all additional bots, leaving only the primary credential. */
    fun clearAdditionalBots()
}
