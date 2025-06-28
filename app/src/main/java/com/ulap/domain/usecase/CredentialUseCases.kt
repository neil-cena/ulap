package com.ulap.domain.usecase

import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.sanitizeTokenForPath
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
}

class ClearCredentialsUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {
    operator fun invoke() = credentialRepository.clearCredentials()
}
