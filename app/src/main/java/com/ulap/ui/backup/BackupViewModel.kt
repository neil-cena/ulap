package com.ulap.ui.backup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.SyncProgress
import com.ulap.domain.usecase.FetchIndexFromPinnedMessageUseCase
import com.ulap.domain.usecase.GetBackupStatsUseCase
import com.ulap.domain.usecase.ResetFailedToPendingUseCase
import com.ulap.domain.usecase.ScanMediaUseCase
import com.ulap.sync.BackupForegroundService
import com.ulap.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val getBackupStats: GetBackupStatsUseCase,
    private val resetFailedToPending: ResetFailedToPendingUseCase,
    private val scanMedia: ScanMediaUseCase,
    private val fetchIndex: FetchIndexFromPinnedMessageUseCase,
    private val syncEngine: SyncEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val stats: StateFlow<BackupStats?> = getBackupStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val progress: StateFlow<SyncProgress> = syncEngine.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncProgress())

    init {
        viewModelScope.launch { scanMedia(fullScan = false) }
    }

    fun startBackup() {
        viewModelScope.launch {
            try { scanMedia(fullScan = false) } catch (_: Exception) { }
        }
        BackupForegroundService.startBackup(context)
    }

    fun retryFailed() {
        viewModelScope.launch {
            try { resetFailedToPending() } catch (_: Exception) { }
        }
        BackupForegroundService.startBackup(context)
    }

    fun syncNow() {
        viewModelScope.launch {
            try {
                fetchIndex()
                scanMedia(fullScan = false)
            } catch (_: Exception) { }
        }
    }
}
