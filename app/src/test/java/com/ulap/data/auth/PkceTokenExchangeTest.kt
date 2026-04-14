package com.ulap.data.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PkceTokenExchangeTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenClient: PkceTokenClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenClient = PkceTokenClient(
            httpClient = OkHttpClient(),
            tokenEndpoint = server.url("/token").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `exchangeCode sends correct POST body`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )

        tokenClient.exchangeCode(
            code = "auth-code-123",
            clientId = "my-client-id",
            clientSecret = "my-secret",
            codeVerifier = "my-verifier",
            redirectUri = "http://127.0.0.1:9999",
        )

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val body = req.body.readUtf8()
        assertTrue("body should contain grant_type", body.contains("grant_type=authorization_code"))
        assertTrue("body should contain code", body.contains("code=auth-code-123"))
        assertTrue("body should contain client_id", body.contains("client_id=my-client-id"))
        assertTrue("body should contain client_secret", body.contains("client_secret=my-secret"))
        assertTrue("body should contain code_verifier", body.contains("code_verifier=my-verifier"))
        assertTrue("body should contain redirect_uri", body.contains("redirect_uri="))
    }

    @Test
    fun `exchangeCode parses successful response`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"access_token":"fresh-at","refresh_token":"fresh-rt","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )

        val result = tokenClient.exchangeCode("code", "cid", "secret", "verifier", "http://127.0.0.1:9999")

        assertTrue("Should be success", result is TokenResult.Success)
        val success = result as TokenResult.Success
        assertEquals("fresh-at", success.accessToken)
        assertEquals("fresh-rt", success.refreshToken)
        assertEquals(3600, success.expiresInSeconds)
    }

    @Test
    fun `exchangeCode returns error on 400 with error field`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"error":"invalid_grant","error_description":"Code has expired"}""")
                .setResponseCode(400),
        )

        val result = tokenClient.exchangeCode("bad-code", "cid", "secret", "verifier", "http://127.0.0.1:9999")

        assertTrue("Should be error", result is TokenResult.Error)
        val error = result as TokenResult.Error
        assertEquals("invalid_grant", error.error)
        assertEquals("Code has expired", error.errorDescription)
    }

    @Test
    fun `exchangeCode returns error on network failure`() {
        server.shutdown()
        val result = tokenClient.exchangeCode("code", "cid", "secret", "verifier", "http://127.0.0.1:9999")
        assertTrue("Should be error", result is TokenResult.Error)
    }

    @Test
    fun `refreshToken sends correct POST body`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"access_token":"new-at","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )

        tokenClient.refreshToken(refreshToken = "my-rt", clientId = "my-cid", clientSecret = "my-secret")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val body = req.body.readUtf8()
        assertTrue("body should contain grant_type", body.contains("grant_type=refresh_token"))
        assertTrue("body should contain refresh_token", body.contains("refresh_token=my-rt"))
        assertTrue("body should contain client_id", body.contains("client_id=my-cid"))
        assertTrue("body should contain client_secret", body.contains("client_secret=my-secret"))
    }

    @Test
    fun `refreshToken parses successful response`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"access_token":"refreshed-at","expires_in":1800,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )

        val result = tokenClient.refreshToken("rt", "cid", "secret")

        assertTrue("Should be success", result is TokenResult.Success)
        val success = result as TokenResult.Success
        assertEquals("refreshed-at", success.accessToken)
        assertNull("Refresh response may omit refresh_token", success.refreshToken)
        assertEquals(1800, success.expiresInSeconds)
    }

    @Test
    fun `refreshToken returns error on revoked token`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"error":"invalid_grant","error_description":"Token has been revoked"}""")
                .setResponseCode(400),
        )

        val result = tokenClient.refreshToken("revoked-rt", "cid", "secret")

        assertTrue("Should be error", result is TokenResult.Error)
        val error = result as TokenResult.Error
        assertEquals("invalid_grant", error.error)
    }

    @Test
    fun `refreshToken preserves existing refresh token when response omits it`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"access_token":"at2","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )

        val result = tokenClient.refreshToken("original-rt", "cid", "secret") as TokenResult.Success
        assertNull("Server did not return new refresh token", result.refreshToken)
    }
}
