package com.ulap.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.domain.model.BackupFolder
import com.ulap.domain.usecase.ObserveFoldersUseCase
import com.ulap.domain.usecase.RefreshFoldersUseCase
import com.ulap.domain.usecase.ScanMediaUseCase
import com.ulap.domain.usecase.ToggleFolderBackupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    val folders: StateFlow<List<BackupFolder>> = observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
