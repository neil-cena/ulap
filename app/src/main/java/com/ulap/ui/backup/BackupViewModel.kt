package com.ulap.ui.backup

import android.content.Context
import android.content.IntentSender
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.SyncProgress
import com.ulap.domain.usecase.FetchIndexFromPinnedMessageUseCase
import com.ulap.domain.usecase.GetBackupStatsUseCase
import com.ulap.domain.usecase.GetBackedUpWithLocalUseCase
import com.ulap.domain.usecase.MarkAsCloudOnlyUseCase
import com.ulap.domain.usecase.ResetFailedToPendingUseCase
import com.ulap.domain.usecase.ScanMediaUseCase
import com.ulap.sync.BackupForegroundService
import com.ulap.sync.SyncEngine
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

data class FreeSpaceState(
    /** Items eligible for local deletion (BACKED_UP with a valid contentUri). */
    val items: List<MediaItem> = emptyList(),
    /** Total bytes that would be freed. */
    val totalBytes: Long = 0L,
    /** True while we're computing eligible items. */
    val isLoading: Boolean = false,
    /** IntentSender for MediaStore.createDeleteRequest — null until prepared. */
    val deleteSender: IntentSender? = null,
    /** Ids that were passed to the delete request so we can mark them after confirmation. */
    val pendingDeleteIds: List<String> = emptyList(),
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val getBackupStats: GetBackupStatsUseCase,
    private val resetFailedToPending: ResetFailedToPendingUseCase,
    private val scanMedia: ScanMediaUseCase,
    private val fetchIndex: FetchIndexFromPinnedMessageUseCase,
    private val syncEngine: SyncEngine,
    private val getBackedUpWithLocal: GetBackedUpWithLocalUseCase,
    private val markAsCloudOnly: MarkAsCloudOnlyUseCase,
    private val userPrefs: UserPreferencesRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val stats: StateFlow<BackupStats?> = getBackupStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val progress: StateFlow<SyncProgress> = syncEngine.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncProgress())

    private val _freeSpace = MutableStateFlow(FreeSpaceState())
    val freeSpace: StateFlow<FreeSpaceState> = _freeSpace.asStateFlow()

    /** True when Wi-Fi-only is enabled and the user just tried to start a backup on mobile data. */
    private val _mobileDataWarning = MutableStateFlow(false)
    val mobileDataWarning: StateFlow<Boolean> = _mobileDataWarning.asStateFlow()

    /** Pending action to run after the user confirms "back up anyway". */
    private var pendingBackupAction: (() -> Unit)? = null

    init {
        viewModelScope.launch { scanMedia(fullScan = false) }
    }

    fun startBackup() {
        runWithWifiCheck {
            viewModelScope.launch {
                try { scanMedia(fullScan = false) } catch (_: Exception) { }
            }
            BackupForegroundService.startBackup(context)
        }
    }

    fun retryFailed() {
        runWithWifiCheck {
            viewModelScope.launch {
                try { resetFailedToPending() } catch (_: Exception) { }
            }
            BackupForegroundService.startBackup(context)
        }
    }

    fun pauseBackup() {
        BackupForegroundService.pause(context)
    }

    fun resumeBackup() {
        runWithWifiCheck { BackupForegroundService.resume(context) }
    }

    fun dismissMobileDataWarning() {
        pendingBackupAction = null
        _mobileDataWarning.update { false }
    }

    fun confirmBackupOnMobileData() {
        _mobileDataWarning.update { false }
        pendingBackupAction?.invoke()
        pendingBackupAction = null
    }

    /** Runs [action] immediately if Wi-Fi-only is off or we're on Wi-Fi; otherwise shows the mobile data warning dialog. */
    private fun runWithWifiCheck(action: () -> Unit) {
        if (userPrefs.wifiOnly.value && isOnMobileData()) {
            pendingBackupAction = action
            _mobileDataWarning.update { true }
        } else {
            action()
        }
    }

    private fun isOnMobileData(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun syncNow() {
        viewModelScope.launch {
            try {
                fetchIndex()
                scanMedia(fullScan = false)
            } catch (_: Exception) { }
        }
    }

    /** Loads eligible items and builds the system delete request IntentSender. */
    fun prepareFreeUpSpace() {
        if (_freeSpace.value.isLoading) return
        _freeSpace.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val items = try { getBackedUpWithLocal() } catch (_: Exception) { emptyList() }
            val totalBytes = items.sumOf { it.size }
            if (items.isEmpty()) {
                _freeSpace.update { FreeSpaceState(items = emptyList(), totalBytes = 0L) }
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

    /** Called after the system delete dialog is confirmed (or on pre-API-30 devices). */
    fun onDeleteGranted() {
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

    fun consumeDeleteSender() {
        _freeSpace.update { it.copy(deleteSender = null) }
    }
}
