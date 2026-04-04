package com.ulap.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ulap.data.auth.GoogleAuthManager
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.sync.GooglePhotosImportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GooglePhotosImportUiState(
    val isSignedIn: Boolean = false,
    val signedInEmail: String? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class GooglePhotosImportViewModel @Inject constructor(
    private val googleAuthManager: GoogleAuthManager,
    private val workManager: WorkManager,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GooglePhotosImportUiState())
    val uiState: StateFlow<GooglePhotosImportUiState> = _uiState.asStateFlow()

    val workInfo: Flow<WorkInfo?> = workManager.getWorkInfosForUniqueWorkFlow("google_import")
        .map { it.firstOrNull() }

    init {
        viewModelScope.launch {
            if (googleAuthManager.refreshTokenFromLastAccount()) {
                _uiState.update {
                    it.copy(
                        isSignedIn = true,
                        signedInEmail = googleAuthManager.getLastSignedInAccountEmail(),
                    )
                }
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
                    signedInEmail = googleAuthManager.getLastSignedInAccountEmail(),
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun startOrResumeImport() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val request = OneTimeWorkRequestBuilder<GooglePhotosImportWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            "google_import",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun pauseImport() {
        workManager.cancelUniqueWork("google_import")
    }

    fun signOut() {
        viewModelScope.launch {
            workManager.cancelUniqueWork("google_import")
            googleAuthManager.signOut()
            userPreferencesRepository.updateGooglePhotosPageToken(null)
            _uiState.update {
                it.copy(isSignedIn = false, signedInEmail = null)
            }
        }
    }
}
