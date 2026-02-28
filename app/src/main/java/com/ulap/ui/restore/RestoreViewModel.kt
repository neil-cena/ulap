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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    init {
        var wasActiveDownloading = false
        syncEngine.progress
            .onEach { prog ->
                if (prog.isActive && prog.operation == SyncOperation.DOWNLOADING) {
                    wasActiveDownloading = true
                } else if (!prog.isActive && wasActiveDownloading) {
                    wasActiveDownloading = false
                    if (prog.itemsTotal > 0) {
                        val failed = maxOf(0, prog.itemsTotal - prog.itemsDone)
                        _completionEvent.tryEmit(BackupCompletionEvent(succeeded = prog.itemsDone, failed = failed))
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun startRestore() {
        BackupForegroundService.startRestore(context)
    }
}
