package com.ulap.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ulap.data.auth.AuthResult
import com.ulap.data.auth.AuthSession
import com.ulap.data.auth.GoogleAuthManager
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

data class GooglePhotosImportUiState(
    val isSignedIn: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val hasPhotosAccessToken: Boolean = false,
    val pickerSessionId: String? = null,
    val pickerUri: String? = null,
    val isWaitingForPicker: Boolean = false,
    val selectedMediaCount: Int? = null,
    val isCountingSelection: Boolean = false,
    val importSuccessSummary: GooglePhotosImportSummary? = null,
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
            val signedIn = googleAuthManager.isSignedIn()
            val hasToken = googleAuthManager.getAccessToken() != null
            _uiState.update { it.copy(isSignedIn = signedIn, hasPhotosAccessToken = hasToken) }
            if (signedIn && !hasToken) {
                val clientId = userPreferencesRepository.googlePhotosWebClientId.first()
                val clientSecret = userPreferencesRepository.googlePhotosClientSecret.first()
                if (clientId != null && clientSecret != null) {
                    val refreshed = googleAuthManager.refreshToken(clientId, clientSecret)
                    _uiState.update {
                        it.copy(hasPhotosAccessToken = refreshed)
                    }
                }
            }
            resumeSavedSessionIfAny()
        }
        viewModelScope.launch {
            workInfo.collect { wi ->
                when (wi?.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        if (_uiState.value.pickerSessionId != null) {
                            val summary = wi.outputData.parseGooglePhotosImportSummary()
                            userPreferencesRepository.setPickerSessionId(null)
                            _uiState.update {
                                it.copy(
                                    pickerSessionId = null,
                                    pickerUri = null,
                                    isWaitingForPicker = false,
                                    selectedMediaCount = null,
                                    isCountingSelection = false,
                                    importSuccessSummary = summary,
                                    error = null,
                                )
                            }
                        }
                    }
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED,
                    -> {
                        if (_uiState.value.pickerSessionId != null) {
                            userPreferencesRepository.setPickerSessionId(null)
                            _uiState.update {
                                it.copy(
                                    pickerSessionId = null,
                                    pickerUri = null,
                                    isWaitingForPicker = false,
                                    selectedMediaCount = null,
                                    isCountingSelection = false,
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Starts the PKCE auth flow: opens a loopback server, returns the auth URL
     * for the caller to launch in a browser, then suspends until the redirect
     * arrives and the code is exchanged for tokens.
     */
    fun launchSignIn(openBrowser: (String) -> Unit) {
        val clientId = userPreferencesRepository.googlePhotosWebClientId.value ?: return
        val clientSecret = userPreferencesRepository.googlePhotosClientSecret.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val session: AuthSession
            try {
                session = googleAuthManager.startAuth(clientId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, error = "Could not start sign-in: ${e.message}") }
                return@launch
            }
            openBrowser(session.url)
            val result = googleAuthManager.awaitAuthResult(session, clientId, clientSecret)
            when (result) {
                is AuthResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSignedIn = true,
                            hasPhotosAccessToken = true,
                            isBusy = false,
                            error = null,
                        )
                    }
                }
                is AuthResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            error = result.errorDescription ?: result.error,
                        )
                    }
                }
            }
        }
    }

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
                        importSuccessSummary = null,
                    )
                }
                launchUri(session.pickerUri)
                startPolling(session.id, session)
            } catch (e: Exception) {
                debugLog.log(GOOGLE_PHOTOS_LOG_TAG, "createPickerSession failed: ${formatGooglePhotosDiagnostics(e)}")
                _uiState.update { it.copy(isBusy = false, error = "Could not start Google Photos session: ${e.message ?: formatGooglePhotosDiagnostics(e)}") }
            }
        }
    }

    fun cancelPickerSession() {
        pollingJob?.cancel()
        pollingJob = null
        val sessionId = _uiState.value.pickerSessionId
        _uiState.update {
            it.copy(
                pickerSessionId = null,
                pickerUri = null,
                isWaitingForPicker = false,
                selectedMediaCount = null,
                isCountingSelection = false,
                importSuccessSummary = null,
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
        val selectedTotal = _uiState.value.selectedMediaCount ?: return
        if (selectedTotal <= 0) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val request = OneTimeWorkRequestBuilder<GooglePhotosImportWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    GooglePhotosImportWorker.KEY_SESSION_ID to sessionId,
                    GooglePhotosImportWorker.KEY_SELECTED_TOTAL to selectedTotal,
                ),
            )
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

    fun dismissImportSuccess() {
        _uiState.update { it.copy(importSuccessSummary = null) }
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
                refreshSelectionCount(savedSessionId)
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
                        refreshSelectionCount(sessionId)
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

    private fun refreshSelectionCount(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCountingSelection = true, error = null) }
            try {
                val n = countMediaItemsInSession(sessionId)
                _uiState.update {
                    it.copy(
                        selectedMediaCount = n,
                        isCountingSelection = false,
                    )
                }
            } catch (e: Exception) {
                debugLog.log(
                    GOOGLE_PHOTOS_LOG_TAG,
                    "countMediaItemsInSession failed: ${formatGooglePhotosDiagnostics(e)}",
                )
                _uiState.update {
                    it.copy(
                        isCountingSelection = false,
                        selectedMediaCount = null,
                        error = "Could not count selected photos: ${e.message ?: formatGooglePhotosDiagnostics(e)}",
                    )
                }
            }
        }
    }

    private suspend fun countMediaItemsInSession(sessionId: String): Int {
        var total = 0
        var pageToken: String? = null
        do {
            val response = pickerApi.listMediaItems(
                sessionId = sessionId,
                pageSize = 100,
                pageToken = pageToken,
            )
            total += response.mediaItems.orEmpty().size
            pageToken = response.nextPageToken
        } while (!pageToken.isNullOrBlank())
        return total
    }
}
