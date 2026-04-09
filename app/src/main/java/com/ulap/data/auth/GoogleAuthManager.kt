package com.ulap.data.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.ExecutionException
import com.ulap.data.repository.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val PHOTOS_PICKER_SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
private const val OAUTH2_SCOPE = "oauth2:$PHOTOS_PICKER_SCOPE"

/** [Activity.onActivityResult] request code for [GoogleSignIn.requestPermissions]. */
const val GOOGLE_PHOTOS_SCOPE_REQUEST_CODE = 99102

sealed class PhotosTokenSyncResult {
    data object Success : PhotosTokenSyncResult()
    /**
     * Play Services reports the Photos scope is not granted for this account.
     * Call [requestPhotosScopePermission], then [syncPhotosAccessTokenFromLastAccount] again.
     */
    data object NeedsScopePermissionRequest : PhotosTokenSyncResult()

    /** Start this intent (e.g. with [Activity.startActivityForResult]) and retry [syncPhotosAccessTokenFromLastAccount]. */
    data class NeedsUserConsentDialog(val consentIntent: Intent) : PhotosTokenSyncResult()

    data class Error(val throwable: Throwable) : PhotosTokenSyncResult()
}

@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefs: UserPreferencesRepository,
) {
    private val accessTokenRef = AtomicReference<String?>(null)

    fun getAccessToken(): String? = accessTokenRef.get()

    fun setAccessToken(token: String?) {
        accessTokenRef.set(token)
    }

    fun clearAccessToken() {
        accessTokenRef.set(null)
    }

    fun getLastSignedInAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    private fun googleSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(PHOTOS_PICKER_SCOPE))
            .apply {
                val clientId = userPrefs.googlePhotosWebClientId.value
                if (!clientId.isNullOrBlank()) {
                    requestIdToken(clientId)
                }
            }
            .build()

    fun googleSignInClient(activity: Activity): GoogleSignInClient =
        GoogleSignIn.getClient(activity, googleSignInOptions())

    fun getSignInIntent(activity: Activity): Intent = googleSignInClient(activity).signInIntent

    /** Email for the last signed-in Google account, if any. */
    fun getLastSignedInAccountEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    /** Triggers the Play Services scope dialog; result must be delivered via [GOOGLE_PHOTOS_SCOPE_REQUEST_CODE]. */
    fun requestPhotosScopePermission(activity: Activity, account: GoogleSignInAccount) {
        GoogleSignIn.requestPermissions(
            activity,
            GOOGLE_PHOTOS_SCOPE_REQUEST_CODE,
            account,
            Scope(PHOTOS_PICKER_SCOPE),
        )
    }

    /** Clears Google Sign-In session and the in-memory OAuth access token. */
    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        val client = GoogleSignIn.getClient(context, googleSignInOptions())
        runCatching { Tasks.await(client.signOut()) }
        accessTokenRef.set(null)
    }

    /**
     * Obtains a Photos Library access token for [account], or explains what UI step is missing.
     * Call after sign-in, after [requestPhotosScopePermission], or after a consent [Intent] result.
     */
    suspend fun syncPhotosAccessTokenForAccount(account: GoogleSignInAccount): PhotosTokenSyncResult =
        withContext(Dispatchers.IO) {
            if (!GoogleSignIn.hasPermissions(account, Scope(PHOTOS_PICKER_SCOPE))) {
                return@withContext PhotosTokenSyncResult.NeedsScopePermissionRequest
            }
            fetchAccessTokenAfterScopeGranted(account)
        }

    suspend fun syncPhotosAccessTokenFromLastAccount(): PhotosTokenSyncResult {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: return PhotosTokenSyncResult.Error(IllegalStateException("no Google account"))
        return syncPhotosAccessTokenForAccount(account)
    }

    private suspend fun fetchAccessTokenAfterScopeGranted(account: GoogleSignInAccount): PhotosTokenSyncResult {
        return try {
            invalidateCachedAccessTokenIfPresent()
            val token = GoogleAuthUtil.getToken(context, account.account!!, OAUTH2_SCOPE)
            if (token.isNullOrBlank()) {
                PhotosTokenSyncResult.Error(IllegalStateException("empty access token"))
            } else if (!accessTokenIncludesPickerScope(token)) {
                val hint = describeAccessTokenScopesForLogs(token)
                accessTokenRef.set(null)
                runCatching { GoogleAuthUtil.clearToken(context, token) }
                PhotosTokenSyncResult.Error(
                    IllegalStateException(
                        "Access token failed tokeninfo check (needs photospicker.mediaitems.readonly). $hint",
                    ),
                )
            } else {
                accessTokenRef.set(token)
                PhotosTokenSyncResult.Success
            }
        } catch (e: UserRecoverableAuthException) {
            val consent = e.intent
            if (consent == null) {
                PhotosTokenSyncResult.Error(IllegalStateException("UserRecoverableAuthException without intent", e))
            } else {
                PhotosTokenSyncResult.NeedsUserConsentDialog(consent)
            }
        } catch (e: Exception) {
            PhotosTokenSyncResult.Error(e)
        }
    }

    /**
     * Refreshes the Photos OAuth access token for the last signed-in account.
     * Invalidates any previously cached token first so new consent/scopes from the server are applied.
     */
    suspend fun refreshTokenFromLastAccount(): Boolean =
        syncPhotosAccessTokenFromLastAccount() is PhotosTokenSyncResult.Success

    suspend fun handleSignInActivityResult(data: Intent?): PhotosTokenSyncResult = withContext(Dispatchers.IO) {
        if (data == null) return@withContext PhotosTokenSyncResult.Error(IllegalStateException("no result data"))
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account: GoogleSignInAccount = Tasks.await(task)
            syncPhotosAccessTokenForAccount(account)
        } catch (e: Exception) {
            val cause = (e as? ExecutionException)?.cause ?: e
            val friendly = (cause as? ApiException)?.let { friendlyApiExceptionMessage(it) }
            PhotosTokenSyncResult.Error(if (friendly != null) Exception(friendly, cause) else cause)
        }
    }

    companion object {
        internal fun friendlyApiExceptionMessage(e: ApiException): String = when (e.statusCode) {
            10 -> "Google Sign-In configuration error (DEVELOPER_ERROR). " +
                "Verify your Web Client ID and SHA-1 fingerprint match your Google Cloud project."
            7 -> "Network error. Check your connection and try again."
            12500 -> "Sign-in failed. Please try again."
            12501 -> "Sign-in cancelled."
            else -> "Google Sign-In failed (error ${e.statusCode})."
        }
    }

    private fun invalidateCachedAccessTokenIfPresent() {
        val previous = accessTokenRef.get()?.takeIf { it.isNotBlank() } ?: return
        runCatching { GoogleAuthUtil.clearToken(context, previous) }
    }
}
