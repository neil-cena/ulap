package com.ulap.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackRedirectServerTest {

    @Test
    fun `parseRequestLine extracts code from valid GET request`() {
        val result = LoopbackRedirectServer.parseRequestLine(
            "GET /?code=4/0AQSTgQE9yR_AUTH_CODE HTTP/1.1",
        )
        assertTrue("Should be success", result is RedirectParseResult.Success)
        assertEquals("4/0AQSTgQE9yR_AUTH_CODE", (result as RedirectParseResult.Success).code)
    }

    @Test
    fun `parseRequestLine extracts code with additional params`() {
        val result = LoopbackRedirectServer.parseRequestLine(
            "GET /?code=MY_CODE&scope=email+profile HTTP/1.1",
        )
        assertTrue(result is RedirectParseResult.Success)
        assertEquals("MY_CODE", (result as RedirectParseResult.Success).code)
    }

    @Test
    fun `parseRequestLine returns error when error param present`() {
        val result = LoopbackRedirectServer.parseRequestLine(
            "GET /?error=access_denied&error_description=User+denied HTTP/1.1",
        )
        assertTrue(result is RedirectParseResult.Error)
        val err = result as RedirectParseResult.Error
        assertEquals("access_denied", err.error)
        assertEquals("User denied", err.errorDescription)
    }

    @Test
    fun `parseRequestLine returns error when code is missing`() {
        val result = LoopbackRedirectServer.parseRequestLine(
            "GET /?state=xyz HTTP/1.1",
        )
        assertTrue(result is RedirectParseResult.Error)
        assertEquals("missing_code", (result as RedirectParseResult.Error).error)
    }

    @Test
    fun `parseRequestLine returns error on malformed request`() {
        val result = LoopbackRedirectServer.parseRequestLine("GARBAGE")
        assertTrue(result is RedirectParseResult.Error)
    }

    @Test
    fun `parseRequestLine handles root path with no query`() {
        val result = LoopbackRedirectServer.parseRequestLine("GET / HTTP/1.1")
        assertTrue(result is RedirectParseResult.Error)
        assertEquals("missing_code", (result as RedirectParseResult.Error).error)
    }

    @Test
    fun `parseRequestLine handles URL-encoded error description`() {
        val result = LoopbackRedirectServer.parseRequestLine(
            "GET /?error=server_error&error_description=Something%20went%20wrong HTTP/1.1",
        )
        assertTrue(result is RedirectParseResult.Error)
        val err = result as RedirectParseResult.Error
        assertEquals("server_error", err.error)
        assertEquals("Something went wrong", err.errorDescription)
    }

    @Test
    fun `start binds to a port and stop releases it`() {
        val server = LoopbackRedirectServer()
        val port = server.start()
        assertTrue("Port should be positive", port > 0)
        assertTrue("redirectUri should contain port", server.redirectUri == "http://127.0.0.1:$port")
        server.stop()
    }
}
