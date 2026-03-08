package com.ulap.data.remote

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_RETRIES = 5
private const val MIN_SEND_GAP_MS = 1_100L
private const val MAX_BACKOFF_MS = 60_000L

@Singleton
class TelegramRateLimiter @Inject constructor() {

    /**
     * Monotonically-increasing timestamp: the earliest wall-clock time at which the next
     * Telegram API call may be dispatched. Each caller atomically claims a slot by advancing
     * this value forward by MIN_SEND_GAP_MS, then sleeps until their reserved slot arrives.
     *
     * This replaces the previous Mutex-based enforceGap(), which serialized ALL concurrent
     * workers through a single lock — making "3 small-file workers" effectively sequential.
     * With slot reservation, all workers queue up concurrently and sleep in parallel:
     *   Worker 1 → slot T+0      (fires immediately)
     *   Worker 2 → slot T+1.1 s  (sleeps 1.1 s independently)
     *   Worker 3 → slot T+2.2 s  (sleeps 2.2 s independently)
     *   Worker 4 → slot T+3.3 s  (sleeps 3.3 s independently)
     * Maximum Telegram throughput is maintained; workers are no longer blocked by each other.
     */
    private val nextAllowedSendTime = AtomicLong(0L)
    private val consecutiveFails = AtomicInteger(0)

    /**
     * Wraps a Telegram API call with:
     * 1. Proactive spacing (≥1.1 s between calls) via atomic slot reservation.
     * 2. Reactive retry loop: on 429, waits the server-prescribed retry_after, then retries.
     */
    suspend fun <T> withRateLimit(block: suspend () -> T): T {
        awaitSlot()
        var attempt = 0
        while (true) {
            attempt++
            try {
                val result = block()
                consecutiveFails.set(0)
                return result
            } catch (e: TelegramRateLimitException) {
                if (attempt >= MAX_RETRIES) throw e
                val waitMs = e.retryAfterMs.coerceAtMost(MAX_BACKOFF_MS)
                // Push the shared slot forward so other workers also back off during the flood.
                nextAllowedSendTime.updateAndGet { maxOf(it, System.currentTimeMillis() + waitMs) }
                delay(waitMs)
                consecutiveFails.incrementAndGet()
                // Re-claim a slot for the retry.
                awaitSlot()
            }
        }
    }

    fun recordFailure() {
        consecutiveFails.updateAndGet { (it + 1).coerceAtMost(MAX_RETRIES) }
    }

    fun recordSuccess() {
        consecutiveFails.set(0)
    }

    /**
     * Atomically reserves the next available send slot and delays until it arrives.
     * Multiple coroutines calling this concurrently each get a distinct slot and sleep
     * in parallel — no coroutine blocks another from reserving its own slot.
     */
    private suspend fun awaitSlot() {
        val now = System.currentTimeMillis()
        // Claim: advance nextAllowedSendTime by MIN_SEND_GAP_MS, but never schedule in the past.
        val mySlot = nextAllowedSendTime.getAndUpdate { last ->
            maxOf(last, now) + MIN_SEND_GAP_MS
        }
        // mySlot is the time this caller is allowed to send. Wait if it's in the future.
        val waitMs = mySlot - System.currentTimeMillis()
        if (waitMs > 0) delay(waitMs)
    }
}

class TelegramRateLimitException(val retryAfterMs: Long) : Exception("Rate limited for ${retryAfterMs}ms")

class TelegramApiException(val errorCode: Int?, val description: String?, val isPermanent: Boolean = false) :
    Exception("Telegram API error $errorCode: $description")
