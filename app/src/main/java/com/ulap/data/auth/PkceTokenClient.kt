package com.ulap.data.auth

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed class TokenResult {
    data class Success(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Int,
    ) : TokenResult()

    data class Error(
        val error: String,
        val errorDescription: String? = null,
    ) : TokenResult()
}

class PkceTokenClient(
    private val httpClient: OkHttpClient,
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
    private val maxRetries: Int = MAX_NETWORK_RETRIES,
) {
    companion object {
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val MAX_NETWORK_RETRIES = 2
    }

    fun exchangeCode(
        code: String,
        clientId: String,
        clientSecret: String,
        codeVerifier: String,
        redirectUri: String,
    ): TokenResult {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", codeVerifier)
            .build()
        return executeTokenRequest(body)
    }

    fun refreshToken(refreshToken: String, clientId: String, clientSecret: String): TokenResult {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()
        return executeTokenRequest(body)
    }

    private fun executeTokenRequest(body: FormBody): TokenResult {
        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(body)
            .build()

        var lastException: IOException? = null
        repeat(1 + maxRetries) { attempt ->
            try {
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                return if (response.isSuccessful) {
                    val parsed = gson.fromJson(responseBody, TokenResponseDto::class.java)
                    TokenResult.Success(
                        accessToken = parsed.accessToken ?: "",
                        refreshToken = parsed.refreshToken?.takeIf { it.isNotBlank() },
                        expiresInSeconds = parsed.expiresIn ?: 0,
                    )
                } else {
                    val parsed = runCatching {
                        gson.fromJson(responseBody, TokenErrorDto::class.java)
                    }.getOrNull()
                    TokenResult.Error(
                        error = parsed?.error ?: "http_${response.code}",
                        errorDescription = parsed?.errorDescription,
                    )
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    Thread.sleep((attempt + 1) * 1_000L)
                }
            }
        }
        return TokenResult.Error(
            error = "network_error",
            errorDescription = lastException?.message,
        )
    }
}

private val gson = Gson()

private data class TokenResponseDto(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("expires_in") val expiresIn: Int?,
    @SerializedName("token_type") val tokenType: String?,
)

private data class TokenErrorDto(
    @SerializedName("error") val error: String?,
    @SerializedName("error_description") val errorDescription: String?,
)
