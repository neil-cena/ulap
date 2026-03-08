package com.ulap.data.remote

import com.ulap.data.repository.UploadSpeedMode
import com.ulap.data.repository.UserPreferencesRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ── Proactive spacing ────────────────────────────────────────────────────────
private const val BASE_GAP_MS = 1_500L          // baseline gap between API calls
private const val CONSERVATIVE_GAP_MS = 3_000L  // gap in Conservative upload mode
private const val MAX_GAP_MS = 10_000L          // adaptive cap

// ── Retry ────────────────────────────────────────────────────────────────────
private const val MAX_RETRIES = 5

// ── Circuit breaker ──────────────────────────────────────────────────────────
private const val CIRCUIT_429_WINDOW_MS = 5 * 60 * 1_000L  // 5-minute observation window
private const val CIRCUIT_429_THRESHOLD = 3                  // 429s in window to trip OPEN
private const val CIRCUIT_COOLDOWN_MS = 120_000L             // 2-minute OPEN cooldown
private const val CIRCUIT_HALF_OPEN_SUCCESSES = 10           // successes needed to close

// ── Hourly budget ────────────────────────────────────────────────────────────
private const val BUDGET_WINDOW_MS = 60 * 60 * 1_000L  // 1-hour rolling window
private const val BUDGET_SOFT_LIMIT = 1_200             // double gap above this count
private const val BUDGET_HARD_LIMIT = 1_500             // enforce pause above this count
private const val BUDGET_HARD_PAUSE_MS = 5 * 60 * 1_000L

// ── Adaptive recovery ────────────────────────────────────────────────────────
private const val RECOVERY_SUCCESS_THRESHOLD = 30  // successes to decrement fail count by 1

/** Reason the uploader is running slower than normal, exposed to the UI. */
enum class ThrottleReason { NONE, ADAPTIVE_SLOWDOWN, CIRCUIT_OPEN, BUDGET_LIMIT }

/** Internal state machine of the circuit breaker. */
enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

/** Snapshot of the rate limiter's throttle state, suitable for UI display. */
data class ThrottleState(
    val isThrottled: Boolean = false,
    val reason: ThrottleReason = ThrottleReason.NONE,
    /** Wall-clock ms when normal speed is expected to resume; 0 if unknown. */
    val resumeAtMs: Long = 0L,
    val currentGapMs: Long = BASE_GAP_MS,
    val messagesThisHour: Int = 0,
)

@Singleton
class TelegramRateLimiter @Inject constructor(
    private val userPrefs: UserPreferencesRepository,
) {
    /** Earliest wall-clock time at which the next API call may be dispatched. */
    private val nextAllowedSendTime = AtomicLong(0L)

    /**
     * How many consecutive 429s have been observed.
     * Increases the inter-call gap via the adaptive formula; recovers gradually after successes.
     */
    private val consecutiveFails = AtomicInteger(0)
    private val successesSinceLastFail = AtomicInteger(0)

    // ── All mutable circuit-breaker / budget state is guarded by stateLock ───
    private val stateLock = Any()
    private var circuitState = CircuitState.CLOSED
    private var circuitOpenUntilMs = 0L
    private var halfOpenSuccessCount = 0
    private val recent429Timestamps = ArrayDeque<Long>()   // last 10 entries
    private val sentMessageTimestamps = ArrayDeque<Long>() // rolling 1-hour window
    private var budgetPauseUntilMs = 0L

    private val _throttleState = MutableStateFlow(ThrottleState(currentGapMs = BASE_GAP_MS))
    val throttleState: StateFlow<ThrottleState> = _throttleState.asStateFlow()

    /**
     * Wraps a Telegram API call with:
     *  1. Adaptive slot reservation (≥ 1.5 s, scales up under pressure)
     *  2. Circuit breaker suspension (2-min pause when 3+ 429s in 5 min)
     *  3. Hourly budget enforcement (5-min pause at 1,500 msgs/hr)
     *  4. Reactive 429 retry loop (up to [MAX_RETRIES] times with server retry_after)
     */
    suspend fun <T> withRateLimit(block: suspend () -> T): T {
        awaitSlot()
        var attempt = 0
        while (true) {
            attempt++
            try {
                val result = block()
                onCallSuccess()
                return result
            } catch (e: TelegramRateLimitException) {
                onRateLimit429(e.retryAfterMs)
                if (attempt >= MAX_RETRIES) throw e
                val waitMs = e.retryAfterMs.coerceAtMost(60_000L)
                // Push the shared slot forward so all workers back off together.
                nextAllowedSendTime.updateAndGet { maxOf(it, System.currentTimeMillis() + waitMs) }
                delay(waitMs)
                awaitSlot()
            }
        }
    }

    /** Called externally when a non-rate-limit failure occurs (e.g. network error). */
    fun recordFailure() {
        consecutiveFails.updateAndGet { (it + 1).coerceAtMost(MAX_RETRIES) }
        successesSinceLastFail.set(0)
        refreshThrottleState()
    }

    /** Called externally to record a success when using a path that bypasses withRateLimit. */
    fun recordSuccess() {
        onCallSuccess()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun onCallSuccess() {
        // Gradual recovery: after RECOVERY_SUCCESS_THRESHOLD consecutive successes,
        // decrement consecutiveFails by 1 instead of resetting it to 0 instantly.
        val s = successesSinceLastFail.incrementAndGet()
        if (s >= RECOVERY_SUCCESS_THRESHOLD) {
            consecutiveFails.updateAndGet { if (it > 0) it - 1 else 0 }
            successesSinceLastFail.set(0)
        }
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            if (circuitState == CircuitState.HALF_OPEN) {
                halfOpenSuccessCount++
                if (halfOpenSuccessCount >= CIRCUIT_HALF_OPEN_SUCCESSES) {
                    circuitState = CircuitState.CLOSED
                    halfOpenSuccessCount = 0
                }
            }
            sentMessageTimestamps.addLast(now)
            evictOldBudgetEntries(now)
        }
        refreshThrottleState()
    }

    private fun onRateLimit429(retryAfterMs: Long) {
        consecutiveFails.updateAndGet { (it + 1).coerceAtMost(MAX_RETRIES) }
        successesSinceLastFail.set(0)
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            recent429Timestamps.addLast(now)
            // Keep only the most recent 10 entries.
            while (recent429Timestamps.size > 10) recent429Timestamps.removeFirst()
            // Count how many 429s fell in the circuit window.
            val windowStart = now - CIRCUIT_429_WINDOW_MS
            val recentCount = recent429Timestamps.count { it >= windowStart }
            when {
                recentCount >= CIRCUIT_429_THRESHOLD && circuitState == CircuitState.CLOSED -> {
                    circuitState = CircuitState.OPEN
                    circuitOpenUntilMs = now + CIRCUIT_COOLDOWN_MS
                    halfOpenSuccessCount = 0
                }
                circuitState == CircuitState.HALF_OPEN -> {
                    // Any 429 while probing sends the circuit back to OPEN.
                    circuitState = CircuitState.OPEN
                    circuitOpenUntilMs = now + CIRCUIT_COOLDOWN_MS
                    halfOpenSuccessCount = 0
                }
            }
        }
        refreshThrottleState()
    }

    /**
     * Reserves the next adaptive send slot and delays until it arrives.
     * Also enforces circuit breaker suspension and budget hard-limit pausing.
     */
    private suspend fun awaitSlot() {
        awaitBudget()
        awaitCircuit()
        val gap = currentGapMs()
        val now = System.currentTimeMillis()
        val mySlot = nextAllowedSendTime.getAndUpdate { last -> maxOf(last, now) + gap }
        val waitMs = mySlot - System.currentTimeMillis()
        if (waitMs > 0) delay(waitMs)
    }

    private suspend fun awaitBudget() {
        val pauseUntil: Long
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            evictOldBudgetEntries(now)
            if (sentMessageTimestamps.size >= BUDGET_HARD_LIMIT && budgetPauseUntilMs <= now) {
                budgetPauseUntilMs = now + BUDGET_HARD_PAUSE_MS
            }
            pauseUntil = budgetPauseUntilMs
        }
        val waitMs = pauseUntil - System.currentTimeMillis()
        if (waitMs > 0) {
            refreshThrottleState()
            delay(waitMs)
            refreshThrottleState()
        }
    }

    private suspend fun awaitCircuit() {
        val waitMs: Long
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            if (circuitState == CircuitState.OPEN && now >= circuitOpenUntilMs) {
                circuitState = CircuitState.HALF_OPEN
                halfOpenSuccessCount = 0
            }
            waitMs = if (circuitState == CircuitState.OPEN) {
                (circuitOpenUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            } else 0L
        }
        if (waitMs > 0) {
            refreshThrottleState()
            delay(waitMs)
            // Transition to HALF_OPEN after cooldown.
            synchronized(stateLock) {
                if (circuitState == CircuitState.OPEN && System.currentTimeMillis() >= circuitOpenUntilMs) {
                    circuitState = CircuitState.HALF_OPEN
                    halfOpenSuccessCount = 0
                }
            }
            refreshThrottleState()
        }
    }

    private fun currentGapMs(): Long {
        val baseGap = when (userPrefs.uploadSpeedMode.value) {
            UploadSpeedMode.CONSERVATIVE -> CONSERVATIVE_GAP_MS
            else -> BASE_GAP_MS
        }
        val fails = consecutiveFails.get()
        // Each consecutive fail adds 50% of baseGap (e.g. 2 fails → baseGap * 2.0).
        val adaptiveGap = (baseGap * (1.0 + fails * 0.5)).toLong().coerceAtMost(MAX_GAP_MS)
        // Double gap when messages/hr is approaching the soft limit.
        val msgCount = synchronized(stateLock) { sentMessageTimestamps.size }
        return if (msgCount >= BUDGET_SOFT_LIMIT) (adaptiveGap * 2).coerceAtMost(MAX_GAP_MS)
        else adaptiveGap
    }

    /** Must be called inside stateLock. */
    private fun evictOldBudgetEntries(now: Long) {
        while (sentMessageTimestamps.isNotEmpty() &&
            now - sentMessageTimestamps.first() > BUDGET_WINDOW_MS
        ) {
            sentMessageTimestamps.removeFirst()
        }
    }

    private fun refreshThrottleState() {
        val now = System.currentTimeMillis()
        val circuit: CircuitState
        val openUntil: Long
        val budgetPause: Long
        val msgCount: Int
        synchronized(stateLock) {
            if (circuitState == CircuitState.OPEN && now >= circuitOpenUntilMs) {
                circuitState = CircuitState.HALF_OPEN
                halfOpenSuccessCount = 0
            }
            circuit = circuitState
            openUntil = circuitOpenUntilMs
            budgetPause = budgetPauseUntilMs
            evictOldBudgetEntries(now)
            msgCount = sentMessageTimestamps.size
        }
        val fails = consecutiveFails.get()
        val gapMs = currentGapMs()
        val reason: ThrottleReason
        val resumeAtMs: Long
        when {
            circuit == CircuitState.OPEN -> {
                reason = ThrottleReason.CIRCUIT_OPEN
                resumeAtMs = openUntil
            }
            budgetPause > now -> {
                reason = ThrottleReason.BUDGET_LIMIT
                resumeAtMs = budgetPause
            }
            fails > 0 || msgCount >= BUDGET_SOFT_LIMIT -> {
                reason = ThrottleReason.ADAPTIVE_SLOWDOWN
                resumeAtMs = 0L
            }
            else -> {
                reason = ThrottleReason.NONE
                resumeAtMs = 0L
            }
        }
        _throttleState.value = ThrottleState(
            isThrottled = reason != ThrottleReason.NONE,
            reason = reason,
            resumeAtMs = resumeAtMs,
            currentGapMs = gapMs,
            messagesThisHour = msgCount,
        )
    }
}

class TelegramRateLimitException(val retryAfterMs: Long) : Exception("Rate limited for ${retryAfterMs}ms")

class TelegramApiException(val errorCode: Int?, val description: String?, val isPermanent: Boolean = false) :
    Exception("Telegram API error $errorCode: $description")
