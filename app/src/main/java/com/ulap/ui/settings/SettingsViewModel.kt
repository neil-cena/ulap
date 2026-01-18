package com.ulap.ui.settings // edge-to-edge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.domain.usecase.ClearCredentialsUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.VerifyBotCredentialsUseCase
import com.ulap.domain.usecase.VerifyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val maskedToken: String = "",
    val chatId: String = "",
    val isVerifying: Boolean = false,
    val verifyResult: String? = null,
    val showClearConfirm: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val getCredentials: GetCredentialsUseCase,
    private val clearCredentials: ClearCredentialsUseCase,
    private val verifyBot: VerifyBotCredentialsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadState()
    }

    private fun loadState() {
        val token = getCredentials.getToken() ?: ""
        val chatId = getCredentials.getChatId() ?: ""
        _uiState.update {
            it.copy(
                maskedToken = if (token.length > 8) "${token.take(4)}…${token.takeLast(4)}" else token,
                chatId = chatId,
            )
        }
    }

    fun verifyConnection() {
        val token = getCredentials.getToken() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isVerifying = true, verifyResult = null) }
            val result = verifyBot(token)
            _uiState.update {
                it.copy(
                    isVerifying = false,
                    verifyResult = when (result) {
                        is VerifyResult.Success -> "Connected as ${result.botName}"
                        is VerifyResult.Error -> "Error: ${result.message}"
                    },
                )
            }
        }
    }

    fun requestClear() = _uiState.update { it.copy(showClearConfirm = true) }
    fun dismissClear() = _uiState.update { it.copy(showClearConfirm = false) }

    fun clearAccount() {
        clearCredentials()
        _uiState.update { SettingsUiState() }
    }
}
