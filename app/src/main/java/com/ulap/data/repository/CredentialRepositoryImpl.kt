package com.ulap.data.repository

import android.content.SharedPreferences
import com.ulap.domain.repository.CredentialRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_BOT_TOKEN = "bot_token"
private const val KEY_CHAT_ID = "chat_id"
private const val KEY_LAST_INDEX_FILE_ID = "last_index_file_id"

@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val encryptedPrefs: SharedPreferences,
) : CredentialRepository {

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
}
