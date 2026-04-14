package com.ulap.data.auth

import com.ulap.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OAuthTokenStoreTest {

    private lateinit var store: OAuthTokenStore

    @Before
    fun setUp() {
        store = OAuthTokenStore(InMemorySharedPreferences())
    }

    @Test
    fun `save and load access token`() {
        store.saveTokens(accessToken = "at123", refreshToken = "rt456", expiresAtMillis = Long.MAX_VALUE)
        assertEquals("at123", store.getAccessToken())
    }

    @Test
    fun `save and load refresh token`() {
        store.saveTokens(accessToken = "at", refreshToken = "rt789", expiresAtMillis = Long.MAX_VALUE)
        assertEquals("rt789", store.getRefreshToken())
    }

    @Test
    fun `clear tokens removes all values`() {
        store.saveTokens(accessToken = "at", refreshToken = "rt", expiresAtMillis = Long.MAX_VALUE)
        store.clearTokens()
        assertNull(store.getAccessToken())
        assertNull(store.getRefreshToken())
    }

    @Test
    fun `getAccessToken returns null when expired`() {
        val pastMillis = System.currentTimeMillis() - 60_000
        store.saveTokens(accessToken = "expired-at", refreshToken = "rt", expiresAtMillis = pastMillis)
        assertNull("Expired token should return null", store.getAccessToken())
    }

    @Test
    fun `getAccessToken returns token when not expired`() {
        val futureMillis = System.currentTimeMillis() + 600_000
        store.saveTokens(accessToken = "valid-at", refreshToken = "rt", expiresAtMillis = futureMillis)
        assertEquals("valid-at", store.getAccessToken())
    }

    @Test
    fun `isAccessTokenExpired returns true for past expiry`() {
        val pastMillis = System.currentTimeMillis() - 1
        store.saveTokens(accessToken = "at", refreshToken = "rt", expiresAtMillis = pastMillis)
        assertTrue(store.isAccessTokenExpired())
    }

    @Test
    fun `isAccessTokenExpired returns false for future expiry`() {
        val futureMillis = System.currentTimeMillis() + 600_000
        store.saveTokens(accessToken = "at", refreshToken = "rt", expiresAtMillis = futureMillis)
        assertFalse(store.isAccessTokenExpired())
    }

    @Test
    fun `isAccessTokenExpired returns true when no tokens stored`() {
        assertTrue(store.isAccessTokenExpired())
    }

    @Test
    fun `getRefreshToken returns null when no tokens stored`() {
        assertNull(store.getRefreshToken())
    }

    @Test
    fun `getRawAccessToken returns token even when expired`() {
        val pastMillis = System.currentTimeMillis() - 60_000
        store.saveTokens(accessToken = "expired-at", refreshToken = "rt", expiresAtMillis = pastMillis)
        assertEquals("expired-at", store.getRawAccessToken())
    }

    @Test
    fun `hasRefreshToken returns true when stored`() {
        store.saveTokens(accessToken = "at", refreshToken = "rt", expiresAtMillis = Long.MAX_VALUE)
        assertTrue(store.hasRefreshToken())
    }

    @Test
    fun `hasRefreshToken returns false when cleared`() {
        store.clearTokens()
        assertFalse(store.hasRefreshToken())
    }

    @Test
    fun `updateAccessToken replaces only the access token and expiry`() {
        store.saveTokens(accessToken = "old-at", refreshToken = "keep-rt", expiresAtMillis = 1000L)
        store.updateAccessToken(accessToken = "new-at", expiresAtMillis = 2000L)
        assertEquals("new-at", store.getRawAccessToken())
        assertEquals("keep-rt", store.getRefreshToken())
    }
}
