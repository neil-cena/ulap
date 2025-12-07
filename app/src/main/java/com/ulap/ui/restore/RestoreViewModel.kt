package com.ulap.ui.restore // MediaStore insert

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.domain.model.SyncProgress
import com.ulap.domain.usecase.StartRestoreUseCase
import com.ulap.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RestoreViewModel @Inject constructor(
    private val startRestore: StartRestoreUseCase,
    private val syncEngine: SyncEngine,
) : ViewModel() {

    val progress: StateFlow<SyncProgress> = syncEngine.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncProgress())

    fun startRestore() {
        viewModelScope.launch { startRestore.invoke() }
    }
}
