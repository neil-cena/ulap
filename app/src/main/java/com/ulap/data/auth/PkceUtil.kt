package com.ulap.data.auth

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

sealed class RedirectParseResult {
    data class Success(val code: String) : RedirectParseResult()
    data class Error(val error: String, val errorDescription: String? = null) : RedirectParseResult()
}

object PkceUtil {

    private const val PHOTOS_PICKER_SCOPE =
        "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val VERIFIER_LENGTH = 64

    private val UNRESERVED_CHARS: CharArray =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')).toCharArray()

    fun generateCodeVerifier(): String {
        val random = SecureRandom()
        return CharArray(VERIFIER_LENGTH) { UNRESERVED_CHARS[random.nextInt(UNRESERVED_CHARS.size)] }
            .concatToString()
    }

    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun buildAuthUrl(clientId: String, codeChallenge: String, redirectUri: String): String {
        val params = mapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to PHOTOS_PICKER_SCOPE,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent",
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, Charsets.UTF_8.name())}"
        }
        return "$AUTH_ENDPOINT?$query"
    }
}
