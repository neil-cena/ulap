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
import com.ulap.data.auth.PhotosTokenSyncResult
import com.ulap.data.googlephotos.formatGooglePhotosDiagnostics
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.debug.DebugLogBuffer
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
    /** Google account is selected (may still need Photos scope or token). */
    val isSignedIn: Boolean = false,
    val signedInEmail: String? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    /** Play Services says [com.google.android.gms.auth.api.signin.GoogleSignIn.hasPermissions] is false — tap [grant scope]. */
    val needsPlayServicesPhotosScope: Boolean = false,
    /** One-shot consent from [com.google.android.gms.auth.UserRecoverableAuthException] (launched by UI). */
    val pendingGoogleConsentIntent: Intent? = null,
    val hasPhotosAccessToken: Boolean = false,
)

private const val GOOGLE_PHOTOS_LOG_TAG = "GooglePhotosImport"

@HiltViewModel
class GooglePhotosImportViewModel @Inject constructor(
    private val googleAuthManager: GoogleAuthManager,
    private val workManager: WorkManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val debugLog: DebugLogBuffer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GooglePhotosImportUiState())
    val uiState: StateFlow<GooglePhotosImportUiState> = _uiState.asStateFlow()

    val workInfo: Flow<WorkInfo?> = workManager.getWorkInfosForUniqueWorkFlow("google_import")
        .map { it.firstOrNull() }

    init {
        viewModelScope.launch {
            applySyncResult(googleAuthManager.syncPhotosAccessTokenFromLastAccount(), fromUserAction = false)
        }
    }

    fun getSignInIntent(activity: Activity): Intent = googleAuthManager.getSignInIntent(activity)

    fun onSignInActivityResult(data: Intent?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val sync = googleAuthManager.handleSignInActivityResult(data)
            applySyncResult(sync, fromUserAction = true)
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    /** After [GoogleAuthManager.requestPhotosScopePermission] + [MainActivity.onActivityResult]. */
    fun onGooglePhotosScopePermissionResult(resultCode: Int, data: Intent?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            applySyncResult(googleAuthManager.syncPhotosAccessTokenFromLastAccount(), fromUserAction = true)
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    /** After launching [GooglePhotosImportUiState.pendingGoogleConsentIntent]. */
    fun onGoogleConsentActivityResult(resultCode: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null, pendingGoogleConsentIntent = null) }
            if (resultCode == Activity.RESULT_OK) {
                applySyncResult(googleAuthManager.syncPhotosAccessTokenFromLastAccount(), fromUserAction = true)
            }
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    fun clearPendingConsentIntent() {
        _uiState.update { it.copy(pendingGoogleConsentIntent = null) }
    }

    fun requestPhotosScopePermission(activity: Activity) {
        val account = googleAuthManager.getLastSignedInAccount() ?: return
        googleAuthManager.requestPhotosScopePermission(activity, account)
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
                GooglePhotosImportUiState()
            }
        }
    }

    private fun applySyncResult(result: PhotosTokenSyncResult, fromUserAction: Boolean) {
        val account = googleAuthManager.getLastSignedInAccount()
        val email = googleAuthManager.getLastSignedInAccountEmail()
        val token = googleAuthManager.getAccessToken()
        when (result) {
            is PhotosTokenSyncResult.Success -> {
                _uiState.update {
                    it.copy(
                        isSignedIn = account != null,
                        signedInEmail = email,
                        error = null,
                        needsPlayServicesPhotosScope = false,
                        pendingGoogleConsentIntent = null,
                        hasPhotosAccessToken = !token.isNullOrBlank(),
                    )
                }
            }
            is PhotosTokenSyncResult.NeedsScopePermissionRequest -> {
                debugLog.log(
                    GOOGLE_PHOTOS_LOG_TAG,
                    "Photos scope not granted in Play Services — user must tap Grant library access",
                )
                _uiState.update {
                    it.copy(
                        isSignedIn = account != null,
                        signedInEmail = email,
                        needsPlayServicesPhotosScope = true,
                        pendingGoogleConsentIntent = null,
                        hasPhotosAccessToken = false,
                        error = if (fromUserAction) null else it.error,
                    )
                }
            }
            is PhotosTokenSyncResult.NeedsUserConsentDialog -> {
                debugLog.log(GOOGLE_PHOTOS_LOG_TAG, "Google account consent required for Photos token")
                _uiState.update {
                    it.copy(
                        isSignedIn = account != null,
                        signedInEmail = email,
                        needsPlayServicesPhotosScope = false,
                        pendingGoogleConsentIntent = result.consentIntent,
                        hasPhotosAccessToken = false,
                        error = null,
                    )
                }
            }
            is PhotosTokenSyncResult.Error -> {
                val msg = result.throwable.message
                result.throwable.let { err ->
                    debugLog.log(GOOGLE_PHOTOS_LOG_TAG, "token sync failed: ${formatGooglePhotosDiagnostics(err)}")
                }
                _uiState.update {
                    it.copy(
                        isSignedIn = account != null,
                        signedInEmail = email,
                        needsPlayServicesPhotosScope = false,
                        pendingGoogleConsentIntent = null,
                        hasPhotosAccessToken = !token.isNullOrBlank(),
                        error = if (fromUserAction || account != null) msg else null,
                    )
                }
            }
        }
    }
}
