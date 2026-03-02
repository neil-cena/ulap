package com.ulap.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.debug.DebugLogBuffer
import com.ulap.domain.usecase.ClearCredentialsUseCase
import com.ulap.domain.usecase.DeleteAllBackupsUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.VerifyBotCredentialsUseCase
import com.ulap.domain.usecase.VerifyResult
import com.ulap.sync.DeleteAllBackupsResult
import com.ulap.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val maskedToken: String = "",
    val chatId: String = "",
    val isVerifying: Boolean = false,
    val verifyResult: String? = null,
    val showClearConfirm: Boolean = false,
    val showDeleteBackupsConfirm: Boolean = false,
    val isDeletingBackups: Boolean = false,
    val deleteBackupsProgress: Pair<Int, Int>? = null,
    val deleteBackupsResult: DeleteBackupsUiResult? = null,
)

sealed class DeleteBackupsUiResult {
    object Success : DeleteBackupsUiResult()
    data class PartialSuccess(val failedBatches: Int) : DeleteBackupsUiResult()
    data class Failure(val message: String) : DeleteBackupsUiResult()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val getCredentials: GetCredentialsUseCase,
    private val clearCredentials: ClearCredentialsUseCase,
    private val verifyBot: VerifyBotCredentialsUseCase,
    val debugLog: DebugLogBuffer,
    private val userPrefs: UserPreferencesRepository,
    private val deleteAllBackupsUseCase: DeleteAllBackupsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val debugEntries: StateFlow<List<String>> = debugLog.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val themePreference: StateFlow<ThemePreference> = userPrefs.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePreference.SYSTEM)

    val stripExif: StateFlow<Boolean> = userPrefs.stripExif
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    fun setTheme(preference: ThemePreference) = userPrefs.setTheme(preference)
    fun setStripExif(enabled: Boolean) = userPrefs.setStripExif(enabled)

    fun requestClear() = _uiState.update { it.copy(showClearConfirm = true) }
    fun dismissClear() = _uiState.update { it.copy(showClearConfirm = false) }

    fun clearAccount() {
        clearCredentials()
        _uiState.update { SettingsUiState() }
    }

    fun requestDeleteBackups() = _uiState.update { it.copy(showDeleteBackupsConfirm = true) }
    fun dismissDeleteBackups() = _uiState.update { it.copy(showDeleteBackupsConfirm = false) }
    fun dismissDeleteBackupsResult() = _uiState.update { it.copy(deleteBackupsResult = null) }

    fun deleteAllBackups() {
        _uiState.update { it.copy(showDeleteBackupsConfirm = false, isDeletingBackups = true, deleteBackupsProgress = null) }
        viewModelScope.launch {
            val engineResult = deleteAllBackupsUseCase { deleted, total ->
                _uiState.update { it.copy(deleteBackupsProgress = Pair(deleted, total)) }
            }
            val uiResult = when (engineResult) {
                is DeleteAllBackupsResult.Success -> DeleteBackupsUiResult.Success
                is DeleteAllBackupsResult.PartialSuccess ->
                    DeleteBackupsUiResult.PartialSuccess(engineResult.failedBatches)
                is DeleteAllBackupsResult.Failure ->
                    DeleteBackupsUiResult.Failure(engineResult.cause.message ?: "Unknown error")
            }
            _uiState.update { it.copy(isDeletingBackups = false, deleteBackupsProgress = null, deleteBackupsResult = uiResult) }
        }
    }

    fun clearDebugLog() = debugLog.clear()
}
