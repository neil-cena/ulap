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
import androidx.work.workDataOf
import com.ulap.data.auth.GoogleAuthManager
import com.ulap.data.auth.PhotosTokenSyncResult
import com.ulap.data.googlephotos.GooglePhotosPickerApi
import com.ulap.data.googlephotos.PickerSession
import com.ulap.data.googlephotos.formatGooglePhotosDiagnostics
import com.ulap.data.googlephotos.pollIntervalMs
import com.ulap.data.googlephotos.timeoutMs
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.debug.DebugLogBuffer
import com.ulap.sync.GooglePhotosImportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

data class GooglePhotosImportUiState(    /** Google account is selected (may still need Photos scope or token). */
    val isSignedIn: Boolean = false,
    val signedInEmail: String? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    /** Play Services says [com.google.android.gms.auth.api.signin.GoogleSignIn.hasPermissions] is false — tap [grant scope]. */
    val needsPlayServicesPhotosScope: Boolean = false,
    /** One-shot consent from [com.google.android.gms.auth.UserRecoverableAuthException] (launched by UI). */
    val pendingGoogleConsentIntent: Intent? = null,
    val hasPhotosAccessToken: Boolean = false,
    /** Session ID for the active Picker API session, if one has been created. */
    val pickerSessionId: String? = null,
    /** The pickerUri the user must open in Google Photos to select media. */
    val pickerUri: String? = null,
    /** True while waiting for the user to finish selecting items in Google Photos. */
    val isWaitingForPicker: Boolean = false,
)

private const val GOOGLE_PHOTOS_LOG_TAG = "GooglePhotosImport"
private val EMPTY_JSON_BODY = "{}".toRequestBody("application/json".toMediaType())

@HiltViewModel
class GooglePhotosImportViewModel @Inject constructor(
    private val googleAuthManager: GoogleAuthManager,
    private val pickerApi: GooglePhotosPickerApi,
    private val workManager: WorkManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val debugLog: DebugLogBuffer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GooglePhotosImportUiState())
    val uiState: StateFlow<GooglePhotosImportUiState> = _uiState.asStateFlow()

    val workInfo: Flow<WorkInfo?> = workManager.getWorkInfosForUniqueWorkFlow("google_import")
        .map { it.firstOrNull() }

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            applySyncResult(googleAuthManager.syncPhotosAccessTokenFromLastAccount(), fromUserAction = false)
            resumeSavedSessionIfAny()
        }
        viewModelScope.launch {
            workInfo.collect { wi ->
                val terminal = wi?.state == WorkInfo.State.SUCCEEDED ||
                    wi?.state == WorkInfo.State.FAILED ||
                    wi?.state == WorkInfo.State.CANCELLED
                if (terminal && _uiState.value.pickerSessionId != null) {
                    userPreferencesRepository.setPickerSessionId(null)
                    _uiState.update {
                        it.copy(
                            pickerSessionId = null,
                            pickerUri = null,
                            isWaitingForPicker = false,
                        )
                    }
                }
            }
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

    /**
     * Creates a Picker API session and opens the [PickerSession.pickerUri] so the user can
     * select photos in Google Photos. Starts background polling for session completion.
     */
    fun createPickerSession(launchUri: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            try {
                val session = pickerApi.createSession(EMPTY_JSON_BODY)
                userPreferencesRepository.setPickerSessionId(session.id)
                _uiState.update {
                    it.copy(
                        pickerSessionId = session.id,
                        pickerUri = session.pickerUri,
                        isWaitingForPicker = true,
                        isBusy = false,
                    )
                }
                launchUri(session.pickerUri)
                startPolling(session.id, session)
            } catch (e: Exception) {
                debugLog.log(GOOGLE_PHOTOS_LOG_TAG, "createPickerSession failed: ${formatGooglePhotosDiagnostics(e)}")
                _uiState.update { it.copy(isBusy = false, error = "Could not start Google Photos session: ${e.message}") }
            }
        }
    }

    /** Cancels the active picker session and clears related state. */
    fun cancelPickerSession() {
        pollingJob?.cancel()
        pollingJob = null
        val sessionId = _uiState.value.pickerSessionId
        _uiState.update {
            it.copy(
                pickerSessionId = null,
                pickerUri = null,
                isWaitingForPicker = false,
                error = null,
            )
        }
        if (sessionId != null) {
            viewModelScope.launch {
                userPreferencesRepository.setPickerSessionId(null)
                runCatching { pickerApi.deleteSession(sessionId) }
            }
        }
    }

    fun startOrResumeImport() {
        val sessionId = _uiState.value.pickerSessionId ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val request = OneTimeWorkRequestBuilder<GooglePhotosImportWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(GooglePhotosImportWorker.KEY_SESSION_ID to sessionId))
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
            pollingJob?.cancel()
            pollingJob = null
            workManager.cancelUniqueWork("google_import")
            googleAuthManager.signOut()
            userPreferencesRepository.updateGooglePhotosPageToken(null)
            userPreferencesRepository.setPickerSessionId(null)
            _uiState.update { GooglePhotosImportUiState() }
        }
    }

    private suspend fun resumeSavedSessionIfAny() {
        val savedSessionId = userPreferencesRepository.pickerSessionId.first() ?: return
        try {
            val session = pickerApi.getSession(savedSessionId)
            if (session.mediaItemsSet) {
                _uiState.update {
                    it.copy(
                        pickerSessionId = savedSessionId,
                        pickerUri = null,
                        isWaitingForPicker = false,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        pickerSessionId = savedSessionId,
                        pickerUri = session.pickerUri,
                        isWaitingForPicker = true,
                    )
                }
                startPolling(savedSessionId, session)
            }
        } catch (e: Exception) {
            debugLog.log(GOOGLE_PHOTOS_LOG_TAG, "resumeSavedSession failed (session likely expired): ${e.message}")
            userPreferencesRepository.setPickerSessionId(null)
        }
    }

    private fun startPolling(sessionId: String, initialSession: PickerSession) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            val intervalMs = initialSession.pollingConfig.pollIntervalMs()
            val timeoutMs = initialSession.pollingConfig.timeoutMs()
            val startTime = System.currentTimeMillis()
            while (true) {
                delay(intervalMs)
                try {
                    val session = pickerApi.getSession(sessionId)
                    if (session.mediaItemsSet) {
                        _uiState.update { it.copy(isWaitingForPicker = false) }
                        break
                    }
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        debugLog.log(GOOGLE_PHOTOS_LOG_TAG, "Picker session timed out — sessionId=$sessionId")
                        userPreferencesRepository.setPickerSessionId(null)
                        _uiState.update {
                            it.copy(
                                isWaitingForPicker = false,
                                pickerSessionId = null,
                                pickerUri = null,
                                error = "Photo selection timed out. Tap \"Select from Google Photos\" to try again.",
                            )
                        }
                        break
                    }
                } catch (e: Exception) {
                    debugLog.log(GOOGLE_PHOTOS_LOG_TAG, "Picker session poll error: ${formatGooglePhotosDiagnostics(e)}")
                    userPreferencesRepository.setPickerSessionId(null)
                    _uiState.update {
                        it.copy(
                            isWaitingForPicker = false,
                            pickerSessionId = null,
                            pickerUri = null,
                            error = "Session error — please try selecting photos again.",
                        )
                    }
                    break
                }
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
                    "Photos picker scope not granted in Play Services — user must tap Grant access",
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
                debugLog.log(GOOGLE_PHOTOS_LOG_TAG, "Google account consent required for Photos picker token")
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
