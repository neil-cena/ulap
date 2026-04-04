package com.ulap.data.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.ulap.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val PHOTOS_READONLY_SCOPE = "https://www.googleapis.com/auth/photoslibrary.readonly"
private const val OAUTH2_SCOPE = "oauth2:$PHOTOS_READONLY_SCOPE"

@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val accessTokenRef = AtomicReference<String?>(null)

    fun getAccessToken(): String? = accessTokenRef.get()

    fun setAccessToken(token: String?) {
        accessTokenRef.set(token)
    }

    fun clearAccessToken() {
        accessTokenRef.set(null)
    }

    private fun googleSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(PHOTOS_READONLY_SCOPE))
            .apply {
                if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
                    requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                }
            }
            .build()

    fun googleSignInClient(activity: Activity): GoogleSignInClient =
        GoogleSignIn.getClient(activity, googleSignInOptions())

    fun getSignInIntent(activity: Activity): Intent = googleSignInClient(activity).signInIntent

    /** Email for the last signed-in Google account, if any. */
    fun getLastSignedInAccountEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    /** Clears Google Sign-In session and the in-memory OAuth access token. */
    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        val client = GoogleSignIn.getClient(context, googleSignInOptions())
        runCatching { Tasks.await(client.signOut()) }
        accessTokenRef.set(null)
    }

    suspend fun refreshTokenFromLastAccount(): Boolean = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext false
        runCatching {
            val token = GoogleAuthUtil.getToken(context, account.account!!, OAUTH2_SCOPE)
            accessTokenRef.set(token)
        }.isSuccess
    }

    suspend fun handleSignInActivityResult(data: Intent?): Result<Unit> = withContext(Dispatchers.IO) {
        if (data == null) return@withContext Result.failure(IllegalStateException("no result data"))
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account: GoogleSignInAccount = Tasks.await(task)
            val token = GoogleAuthUtil.getToken(context, account.account!!, OAUTH2_SCOPE)
            accessTokenRef.set(token)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
