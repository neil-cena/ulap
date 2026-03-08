package com.ulap.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.HttpException

private data class TelegramJsonError(
    @SerializedName("description") val description: String?,
    @SerializedName("error_code") val errorCode: Int?,
)

private val gson = Gson()

/**
 * Text stored on failed media items and shown in the UI.
 * [HttpException] often only exposes "HTTP 400"; Telegram still returns JSON with [description] in the body.
 */
fun Throwable.toUploadErrorDetail(): String {
    (this as? HttpException)?.let { http ->
        val code = http.code()
        val rawBody = http.response()?.errorBody()?.use { it.string() }?.trim().orEmpty()
        if (rawBody.isNotEmpty()) {
            val parsed = runCatching { gson.fromJson(rawBody, TelegramJsonError::class.java) }.getOrNull()
            val desc = parsed?.description?.trim()?.takeIf { it.isNotEmpty() }
            if (desc != null) {
                val ec = parsed.errorCode ?: code
                return "Telegram API error $ec: $desc"
            }
            val safeSnippet = if (rawBody.length <= 800) rawBody else rawBody.take(800) + "…"
            return "HTTP $code: $safeSnippet"
        }
        return message?.takeIf { it.isNotBlank() } ?: "HTTP $code"
    }
    return message?.takeIf { it.isNotBlank() }
        ?: javaClass.simpleName?.takeIf { it.isNotBlank() }
        ?: "Unexpected error"
}
