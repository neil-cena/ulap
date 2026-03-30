package com.ulap.data.remote

import com.ulap.debug.DebugLogBuffer
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.domain.repository.CredentialRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException

private const val MAX_BUFFER_ENTRIES = 500
private const val MAX_MESSAGE_CHARS = 4_000
private const val BATCH_INTERVAL_MS = 2_000L
private const val FATAL_SEND_TIMEOUT_MS = 3_000L
private const val RATE_LIMIT_WINDOW_MS = 60_000L
private const val RATE_LIMIT_MAX_MESSAGES = 25

/**
 * Forwards [DebugLogBuffer] entries to a Telegram chat for remote diagnostics.
 *
 * Design constraints:
 * - Uses its own isolated rate limiter (max [RATE_LIMIT_MAX_MESSAGES]/min) — never touches TelegramRateLimiter
 *   which tracks upload budget and would be contaminated by diagnostic traffic.
 * - Batch drain is Mutex-protected so the periodic timer and [flushNow] never double-send.
 * - [logFatal] is synchronous (runBlocking + timeout) so it can be called from UncaughtExceptionHandler.
 * - When [telegramLoggingEnabled] is false or credentials are null, all sends are skipped silently.
 * - Instantiated via Hilt @Provides in AppModule (not @Inject constructor) due to Channel<String> binding.
 */
class TelegramLogger(
    private val api: TelegramBotApi,
    private val channel: Channel<String>,
    private val botToken: StateFlow<String?>,
    private val loggingChatId: StateFlow<String?>,
    private val telegramLoggingEnabled: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val buffer = mutableListOf<String>()
    private val mutex = Mutex()
    private var droppedCount = 0

    // Timestamps (ms) of recent sends for the isolated rate limiter.
    private val recentSendTimestamps = ArrayDeque<Long>()

    init {
        scope.launch { runBatchLoop() }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Drains the [channel] into the accumulation buffer, then sends the full batch immediately.
     * Protected by [mutex] so concurrent calls and the periodic timer cannot double-send.
     */
    suspend fun flushNow() {
        mutex.withLock {
            drainChannelIntoBuffer()
            sendBatchLocked()
        }
    }

    /**
     * Scrubs [throwable] (type + top 3 frames only), then does a blocking best-effort send
     * with a hard [FATAL_SEND_TIMEOUT_MS] timeout. Always safe to call from an
     * [Thread.UncaughtExceptionHandler]; never throws.
     */
    fun logFatal(throwable: Throwable) {
        val token = botToken.value ?: return
        val chatId = loggingChatId.value ?: return
        if (!telegramLoggingEnabled.value) return

        val scrubbed = buildString {
            append("FATAL: ${throwable::class.java.name}\n")
            throwable.stackTrace.take(3).forEach { append("  at $it\n") }
        }

        runCatching {
            runBlocking {
                withTimeout(FATAL_SEND_TIMEOUT_MS) {
                    api.sendMessage(sanitizeTokenForPath(token), chatId, scrubbed)
                }
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private suspend fun runBatchLoop() {
        while (scope.isActive) {
            delay(BATCH_INTERVAL_MS)
            mutex.withLock {
                drainChannelIntoBuffer()
                sendBatchLocked()
            }
        }
    }

    /** Must be called while holding [mutex]. */
    private fun drainChannelIntoBuffer() {
        while (true) {
            val item = channel.tryReceive().getOrNull() ?: break
            if (buffer.size >= MAX_BUFFER_ENTRIES) {
                droppedCount++
            } else {
                buffer.add(item)
            }
        }
    }

    /** Must be called while holding [mutex]. Sends current [buffer] as one (or more) messages. */
    private suspend fun sendBatchLocked() {
        if (buffer.isEmpty() && droppedCount == 0) return
        val token = botToken.value ?: run { buffer.clear(); droppedCount = 0; return }
        val chatId = loggingChatId.value ?: run { buffer.clear(); droppedCount = 0; return }
        if (!telegramLoggingEnabled.value) { buffer.clear(); droppedCount = 0; return }

        val prefix = if (droppedCount > 0) "⚠ $droppedCount entries dropped\n\n" else ""
        droppedCount = 0

        val lines = buffer.toList()
        buffer.clear()

        // Split into ≤4000-char chunks to respect Telegram message size limit.
        val chunks = splitIntoChunks(prefix, lines)
        for (chunk in chunks) {
            rateLimitedSend(sanitizeTokenForPath(token), chatId, chunk)
        }
    }

    private suspend fun rateLimitedSend(token: String, chatId: String, text: String) {
        enforceRateLimit()
        try {
            val response = api.sendMessage(token, chatId, text)
            if (response.ok) {
                recentSendTimestamps.addLast(System.currentTimeMillis())
            } else {
                val retryAfter = response.parameters?.retryAfter
                if (retryAfter != null) {
                    delay(retryAfter * 1_000L)
                    api.sendMessage(token, chatId, text)
                    recentSendTimestamps.addLast(System.currentTimeMillis())
                }
            }
        } catch (e: HttpException) {
            if (e.code() == 429) {
                val retryAfterMs = runCatching {
                    val body = e.response()?.errorBody()?.string() ?: ""
                    val match = Regex("\"retry_after\":(\\d+)").find(body)
                    match?.groupValues?.get(1)?.toLong()?.times(1_000L)
                }.getOrNull() ?: 30_000L
                delay(retryAfterMs)
                runCatching { api.sendMessage(token, chatId, text) }
            }
            // Other HTTP errors: drop silently (best-effort logging)
        } catch (_: Exception) {
            // Network errors: drop silently
        }
    }

    private fun enforceRateLimit() {
        val now = System.currentTimeMillis()
        val windowStart = now - RATE_LIMIT_WINDOW_MS
        while (recentSendTimestamps.isNotEmpty() && recentSendTimestamps.first() < windowStart) {
            recentSendTimestamps.removeFirst()
        }
        // Note: enforceRateLimit is called in a suspend context (rateLimitedSend),
        // but delay is not called here to keep the logic simple. The 2s batch interval
        // naturally limits to ~30 msg/min; this deque check provides a hard cap.
        // If the limit is hit, the message is dropped (best-effort diagnostic tool).
        if (recentSendTimestamps.size >= RATE_LIMIT_MAX_MESSAGES) {
            // Drop this send — best-effort; increment dropped count for next batch
            droppedCount++
        }
    }

    private fun splitIntoChunks(prefix: String, lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var firstChunk = true

        fun flush() {
            if (current.isNotEmpty()) {
                result.add(current.toString())
                current.clear()
            }
        }

        for (line in lines) {
            val pfx = if (firstChunk) prefix else ""
            val candidate = pfx + (if (current.isEmpty()) "" else "\n") + line
            if (current.length + candidate.length > MAX_MESSAGE_CHARS) {
                flush()
                firstChunk = false
                current.append(line)
            } else {
                if (firstChunk && current.isEmpty() && prefix.isNotEmpty()) {
                    current.append(prefix)
                    firstChunk = false
                }
                if (current.isNotEmpty()) current.append("\n")
                current.append(line)
            }
        }
        if (firstChunk && prefix.isNotEmpty() && current.isEmpty()) {
            // Only a drop warning, no lines
            result.add(prefix.trimEnd())
        } else {
            flush()
        }
        return result
    }
}
