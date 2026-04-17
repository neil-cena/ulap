package com.ulap.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.data.repository.UploadSpeedMode
import com.ulap.debug.DebugLogBuffer
import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.BotCredential
import com.ulap.domain.usecase.AddSecondaryBotUseCase
import com.ulap.domain.usecase.ClearCredentialsUseCase
import com.ulap.domain.usecase.DeleteAllBackupsUseCase
import com.ulap.domain.usecase.GetBackedUpWithLocalUseCase
import com.ulap.domain.usecase.GetBackupStatsUseCase
import com.ulap.domain.usecase.GetBotPoolUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.MarkAsCloudOnlyUseCase
import com.ulap.domain.usecase.MarkCorruptChunkedItemsForReuploadUseCase
import com.ulap.domain.usecase.ObserveCorruptChunkedBackupCountUseCase
import com.ulap.domain.usecase.RemoveSecondaryBotUseCase
import com.ulap.domain.usecase.RepairCorruptChunkMetadataFromPinnedIndexUseCase
import com.ulap.data.remote.RepairPhase
import com.ulap.data.remote.RepairProgress
import com.ulap.domain.health.BotHealthStatus
import com.ulap.domain.usecase.CheckBotHealthUseCase
import com.ulap.domain.usecase.GetBotHealthStateUseCase
import com.ulap.domain.usecase.HandlePrimaryBanResult
import com.ulap.domain.usecase.HandlePrimaryBotBannedUseCase
import com.ulap.domain.usecase.RefreshBotHealthAsyncUseCase
import com.ulap.domain.usecase.RepairBannedBotFilesUseCase
import com.ulap.domain.usecase.RepairResult
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

/** UI representation of one bot in the pool. */
data class BotPoolEntry(
    val index: Int,
    val maskedToken: String,
    val label: String,
    val isPrimary: Boolean,
    val healthStatus: BotHealthStatus = BotHealthStatus.UNKNOWN,
)

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
    val botPool: List<BotPoolEntry> = emptyList(),
    val isAddingBot: Boolean = false,
    val addBotResult: String? = null,
    val repairProgress: RepairProgress? = null,
    val repairResult: String? = null,
    val showPromotionDialog: Boolean = false,
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
    private val observeCorruptChunkedBackupCount: ObserveCorruptChunkedBackupCountUseCase,
    private val repairCorruptChunkMetadataFromPinnedIndex: RepairCorruptChunkMetadataFromPinnedIndexUseCase,
    private val markCorruptChunkedItemsForReupload: MarkCorruptChunkedItemsForReuploadUseCase,
    private val getBotPool: GetBotPoolUseCase,
    private val addSecondaryBot: AddSecondaryBotUseCase,
    private val removeSecondaryBot: RemoveSecondaryBotUseCase,
    private val checkBotHealth: CheckBotHealthUseCase,
    private val getBotHealthState: GetBotHealthStateUseCase,
    private val refreshBotHealthAsync: RefreshBotHealthAsyncUseCase,
    private val repairBannedBotFiles: RepairBannedBotFilesUseCase,
    private val handlePrimaryBotBanned: HandlePrimaryBotBannedUseCase,
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

    val uploadSpeedMode: StateFlow<UploadSpeedMode> = userPrefs.uploadSpeedMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UploadSpeedMode.BALANCED)

    val telegramLoggingEnabled: StateFlow<Boolean> = userPrefs.telegramLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val telegramLoggingChatId: StateFlow<String?> = userPrefs.telegramLoggingChatId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val googlePhotosWebClientId: StateFlow<String?> = userPrefs.googlePhotosWebClientId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val backupStats: StateFlow<BackupStats?> = getBackupStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val corruptChunkedBackupCount: StateFlow<Int> = observeCorruptChunkedBackupCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _isFixingCorruptBackups = MutableStateFlow(false)
    val isFixingCorruptBackups: StateFlow<Boolean> = _isFixingCorruptBackups.asStateFlow()

    private val _fixCorruptBackupsResult = MutableStateFlow<String?>(null)
    val fixCorruptBackupsResult: StateFlow<String?> = _fixCorruptBackupsResult.asStateFlow()

    init {
        loadState()
        // Observe health changes and refresh bot pool entries with updated status.
        viewModelScope.launch {
            getBotHealthState().collect { health ->
                _uiState.update { state ->
                    state.copy(
                        botPool = state.botPool.map { entry ->
                            entry.copy(healthStatus = health[entry.index] ?: BotHealthStatus.UNKNOWN)
                        },
                    )
                }
            }
        }
        // Kick off an initial async health check.
        refreshBotHealthAsync()
    }

    private fun loadState() {
        val token = getCredentials.getToken() ?: ""
        val chatId = getCredentials.getChatId() ?: ""
        val poolEntries = getBotPool().map { bot ->
            BotPoolEntry(
                index = bot.index,
                maskedToken = maskToken(bot.token),
                label = bot.label,
                isPrimary = bot.index == 0,
            )
        }
        _uiState.update {
            it.copy(
                maskedToken = if (token.length > 8) "${token.take(4)}…${token.takeLast(4)}" else token,
                chatId = chatId,
                botPool = poolEntries,
            )
        }
    }

    private fun maskToken(token: String): String =
        if (token.length > 8) "${token.take(4)}…${token.takeLast(4)}" else token

    fun verifyConnection() {
        val token = getCredentials.getToken() ?: return
        val chatId = getCredentials.getChatId() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isVerifying = true, verifyResult = null) }
            val result = verifyBot(token, chatId)
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

    // ── Bot pool ──────────────────────────────────────────────────────────────

    fun addBot(token: String, label: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAddingBot = true, addBotResult = null) }
            val result = addSecondaryBot(token, label)
            val message = when (result) {
                is VerifyResult.Success -> "Added ${result.botName}"
                is VerifyResult.Error -> "Error: ${result.message}"
            }
            loadState()
            _uiState.update { it.copy(isAddingBot = false, addBotResult = message) }
        }
    }

    fun removeBot(botIndex: Int) {
        removeSecondaryBot(botIndex)
        loadState()
    }

    fun dismissAddBotResult() = _uiState.update { it.copy(addBotResult = null) }

    // ─────────────────────────────────────────────────────────────────────────
    fun setStripExif(enabled: Boolean) = userPrefs.setStripExif(enabled)

    fun setWifiOnly(enabled: Boolean) {
        userPrefs.setWifiOnly(enabled)
        SyncWorker.schedule(context, wifiOnly = enabled, pauseOnLowBattery = userPrefs.pauseOnLowBattery.value)
    }

    fun setPauseOnLowBattery(enabled: Boolean) {
        userPrefs.setPauseOnLowBattery(enabled)
        SyncWorker.schedule(context, wifiOnly = userPrefs.wifiOnly.value, pauseOnLowBattery = enabled)
    }

    fun setUploadSpeedMode(mode: UploadSpeedMode) = userPrefs.setUploadSpeedMode(mode)

    fun setTelegramLoggingEnabled(enabled: Boolean) = userPrefs.setTelegramLoggingEnabled(enabled)

    fun setTelegramLoggingChatId(chatId: String?) = userPrefs.setTelegramLoggingChatId(chatId)

    fun setGooglePhotosWebClientId(clientId: String?) = userPrefs.setGooglePhotosWebClientId(clientId)

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

    fun dismissFixCorruptBackupsResult() {
        _fixCorruptBackupsResult.value = null
    }

    fun repairCorruptChunkedBackups() {
        if (_isFixingCorruptBackups.value) return
        viewModelScope.launch {
            _isFixingCorruptBackups.value = true
            _fixCorruptBackupsResult.value = null
            val result = repairCorruptChunkMetadataFromPinnedIndex()
            _fixCorruptBackupsResult.value = when {
                result.isSuccess -> {
                    val n = result.getOrNull() ?: 0
                    if (n > 0) {
                        "Repaired $n corrupted backup(s). Chunk metadata was restored from the pinned index."
                    } else {
                        // Repair found no recoverable data — the chunk file IDs were wiped before
                        // the index could capture them (legacy bug). Mark affected videos for
                        // re-upload so the next sync recreates the metadata.
                        val requeued = runCatching { markCorruptChunkedItemsForReupload() }.getOrNull() ?: 0
                        if (requeued > 0) {
                            "Chunk data is unrecoverable from the index. Marked $requeued video(s) for re-upload. Start a backup to restore streaming."
                        } else {
                            "No corrupted chunked backups found."
                        }
                    }
                }
                else -> "Repair failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            }
            _isFixingCorruptBackups.value = false
        }
    }

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

    // ── Bot health and repair ─────────────────────────────────────────────────

    /** Re-runs getMe for all bots and updates the health indicators. */
    fun refreshBotHealth() {
        viewModelScope.launch {
            checkBotHealth()
        }
    }

    /** Dismisses the promotion dialog without taking action. */
    fun dismissPromotionDialog() = _uiState.update { it.copy(showPromotionDialog = false) }

    /** Dismisses the repair result snackbar. */
    fun dismissRepairResult() = _uiState.update { it.copy(repairResult = null) }

    /**
     * Starts a repair run for a banned secondary (non-primary) bot identified by [bannedBotIndex].
     * Re-forwards all affected files through the best available healthy bot.
     */
    fun startRepair(bannedBotIndex: Int) {
        if (_uiState.value.repairProgress?.phase == RepairPhase.RUNNING) return
        viewModelScope.launch {
            _uiState.update { it.copy(repairProgress = RepairProgress(phase = RepairPhase.RUNNING)) }
            // Stream live progress from the use case's shared StateFlow.
            val progressJob = launch {
                repairBannedBotFiles.repairProgress.collect { progress ->
                    _uiState.update { it.copy(repairProgress = progress) }
                }
            }
            val result = repairBannedBotFiles(bannedBotIndex)
            progressJob.cancel()
            val message = when (result) {
                is RepairResult.Done -> buildRepairResultMessage(
                    result.repairedCount, result.failedCount, result.needsReuploadCount,
                )
                RepairResult.NoCredentials -> "No credentials configured."
                RepairResult.NoHealthyBot -> "No healthy bot available to perform repair. Add a working bot first."
            }
            _uiState.update { it.copy(repairResult = message) }
            loadState()
        }
    }

    /**
     * Promotes the first healthy secondary bot to primary, then repairs files that belonged to
     * the banned primary bot. Called when the user confirms the promotion dialog.
     */
    fun promotePrimaryBot() {
        _uiState.update { it.copy(showPromotionDialog = false) }
        if (_uiState.value.repairProgress?.phase == RepairPhase.RUNNING) return
        viewModelScope.launch {
            _uiState.update { it.copy(repairProgress = RepairProgress(phase = RepairPhase.RUNNING)) }
            val progressJob = launch {
                handlePrimaryBotBanned.repairProgress.collect { progress ->
                    _uiState.update { it.copy(repairProgress = progress) }
                }
            }
            val result = handlePrimaryBotBanned()
            progressJob.cancel()
            val message = when (result) {
                is HandlePrimaryBanResult.Done -> "Primary bot promoted. " + buildRepairResultMessage(
                    result.repairedCount, result.failedCount, result.needsReuploadCount,
                )
                HandlePrimaryBanResult.NoCredentials -> "No credentials configured."
                HandlePrimaryBanResult.NoHealthyAlt -> "No healthy secondary bot to promote. Add a working bot first."
            }
            _uiState.update { it.copy(repairResult = message) }
            loadState()
        }
    }

    private fun buildRepairResultMessage(repaired: Int, failed: Int, needsReupload: Int): String {
        val parts = buildList {
            if (repaired > 0) add("$repaired file(s) repaired")
            if (failed > 0) add("$failed failed (will retry next run)")
            if (needsReupload > 0) add("$needsReupload need re-upload (original messages deleted)")
        }
        return if (parts.isEmpty()) "Repair complete. No items needed repair." else parts.joinToString(", ") + "."
    }
}
