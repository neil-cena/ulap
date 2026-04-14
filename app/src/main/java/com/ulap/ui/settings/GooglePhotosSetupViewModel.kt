package com.ulap.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.data.auth.GoogleAuthManager
import com.ulap.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GooglePhotosSetupViewModel @Inject constructor(
    private val googleAuthManager: GoogleAuthManager,
    private val userPrefs: UserPreferencesRepository,
) : ViewModel() {

    val savedClientId: StateFlow<String?> = userPrefs.googlePhotosWebClientId
    val savedClientSecret: StateFlow<String?> = userPrefs.googlePhotosClientSecret

    fun saveCredentials(clientId: String, clientSecret: String) {
        userPrefs.setGooglePhotosWebClientId(clientId.trim())
        userPrefs.setGooglePhotosClientSecret(clientSecret.trim())
    }

    fun clearCredentials() {
        viewModelScope.launch {
            try {
                googleAuthManager.signOut()
            } finally {
                userPrefs.setGooglePhotosWebClientId(null)
                userPrefs.setGooglePhotosClientSecret(null)
            }
        }
    }
}
