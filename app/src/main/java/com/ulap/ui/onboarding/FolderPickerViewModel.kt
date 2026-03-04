package com.ulap.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.domain.model.BackupFolder
import com.ulap.domain.usecase.ObserveFoldersUseCase
import com.ulap.domain.usecase.RefreshFoldersUseCase
import com.ulap.domain.usecase.ScanMediaUseCase
import com.ulap.domain.usecase.ToggleFolderBackupUseCase
import com.ulap.sync.MediaObserverService
import com.ulap.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderPickerUiState(
    val isLoading: Boolean = true,
    val hasEnabledAny: Boolean = false,
)

@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    private val observeFolders: ObserveFoldersUseCase,
    private val refreshFolders: RefreshFoldersUseCase,
    private val toggleFolderBackup: ToggleFolderBackupUseCase,
    private val scanMedia: ScanMediaUseCase,
    private val syncEngine: SyncEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val folders: StateFlow<List<BackupFolder>> = observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _requestStartBackup = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when backup should be started (e.g. user enabled a folder). UI should run notification permission flow then start the service. */
    val requestStartBackup: SharedFlow<Unit> = _requestStartBackup.asSharedFlow()

    private val _uiState = MutableStateFlow(FolderPickerUiState())
    val uiState: StateFlow<FolderPickerUiState> = _uiState

    init {
        viewModelScope.launch {
            refreshFolders()
            _uiState.update { it.copy(isLoading = false) }
        }
        viewModelScope.launch {
            folders.collect { list ->
                _uiState.update { it.copy(hasEnabledAny = list.any { f -> f.isEnabled }) }
            }
        }
    }

    fun toggle(bucketName: String, enabled: Boolean) {
        viewModelScope.launch {
            toggleFolderBackup(bucketName, enabled)
            if (enabled) {
                MediaObserverService.start(context)
                if (!syncEngine.progress.value.isActive) {
                    try { scanMedia(fullScan = true) } catch (_: Exception) { }
                    _requestStartBackup.emit(Unit)
                }
            }
        }
    }

    fun refreshAfterPermission() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            refreshFolders()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun scanAfterOnboarding() {
        viewModelScope.launch {
            try { scanMedia(fullScan = false) } catch (_: Exception) { }
        }
    }
}
