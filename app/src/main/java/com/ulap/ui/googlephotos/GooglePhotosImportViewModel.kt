package com.ulap.ui.googlephotos

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.data.auth.GoogleAuthManager
import com.ulap.data.googlephotos.GooglePhotosImportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GooglePhotosImportUiState(
    val isSignedIn: Boolean = false,
    val isBusy: Boolean = false,
    val isImporting: Boolean = false,
    val processed: Int = 0,
    val imported: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class GooglePhotosImportViewModel @Inject constructor(
    private val googleAuthManager: GoogleAuthManager,
    private val importManager: GooglePhotosImportManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GooglePhotosImportUiState())
    val uiState: StateFlow<GooglePhotosImportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            if (googleAuthManager.refreshTokenFromLastAccount()) {
                _uiState.update { it.copy(isSignedIn = true) }
            }
        }
    }

    fun getSignInIntent(activity: Activity): Intent = googleAuthManager.getSignInIntent(activity)

    fun onSignInActivityResult(data: Intent?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val result = googleAuthManager.handleSignInActivityResult(data)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    isSignedIn = result.isSuccess && googleAuthManager.getAccessToken() != null,
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun startImport() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, processed = 0, imported = 0, error = null)
            }
            val result = importManager.importGooglePhotosLibrary { processed, imported ->
                _uiState.update { it.copy(processed = processed, imported = imported) }
            }
            _uiState.update {
                it.copy(
                    isImporting = false,
                    error = result.exceptionOrNull()?.message ?: it.error,
                )
            }
        }
    }
}
