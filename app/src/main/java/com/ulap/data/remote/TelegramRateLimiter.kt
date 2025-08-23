package com.ulap.data.remote // rate limiter

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_RETRIES = 5
private const val MIN_SEND_GAP_MS = 1_100L
private const val MAX_BACKOFF_MS = 60_000L

@Singleton
class TelegramRateLimiter @Inject constructor() {

    private val mutex = Mutex()
    private val lastSendTime = AtomicLong(0L)

    // AtomicInteger so concurrent workers can read/write without data races
    private val consecutiveFails = AtomicInteger(0)

    /**
     * Wraps a Telegram API call with:
     * 1. Proactive spacing (≥1.1s between calls) to avoid hitting the rate limit.
     * 2. Reactive retry loop: if a 429 is received, waits the server-prescribed
     *    [retry_after] seconds then retries, up to [MAX_RETRIES] times.
     */
    suspend fun <T> withRateLimit(block: suspend () -> T): T {
        enforceGap()
        var attempt = 0
        while (true) {
            attempt++
            try {
                val result = block()
                consecutiveFails.set(0)
                lastSendTime.set(System.currentTimeMillis())
                return result
            } catch (e: TelegramRateLimitException) {
                if (attempt >= MAX_RETRIES) throw e
                val waitMs = e.retryAfterMs.coerceAtMost(MAX_BACKOFF_MS)
                delay(waitMs)
                consecutiveFails.incrementAndGet()
                lastSendTime.set(System.currentTimeMillis())
                // Re-enforce the gap before the retry attempt
                enforceGap()
            }
        }
    }

    fun recordFailure() {
        consecutiveFails.updateAndGet { (it + 1).coerceAtMost(MAX_RETRIES) }
    }

    fun recordSuccess() {
        consecutiveFails.set(0)
    }

    private suspend fun enforceGap() {
        mutex.withLock {
            val elapsed = System.currentTimeMillis() - lastSendTime.get()
            if (elapsed < MIN_SEND_GAP_MS) {
                delay(MIN_SEND_GAP_MS - elapsed)
            }
        }
    }
}

class TelegramRateLimitException(val retryAfterMs: Long) : Exception("Rate limited for ${retryAfterMs}ms")

class TelegramApiException(val errorCode: Int?, val description: String?) :
    Exception("Telegram API error $errorCode: $description")
