package com.ulap.data.auth

import com.ulap.testutil.InMemorySharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleAuthManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: OAuthTokenStore
    private lateinit var tokenClient: PkceTokenClient
    private lateinit var authManager: GoogleAuthManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = OAuthTokenStore(InMemorySharedPreferences())
        tokenClient = PkceTokenClient(
            httpClient = OkHttpClient(),
            tokenEndpoint = server.url("/token").toString(),
        )
        authManager = GoogleAuthManager(tokenStore, tokenClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getAccessToken returns valid token from store when not expired`() {
        val future = System.currentTimeMillis() + 600_000
        tokenStore.saveTokens("valid-at", "rt", future)
        assertEquals("valid-at", authManager.getAccessToken())
    }

    @Test
    fun `getAccessToken returns null when no tokens stored`() {
        assertNull(authManager.getAccessToken())
    }

    @Test
    fun `startAuth returns session with URL and loopback server`() {
        val session = authManager.startAuth("my-client-id")
        try {
            assertTrue(session.url.contains("client_id=my-client-id"))
            assertTrue(session.url.contains("code_challenge="))
            assertTrue(session.url.contains("redirect_uri="))
            assertTrue(session.url.contains("127.0.0.1"))
            assertTrue(session.codeVerifier.length in 43..128)
            assertTrue(session.server.port > 0)
        } finally {
            session.server.stop()
        }
    }

    @Test
    fun `signOut clears tokens`() {
        tokenStore.saveTokens("at", "rt", Long.MAX_VALUE)
        authManager.signOut()
        assertNull(authManager.getAccessToken())
        assertNull(tokenStore.getRefreshToken())
    }

    @Test
    fun `refreshToken succeeds with valid refresh token`() = runTest {
        tokenStore.saveTokens("old-at", "valid-rt", System.currentTimeMillis() - 1000)
        server.enqueue(
            MockResponse()
                .setBody("""{"access_token":"refreshed-at","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )

        val result = authManager.refreshToken("my-cid", "my-secret")

        assertTrue("Should succeed", result)
        assertEquals("refreshed-at", authManager.getAccessToken())
        assertEquals("valid-rt", tokenStore.getRefreshToken())
    }

    @Test
    fun `refreshToken fails when no refresh token stored`() = runTest {
        val result = authManager.refreshToken("my-cid", "my-secret")
        assertFalse("Should fail", result)
    }

    @Test
    fun `refreshToken fails on server error`() = runTest {
        tokenStore.saveTokens("old-at", "expired-rt", System.currentTimeMillis() - 1000)
        server.enqueue(
            MockResponse()
                .setBody("""{"error":"invalid_grant","error_description":"Token revoked"}""")
                .setResponseCode(400),
        )

        val result = authManager.refreshToken("my-cid", "my-secret")
        assertFalse("Should fail", result)
    }

    @Test
    fun `isSignedIn returns true when tokens exist`() {
        tokenStore.saveTokens("at", "rt", Long.MAX_VALUE)
        assertTrue(authManager.isSignedIn())
    }

    @Test
    fun `isSignedIn returns false when no tokens`() {
        assertFalse(authManager.isSignedIn())
    }

    @Test
    fun `signedInState emits changes`() = runTest {
        assertFalse(authManager.signedInState.first())
        tokenStore.saveTokens("at", "rt", Long.MAX_VALUE)
        authManager.notifySignedIn()
        assertTrue(authManager.signedInState.first())
    }
}
