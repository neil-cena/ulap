package com.ulap.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.SaveCredentialsUseCase
import com.ulap.domain.usecase.VerifyBotCredentialsUseCase
import com.ulap.domain.usecase.VerifyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BotSetupUiState(
    val token: String = "",
    val chatId: String = "",
    val isVerifying: Boolean = false,
    val verifyError: String? = null,
    val isVerified: Boolean = false,
)

@HiltViewModel
class BotSetupViewModel @Inject constructor(
    private val verifyBotCredentials: VerifyBotCredentialsUseCase,
    private val saveCredentials: SaveCredentialsUseCase,
    private val getCredentials: GetCredentialsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BotSetupUiState())
    val uiState: StateFlow<BotSetupUiState> = _uiState.asStateFlow()

    init {
        val token = getCredentials.getToken()
        val chatId = getCredentials.getChatId()
        if (!token.isNullOrBlank() && !chatId.isNullOrBlank()) {
            _uiState.update { it.copy(token = token, chatId = chatId) }
        }
    }

    fun onTokenChanged(value: String) = _uiState.update { it.copy(token = value, verifyError = null, isVerified = false) }
    fun onChatIdChanged(value: String) = _uiState.update { it.copy(chatId = value, verifyError = null, isVerified = false) }

    fun verify(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.token.isBlank() || state.chatId.isBlank()) {
            _uiState.update { it.copy(verifyError = "Please enter both token and chat ID") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isVerifying = true, verifyError = null) }
            val result = verifyBotCredentials(state.token, state.chatId)
            when (result) {
                is VerifyResult.Success -> {
                    val effectiveChatId = result.correctedChatId ?: state.chatId
                    if (result.correctedChatId != null) {
                        _uiState.update { it.copy(chatId = result.correctedChatId) }
                    }
                    saveCredentials(state.token, effectiveChatId)
                    _uiState.update { it.copy(isVerifying = false, isVerified = true) }
                    onSuccess()
                }
                is VerifyResult.Error -> {
                    _uiState.update { it.copy(isVerifying = false, verifyError = result.message) }
                }
            }
        }
    }
}
