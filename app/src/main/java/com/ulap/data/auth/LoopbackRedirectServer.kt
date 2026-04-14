package com.ulap.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

/**
 * Lightweight HTTP server that binds to `127.0.0.1` on an ephemeral port and waits
 * for a single OAuth redirect request. Google Desktop-type OAuth clients redirect
 * to `http://127.0.0.1:<port>` which this server intercepts.
 */
class LoopbackRedirectServer {

    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: throw IllegalStateException("Server not started")

    val redirectUri: String
        get() = "http://127.0.0.1:$port"

    fun start(): Int {
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        ss.soTimeout = 300_000 // 5 minute timeout
        serverSocket = ss
        return ss.localPort
    }

    /**
     * Blocks until a single HTTP request arrives, parses the query string for
     * `code` or `error`, sends a minimal HTML response, and returns the result.
     */
    suspend fun waitForRedirect(): RedirectParseResult = withContext(Dispatchers.IO) {
        val ss = serverSocket ?: return@withContext RedirectParseResult.Error(
            "server_not_started", "Loopback server was not started",
        )
        try {
            val socket = ss.accept()
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return@withContext RedirectParseResult.Error(
                    "empty_request", "Empty HTTP request received",
                )

                val result = parseRequestLine(requestLine)

                val html = if (result is RedirectParseResult.Success) {
                    SUCCESS_HTML
                } else {
                    ERROR_HTML
                }
                val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    html
                client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                client.getOutputStream().flush()

                result
            }
        } catch (e: java.net.SocketTimeoutException) {
            RedirectParseResult.Error("timeout", "OAuth redirect timed out")
        } catch (e: Exception) {
            RedirectParseResult.Error("server_error", e.message)
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object {
        internal fun parseRequestLine(requestLine: String): RedirectParseResult {
            // e.g. "GET /?code=AUTH_CODE&scope=... HTTP/1.1"
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                return RedirectParseResult.Error("malformed_request", "Could not parse HTTP request line")
            }
            val path = parts[1] // e.g. "/?code=AUTH_CODE"
            val uri = runCatching { URI("http://localhost$path") }.getOrNull()
                ?: return RedirectParseResult.Error("malformed_uri", "Could not parse redirect path")

            val queryParams = parseQueryString(uri.rawQuery ?: "")

            val error = queryParams["error"]
            if (!error.isNullOrBlank()) {
                return RedirectParseResult.Error(error, queryParams["error_description"])
            }
            val code = queryParams["code"]
            if (code.isNullOrBlank()) {
                return RedirectParseResult.Error("missing_code", "No authorization code in redirect")
            }
            return RedirectParseResult.Success(code)
        }

        private fun parseQueryString(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split("&").mapNotNull { param ->
                val kv = param.split("=", limit = 2)
                if (kv.size == 2) {
                    java.net.URLDecoder.decode(kv[0], "UTF-8") to
                        java.net.URLDecoder.decode(kv[1], "UTF-8")
                } else null
            }.toMap()
        }

        private const val SUCCESS_HTML = """<!DOCTYPE html>
<html><head><title>Ulap</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f5f5f5}
.card{background:white;padding:2rem;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.1);text-align:center;max-width:400px}
h1{color:#1a73e8;font-size:1.5rem}p{color:#555}</style></head>
<body><div class="card"><h1>Sign-in successful</h1><p>You can close this tab and return to Ulap.</p></div></body></html>"""

        private const val ERROR_HTML = """<!DOCTYPE html>
<html><head><title>Ulap</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f5f5f5}
.card{background:white;padding:2rem;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.1);text-align:center;max-width:400px}
h1{color:#d93025;font-size:1.5rem}p{color:#555}</style></head>
<body><div class="card"><h1>Sign-in failed</h1><p>Something went wrong. Please close this tab and try again in Ulap.</p></div></body></html>"""
    }
}
