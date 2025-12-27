package com.ulap.ui.gallery // per-folder bars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.domain.model.BackupFolder
import com.ulap.domain.usecase.ObserveFoldersUseCase
import com.ulap.domain.usecase.RefreshFoldersUseCase
import com.ulap.domain.usecase.ToggleFolderBackupUseCase
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
) : ViewModel() {

    val folders: StateFlow<List<BackupFolder>> = observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { refreshFolders() }
    }

    fun toggle(bucketName: String, enabled: Boolean) {
        viewModelScope.launch { toggleFolderBackup(bucketName, enabled) }
    }
}
