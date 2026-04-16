package com.ulap.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthResult {
    data object Success : AuthResult()
    data class Error(val error: String, val errorDescription: String? = null) : AuthResult()
}

data class AuthSession(
    val url: String,
    val codeVerifier: String,
    val server: LoopbackRedirectServer,
)

@Singleton
class GoogleAuthManager @Inject constructor(
    private val tokenStore: OAuthTokenStore,
    private val tokenClient: PkceTokenClient,
) {
    private val _signedInState = MutableStateFlow(tokenStore.hasRefreshToken())
    val signedInState: StateFlow<Boolean> = _signedInState.asStateFlow()
    private val refreshMutex = Mutex()

    fun getAccessToken(): String? = tokenStore.getAccessToken()

    fun isSignedIn(): Boolean = tokenStore.hasRefreshToken()

    /**
     * Starts a loopback HTTP server and builds the OAuth URL.
     * The caller should launch the URL in a browser, then call [awaitAuthResult].
     */
    fun startAuth(clientId: String): AuthSession {
        val server = LoopbackRedirectServer()
        server.start()
        val verifier = PkceUtil.generateCodeVerifier()
        val challenge = PkceUtil.generateCodeChallenge(verifier)
        val url = PkceUtil.buildAuthUrl(clientId, challenge, server.redirectUri)
        return AuthSession(url, verifier, server)
    }

    /**
     * Waits for the browser to redirect to the loopback server,
     * exchanges the auth code for tokens, and stores them.
     */
    suspend fun awaitAuthResult(
        session: AuthSession,
        clientId: String,
        clientSecret: String,
    ): AuthResult = withContext(Dispatchers.IO) {
        try {
            val parsed = session.server.waitForRedirect()
            when (parsed) {
                is RedirectParseResult.Error ->
                    AuthResult.Error(parsed.error, parsed.errorDescription)
                is RedirectParseResult.Success -> {
                    val tokenResult = tokenClient.exchangeCode(
                        code = parsed.code,
                        clientId = clientId,
                        clientSecret = clientSecret,
                        codeVerifier = session.codeVerifier,
                        redirectUri = session.server.redirectUri,
                    )
                    applyTokenResult(tokenResult)
                }
            }
        } finally {
            session.server.stop()
        }
    }

    suspend fun refreshToken(clientId: String, clientSecret: String): Boolean = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            if (!tokenStore.isAccessTokenExpired()) return@withLock true
            val rt = tokenStore.getRefreshToken() ?: return@withLock false
            val result = tokenClient.refreshToken(rt, clientId, clientSecret)
            when (result) {
                is TokenResult.Success -> {
                    val expiresAt = System.currentTimeMillis() + result.expiresInSeconds * 1000L
                    if (result.refreshToken != null) {
                        tokenStore.saveTokens(result.accessToken, result.refreshToken, expiresAt)
                    } else {
                        tokenStore.updateAccessToken(result.accessToken, expiresAt)
                    }
                    true
                }
                is TokenResult.Error -> false
            }
        }
    }

    fun signOut() {
        tokenStore.clearTokens()
        _signedInState.value = false
    }

    fun clearAccessToken() {
        tokenStore.expireAccessToken()
        _signedInState.value = false
    }

    fun notifySignedIn() {
        _signedInState.value = tokenStore.hasRefreshToken()
    }

    private fun applyTokenResult(result: TokenResult): AuthResult {
        return when (result) {
            is TokenResult.Success -> {
                val expiresAt = System.currentTimeMillis() + result.expiresInSeconds * 1000L
                tokenStore.saveTokens(result.accessToken, result.refreshToken, expiresAt)
                _signedInState.value = true
                AuthResult.Success
            }
            is TokenResult.Error -> {
                AuthResult.Error(result.error, result.errorDescription)
            }
        }
    }
}
