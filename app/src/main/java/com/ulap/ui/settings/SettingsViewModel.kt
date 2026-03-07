package com.ulap.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.debug.DebugLogBuffer
import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.MediaItem
import com.ulap.domain.usecase.ClearCredentialsUseCase
import com.ulap.domain.usecase.DeleteAllBackupsUseCase
import com.ulap.domain.usecase.GetBackedUpWithLocalUseCase
import com.ulap.domain.usecase.GetBackupStatsUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.MarkAsCloudOnlyUseCase
import com.ulap.domain.usecase.VerifyBotCredentialsUseCase
import com.ulap.domain.usecase.VerifyResult
import com.ulap.sync.DeleteAllBackupsResult
import com.ulap.sync.SyncWorker
import com.ulap.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

data class FreeSpaceState(
    val items: List<MediaItem> = emptyList(),
    val totalBytes: Long = 0L,
    val isLoading: Boolean = false,
    val deleteSender: IntentSender? = null,
    val pendingDeleteIds: List<String> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val getCredentials: GetCredentialsUseCase,
    private val clearCredentials: ClearCredentialsUseCase,
    private val verifyBot: VerifyBotCredentialsUseCase,
    val debugLog: DebugLogBuffer,
    private val userPrefs: UserPreferencesRepository,
    private val deleteAllBackupsUseCase: DeleteAllBackupsUseCase,
    private val getBackupStats: GetBackupStatsUseCase,
    private val getBackedUpWithLocal: GetBackedUpWithLocalUseCase,
    private val markAsCloudOnly: MarkAsCloudOnlyUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val debugEntries: StateFlow<List<String>> = debugLog.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val themePreference: StateFlow<ThemePreference> = userPrefs.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePreference.SYSTEM)

    val stripExif: StateFlow<Boolean> = userPrefs.stripExif
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val wifiOnly: StateFlow<Boolean> = userPrefs.wifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pauseOnLowBattery: StateFlow<Boolean> = userPrefs.pauseOnLowBattery
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val backupStats: StateFlow<BackupStats?> = getBackupStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    fun setWifiOnly(enabled: Boolean) {
        userPrefs.setWifiOnly(enabled)
        SyncWorker.schedule(context, wifiOnly = enabled, pauseOnLowBattery = userPrefs.pauseOnLowBattery.value)
    }

    fun setPauseOnLowBattery(enabled: Boolean) {
        userPrefs.setPauseOnLowBattery(enabled)
        SyncWorker.schedule(context, wifiOnly = userPrefs.wifiOnly.value, pauseOnLowBattery = enabled)
    }

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

    // ── Free Up Space ─────────────────────────────────────────────────────────

    private val _freeSpace = MutableStateFlow(FreeSpaceState())
    val freeSpace: StateFlow<FreeSpaceState> = _freeSpace.asStateFlow()

    fun prepareFreeUpSpace() {
        if (_freeSpace.value.isLoading) return
        _freeSpace.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val items = try { getBackedUpWithLocal() } catch (_: Exception) { emptyList() }
            val totalBytes = items.sumOf { it.size }
            if (items.isEmpty()) {
                _freeSpace.update { FreeSpaceState() }
                return@launch
            }
            val sender: IntentSender? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val uris = items.mapNotNull { item ->
                        item.contentUri.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
                    }
                    MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
                } catch (_: Exception) { null }
            } else null
            _freeSpace.update {
                FreeSpaceState(
                    items = items,
                    totalBytes = totalBytes,
                    isLoading = false,
                    deleteSender = sender,
                    pendingDeleteIds = items.map { it.id },
                )
            }
        }
    }

    fun onFreeSpaceDeleteGranted() {
        val ids = _freeSpace.value.pendingDeleteIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            markAsCloudOnly(ids)
            _freeSpace.update { FreeSpaceState() }
        }
    }

    fun dismissFreeUpSpace() {
        _freeSpace.update { FreeSpaceState() }
    }

    fun consumeFreeSpaceDeleteSender() {
        _freeSpace.update { it.copy(deleteSender = null) }
    }
}
