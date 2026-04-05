package com.ulap.domain.health

import com.ulap.data.remote.BotBanStore
import com.ulap.data.remote.BotPool
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.sanitizeTokenForPath
import com.ulap.domain.model.BotCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

enum class BotHealthStatus { UNKNOWN, HEALTHY, BANNED, UNREACHABLE }

/**
 * Singleton service responsible for checking the liveness of all configured bots.
 *
 * Detection rules:
 *  - `getMe` responds `ok=true`            → [BotHealthStatus.HEALTHY]
 *  - HTTP 401 or 403                       → [BotHealthStatus.BANNED] (persisted via [BotBanStore])
 *  - Network error / timeout               → [BotHealthStatus.UNREACHABLE] (transient, not persisted)
 *
 * The [healthState] flow is keyed by [BotCredential.index] and emits a fresh snapshot after
 * every [checkAll] or [checkSingle] call.
 *
 * Trigger points are wired up externally:
 *  - App foreground: MainActivity calls [checkAll].
 *  - Permanent ban in SyncEngine: calls [checkSingle] for the affected bot.
 *  - Periodic health-check worker (every 6 hours).
 */
@Singleton
open class BotHealthMonitor @Inject constructor(
    private val api: TelegramBotApi,
    private val botPool: BotPool,
    private val botBanStore: BotBanStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _healthState = MutableStateFlow<Map<Int, BotHealthStatus>>(emptyMap())
    open val healthState: StateFlow<Map<Int, BotHealthStatus>> = _healthState.asStateFlow()

    /**
     * Checks all configured bots in parallel, updating [healthState] and persisting ban state.
     * Safe to call from the main thread (switches to IO internally).
     */
    suspend fun checkAll() {
        val bots = botPool.allBots()
        if (bots.isEmpty()) return
        val results = bots.map { bot ->
            scope.async { bot to probe(bot) }
        }.awaitAll()
        applyResults(results)
    }

    /**
     * Checks a single bot by [botIndex], updating only its slot in [healthState].
     * No-op if the bot is not found in the pool.
     */
    suspend fun checkSingle(botIndex: Int) {
        val bot = botPool.allBots().find { it.index == botIndex } ?: return
        val status = probe(bot)
        applyResults(listOf(bot to status))
    }

    /** Trigger an async check of all bots without waiting for results. */
    fun checkAllAsync() {
        scope.launch { checkAll() }
    }

    /** Returns the current health status for [botIndex], or [BotHealthStatus.UNKNOWN] if unseen. */
    fun statusFor(botIndex: Int): BotHealthStatus =
        _healthState.value[botIndex] ?: BotHealthStatus.UNKNOWN

    private suspend fun probe(bot: BotCredential): BotHealthStatus {
        return try {
            val response = api.getMe(sanitizeTokenForPath(bot.token))
            if (response.ok) BotHealthStatus.HEALTHY else BotHealthStatus.BANNED
        } catch (e: HttpException) {
            when (e.code()) {
                401, 403 -> BotHealthStatus.BANNED
                else -> BotHealthStatus.UNREACHABLE
            }
        } catch (_: Exception) {
            BotHealthStatus.UNREACHABLE
        }
    }

    private fun applyResults(results: List<Pair<BotCredential, BotHealthStatus>>) {
        val updated = _healthState.value.toMutableMap()
        for ((bot, status) in results) {
            updated[bot.index] = status
            if (status == BotHealthStatus.BANNED) {
                botPool.markPermanentlyBanned(bot.index, "Detected banned via getMe")
            }
        }
        _healthState.update { updated }
    }
}
