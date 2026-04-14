package com.ulap.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class PkceAuthManagerTest {

    @Test
    fun `code verifier length is between 43 and 128 characters`() {
        val verifier = PkceUtil.generateCodeVerifier()
        assertTrue(
            "Verifier length ${verifier.length} should be >= 43",
            verifier.length >= 43,
        )
        assertTrue(
            "Verifier length ${verifier.length} should be <= 128",
            verifier.length <= 128,
        )
    }

    @Test
    fun `code verifier only contains URL-safe characters`() {
        val verifier = PkceUtil.generateCodeVerifier()
        val allowed = Regex("^[A-Za-z0-9\\-._~]+$")
        assertTrue(
            "Verifier should only contain unreserved characters: $verifier",
            allowed.matches(verifier),
        )
    }

    @Test
    fun `code verifier is random across invocations`() {
        val a = PkceUtil.generateCodeVerifier()
        val b = PkceUtil.generateCodeVerifier()
        assertNotEquals("Two verifiers should not be identical", a, b)
    }

    @Test
    fun `code challenge is base64url-encoded SHA-256 of verifier without padding`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = PkceUtil.generateCodeChallenge(verifier)

        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

        assertEquals(expected, challenge)
        assertFalse("Challenge should not contain padding '='", challenge.contains("="))
    }

    @Test
    fun `code challenge uses known RFC 7636 test vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = PkceUtil.generateCodeChallenge(verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun `buildAuthUrl contains all required query parameters`() {
        val clientId = "test-client-id.apps.googleusercontent.com"
        val verifier = PkceUtil.generateCodeVerifier()
        val challenge = PkceUtil.generateCodeChallenge(verifier)
        val redirectUri = "http://127.0.0.1:12345"

        val url = PkceUtil.buildAuthUrl(clientId, challenge, redirectUri)

        assertTrue("URL should contain client_id", url.contains("client_id=$clientId"))
        assertTrue("URL should contain redirect_uri", url.contains("redirect_uri="))
        assertTrue("URL should contain response_type=code", url.contains("response_type=code"))
        assertTrue("URL should contain scope with photospicker", url.contains("photospicker.mediaitems.readonly"))
        assertTrue("URL should contain code_challenge", url.contains("code_challenge=$challenge"))
        assertTrue("URL should contain code_challenge_method=S256", url.contains("code_challenge_method=S256"))
        assertTrue("URL should contain access_type=offline", url.contains("access_type=offline"))
        assertTrue("URL should contain prompt=consent", url.contains("prompt=consent"))
    }

    @Test
    fun `buildAuthUrl starts with Google OAuth endpoint`() {
        val url = PkceUtil.buildAuthUrl("test-id", "test-challenge", "http://127.0.0.1:9999")
        assertTrue(
            "URL should start with Google OAuth2 endpoint",
            url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"),
        )
    }

    @Test
    fun `buildAuthUrl uses provided redirect URI`() {
        val url = PkceUtil.buildAuthUrl("test-id", "test-challenge", "http://127.0.0.1:8080")
        assertTrue(
            "URL should contain the loopback redirect URI",
            url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8080") ||
                url.contains("redirect_uri=http://127.0.0.1:8080"),
        )
    }
}
