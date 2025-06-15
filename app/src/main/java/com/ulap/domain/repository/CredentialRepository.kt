package com.ulap.domain.repository

interface CredentialRepository {
    fun getBotToken(): String?
    fun getChatId(): String?
    fun saveCredentials(token: String, chatId: String)
    fun clearCredentials()
    fun hasCredentials(): Boolean

    /** Last uploaded backup index document file_id (for "Sync from other device"). */
    fun getLastIndexFileId(): String?
    fun setLastIndexFileId(fileId: String?)
}
