package com.ulap.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import com.ulap.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class GooglePhotosSetupViewModel @Inject constructor(
    private val userPrefs: UserPreferencesRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val savedClientId: StateFlow<String?> = userPrefs.googlePhotosWebClientId

    /** SHA-1 fingerprint of the installed APK's first signing certificate, formatted AA:BB:CC:… */
    val signingFingerprint: String by lazy { readSigningFingerprint(context) }

    fun saveClientId(clientId: String) {
        userPrefs.setGooglePhotosWebClientId(clientId.trim())
    }

    fun clearClientId() {
        userPrefs.setGooglePhotosWebClientId(null)
    }
}

@SuppressLint("PackageManagerGetSignatures")
private fun readSigningFingerprint(context: Context): String {
    return try {
        val certBytes: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val signers = info.signingInfo ?: return "unavailable"
            if (signers.hasMultipleSigners()) {
                signers.apkContentsSigners.firstOrNull()?.toByteArray()
            } else {
                signers.signingCertificateHistory.firstOrNull()?.toByteArray()
            } ?: return "unavailable"
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray() ?: return "unavailable"
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(certBytes)
        digest.joinToString(":") { "%02X".format(it) }
    } catch (_: Exception) {
        "unavailable"
    }
}
