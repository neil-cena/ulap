package com.ulap.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.domain.model.BackupFolder
import com.ulap.domain.usecase.ObserveFoldersUseCase
import com.ulap.domain.usecase.RefreshFoldersUseCase
import com.ulap.domain.usecase.ScanMediaUseCase
import com.ulap.domain.usecase.StartBackupUseCase
import com.ulap.domain.usecase.ToggleFolderBackupUseCase
import com.ulap.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val observeFolders: ObserveFoldersUseCase,
    private val toggleFolderBackup: ToggleFolderBackupUseCase,
    private val refreshFolders: RefreshFoldersUseCase,
    private val scanMedia: ScanMediaUseCase,
    private val startBackup: StartBackupUseCase,
    private val syncEngine: SyncEngine,
) : ViewModel() {

    val folders: StateFlow<List<BackupFolder>> = observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { refreshFolders() }
    }

    fun toggle(bucketName: String, enabled: Boolean) {
        viewModelScope.launch {
            toggleFolderBackup(bucketName, enabled)
            if (enabled && !syncEngine.progress.value.isActive) {
                try { scanMedia(fullScan = true) } catch (_: Exception) { }
                startBackup()
            }
        }
    }
}
