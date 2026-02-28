package com.ulap.ui.restore

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.domain.model.BackupCompletionEvent
import com.ulap.domain.model.SyncOperation
import com.ulap.domain.model.SyncProgress
import com.ulap.sync.BackupForegroundService
import com.ulap.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RestoreViewModel @Inject constructor(
    private val syncEngine: SyncEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val progress: StateFlow<SyncProgress> = syncEngine.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncProgress())

    private val _completionEvent = MutableSharedFlow<BackupCompletionEvent>(extraBufferCapacity = 1)
    val completionEvent: SharedFlow<BackupCompletionEvent> = _completionEvent.asSharedFlow()
    private val _restoreRequested = MutableStateFlow(false)
    val restoreRequested: StateFlow<Boolean> = _restoreRequested.asStateFlow()

    init {
        // Track whether the active DOWNLOADING run was started by this ViewModel's startRestore().
        // This prevents single-item gallery downloads (downloadCloudItemToLocal) from falsely
        // triggering the restore completion snackbar and re-enabling the button.
        var trackingRestoreRun = false
        syncEngine.progress
            .onEach { prog ->
                if (prog.isActive && prog.operation == SyncOperation.DOWNLOADING && _restoreRequested.value) {
                    trackingRestoreRun = true
                } else if (!prog.isActive && trackingRestoreRun) {
                    trackingRestoreRun = false
                    if (prog.itemsTotal > 0) {
                        val failed = maxOf(0, prog.itemsTotal - prog.itemsDone)
                        _completionEvent.tryEmit(BackupCompletionEvent(succeeded = prog.itemsDone, failed = failed))
                    }
                    _restoreRequested.value = false
                } else if (!prog.isActive && _restoreRequested.value) {
                    // Restore request ended without an active run (e.g. no restorable items).
                    _restoreRequested.value = false
                }
            }
            .launchIn(viewModelScope)
    }

    fun startRestore() {
        if (_restoreRequested.value) return
        _restoreRequested.value = true
        BackupForegroundService.startRestore(context)
    }
}
