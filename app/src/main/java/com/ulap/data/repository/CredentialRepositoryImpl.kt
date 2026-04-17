package com.ulap.data.repository

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_BOT_TOKEN = "bot_token"
private const val KEY_CHAT_ID = "chat_id"
private const val KEY_LAST_INDEX_FILE_ID = "last_index_file_id"
private const val KEY_LAST_INDEX_MESSAGE_ID = "last_index_message_id"
private const val KEY_ADDITIONAL_BOTS = "additional_bots"

/** Wire format stored under [KEY_ADDITIONAL_BOTS]. Index is persisted to survive bot removal
 *  without shifting positions. For legacy entries without a stored index (index == 0),
 *  the list position is used as a fallback on read. */
private data class AdditionalBotEntry(val token: String, val label: String, val index: Int = 0)

@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val encryptedPrefs: SharedPreferences,
) : CredentialRepository {

    private val gson = Gson()

    override fun getBotToken(): String? = encryptedPrefs.getString(KEY_BOT_TOKEN, null)

    override fun getChatId(): String? = encryptedPrefs.getString(KEY_CHAT_ID, null)

    override fun saveCredentials(token: String, chatId: String) {
        encryptedPrefs.edit()
            .putString(KEY_BOT_TOKEN, token.trim())
            .putString(KEY_CHAT_ID, chatId.trim())
            .apply()
    }

    override fun clearCredentials() {
        encryptedPrefs.edit().clear().apply()
    }

    override fun hasCredentials(): Boolean =
        !getBotToken().isNullOrBlank() && !getChatId().isNullOrBlank()

    override fun getLastIndexFileId(): String? = encryptedPrefs.getString(KEY_LAST_INDEX_FILE_ID, null)

    override fun setLastIndexFileId(fileId: String?) {
        encryptedPrefs.edit().putString(KEY_LAST_INDEX_FILE_ID, fileId?.takeIf { it.isNotBlank() }).apply()
    }

    override fun getLastIndexMessageId(): Long? {
        val stored = encryptedPrefs.getLong(KEY_LAST_INDEX_MESSAGE_ID, -1L)
        return if (stored == -1L) null else stored
    }

    override fun setLastIndexMessageId(messageId: Long?) {
        if (messageId == null) {
            encryptedPrefs.edit().remove(KEY_LAST_INDEX_MESSAGE_ID).apply()
        } else {
            encryptedPrefs.edit().putLong(KEY_LAST_INDEX_MESSAGE_ID, messageId).apply()
        }
    }

    override fun getAdditionalBotTokens(): List<BotCredential> {
        val json = encryptedPrefs.getString(KEY_ADDITIONAL_BOTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AdditionalBotEntry>>() {}.type
            val entries: List<AdditionalBotEntry> = gson.fromJson(json, type) ?: emptyList()
            // Index 0 is the primary bot; additional bots are numbered from 1.
            // For legacy entries (index == 0 because the field was absent in JSON), fall back to
            // the list position so existing data keeps working.
            entries.mapIndexed { i, entry ->
                BotCredential(
                    index = if (entry.index > 0) entry.index else i + 1,
                    token = entry.token.trim(),
                    label = entry.label,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun saveAdditionalBotTokens(bots: List<BotCredential>) {
        val entries = bots.map { AdditionalBotEntry(it.token, it.label, it.index) }
        encryptedPrefs.edit().putString(KEY_ADDITIONAL_BOTS, gson.toJson(entries)).apply()
    }

    override fun clearAdditionalBots() {
        encryptedPrefs.edit().remove(KEY_ADDITIONAL_BOTS).apply()
    }
}
