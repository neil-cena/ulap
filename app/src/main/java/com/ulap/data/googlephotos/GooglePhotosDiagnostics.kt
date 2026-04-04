package com.ulap.data.googlephotos

import retrofit2.HttpException

/**
 * Single-line text for [com.ulap.debug.DebugLogBuffer] / Telegram remote diagnostics (4k Telegram limit).
 */
/** First [retrofit2.HttpException] status in the causal chain, if any. */
fun Throwable.httpStatusCodeOrNull(): Int? =
    generateSequence(this) { it.cause }
        .mapNotNull { t -> (t as? HttpException)?.code() }
        .firstOrNull()

fun formatGooglePhotosDiagnostics(throwable: Throwable): String {
    val head = "${throwable::class.java.simpleName}: ${throwable.message ?: ""}"
    val http = generateSequence(throwable) { it.cause }
        .mapNotNull { t ->
            (t as? HttpException)?.let { ex ->
                val snippet = runCatching {
                    ex.response()?.errorBody()?.string()
                        ?.take(400)
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                }.getOrNull().orEmpty()
                buildString {
                    append("HTTP ").append(ex.code())
                    if (snippet.isNotEmpty()) append(" — ").append(snippet)
                }
            }
        }
        .firstOrNull()
    return if (http != null) "$head | $http" else head
}
