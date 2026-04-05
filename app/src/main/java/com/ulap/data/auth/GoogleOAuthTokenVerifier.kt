package com.ulap.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * The only scope that grants access to the Google Photos Picker API for reading user-selected media.
 * The old photoslibrary.readonly scope was removed by Google on March 31, 2025.
 */
private val SCOPES_ALLOWING_PICKER_ACCESS = setOf(
    "https://www.googleapis.com/auth/photospicker.mediaitems.readonly",
)

/**
 * Returns true if [scopeSpaceSeparated] includes a scope that allows Google Photos Picker API access.
 */
fun scopesAllowPhotosPicker(scopeSpaceSeparated: String): Boolean {
    val scopes = scopeSpaceSeparated
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    if (scopes.isEmpty()) return false
    return scopes.any { it in SCOPES_ALLOWING_PICKER_ACCESS }
}

/**
 * Ground-truth check: [GoogleSignIn.hasPermissions] can be true while the access token
 * still lacks the picker scope. Uses https://www.googleapis.com/oauth2/v1/tokeninfo.
 */
suspend fun accessTokenIncludesPickerScope(accessToken: String): Boolean =
    withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext false
        val scopeStr = fetchTokenInfoScopeString(accessToken) ?: return@withContext false
        scopesAllowPhotosPicker(scopeStr)
    }

private suspend fun fetchTokenInfoScopeString(accessToken: String): String? =
    withContext(Dispatchers.IO) {
        val url = URL(
            "https://www.googleapis.com/oauth2/v1/tokeninfo?access_token=" +
                URLEncoder.encode(accessToken, UTF_8.name()),
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: return@withContext null
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) return@withContext null
            runCatching { JSONObject(body).optString("scope", "") }.getOrDefault("")
        } finally {
            conn.disconnect()
        }
    }

/** Safe one-line for logs / Telegram: no token, only scope list or HTTP code. */
suspend fun describeAccessTokenScopesForLogs(accessToken: String): String =
    withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext "empty token"
        val url = URL(
            "https://www.googleapis.com/oauth2/v1/tokeninfo?access_token=" +
                URLEncoder.encode(accessToken, UTF_8.name()),
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) return@withContext "tokeninfo HTTP $code"
            val scopeStr = runCatching { JSONObject(body).optString("scope", "") }.getOrDefault("")
            val ok = scopesAllowPhotosPicker(scopeStr)
            val hint = if (!ok) {
                " — missing photospicker.mediaitems.readonly"
            } else {
                ""
            }
            "tokeninfo scopes: ${scopeStr.ifBlank { "(none)" }}$hint"
        } finally {
            conn.disconnect()
        }
    }
