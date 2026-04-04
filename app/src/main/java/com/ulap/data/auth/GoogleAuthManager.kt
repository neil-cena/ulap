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

    fun googleSignInClient(activity: Activity): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(PHOTOS_READONLY_SCOPE))
            .apply {
                if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
                    requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                }
            }
            .build()
        return GoogleSignIn.getClient(activity, gso)
    }

    fun getSignInIntent(activity: Activity): Intent = googleSignInClient(activity).signInIntent

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
