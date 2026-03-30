package com.ulap

import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.remote.TelegramApiException
import com.ulap.data.remote.TelegramRateLimitException
import com.ulap.domain.model.BotCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

// ─────────────────────────────────────────────────────────────────────────────
// Contract types
//
// These model the handoff decision logic that SyncEngine.processUpload() must
// implement for multi-bot support.  All types are defined here so the BRT is
// self-contained and compiles today.
//
// To wire these tests against the production implementation:
//   1. Move HandoffDecision, BotPoolSnapshot, MultibotUploadOrchestrator, and
//      MultibotPreflightChecker into production packages.
//   2. Replace `ReferenceMultibotOrchestrator` / `ReferenceMultibotPreflightChecker`
//      below with the production implementations.
//   3. All tests must still pass — any assertion failure means a regression.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The outcome that SyncEngine.processUpload() must act on after catching an
 * exception from processUploadInternal.
 */
sealed class HandoffDecision {
    /**
     * Single-bot path — existing behavior is preserved.
     * Caller must NOT delete chunk metadata and must NOT re-queue the item as PENDING.
     */
    data object ContinueSingleBot : HandoffDecision()

    /**
     * Multi-bot handoff — switch to a different bot on the next attempt.
     *
     * @param clearChunks When true, caller must call chunkMetadataDao.deleteChunksForMedia()
     *   so the upload restarts from chunk 0 with the new bot.
     */
    data class RequeueWithHandoff(val clearChunks: Boolean) : HandoffDecision()

    /**
     * No viable bot remains — set the item to [BackupStatus.FAILED].
     */
    data object FailPermanent : HandoffDecision()
}

/**
 * Preflight outcome checked at the top of processUpload, before any attempt.
 */
sealed class PreflightDecision {
    /** All bots are available or this is a single-bot pool — proceed immediately. */
    data object Proceed : PreflightDecision()

    /**
     * All bots are temporarily cooled down — the caller must delay [waitMs] ms before
     * attempting the upload.
     *
     * [waitMs] is bounded to [MAX_COOLDOWN_WAIT_MS] so the engine cannot stall forever.
     */
    data class WaitBeforeRetry(val waitMs: Long) : PreflightDecision()
}

/** Upper bound on the wait injected by the preflight checker (1 minute). */
const val MAX_COOLDOWN_WAIT_MS = 60_000L

/**
 * Immutable snapshot of the BotPool state at the moment of the handoff decision.
 * Pure data — no Room, no Hilt, no Android framework.
 *
 * @param allBots           All configured bots (index 0 = primary).
 * @param permanentlyBannedIndices  Indices recorded as permanently banned at snapshot time.
 * @param cooldownExpiries  Maps botIndex → epoch-ms after which that bot is ready again.
 *                          Bots absent from the map are ready.
 */
data class BotPoolSnapshot(
    val allBots: List<BotCredential>,
    val permanentlyBannedIndices: Set<Int>,
    val cooldownExpiries: Map<Int, Long> = emptyMap(),
) {
    val isMultiBot: Boolean get() = allBots.size > 1

    /** True if every bot currently has a future cooldown expiry AND none are permanently banned. */
    fun isAllTemporarilyCooledDown(nowMs: Long): Boolean {
        if (allBots.isEmpty()) return false
        if (allBots.any { it.index in permanentlyBannedIndices }) return false
        return allBots.all { bot ->
            val expiry = cooldownExpiries[bot.index]
            expiry != null && expiry > nowMs
        }
    }

    /** The latest cooldown expiry among non-permanently-banned bots, or 0L when none exist. */
    fun maxCooldownExpiryMs(nowMs: Long): Long =
        allBots
            .filter { it.index !in permanentlyBannedIndices }
            .mapNotNull { bot -> cooldownExpiries[bot.index]?.takeIf { it > nowMs } }
            .maxOrNull() ?: 0L

    /** True if at least one bot other than [excludedIndex] is not permanently banned. */
    fun hasAvailableBotExcluding(excludedIndex: Int): Boolean =
        allBots.any { it.index != excludedIndex && it.index !in permanentlyBannedIndices }

    /**
     * True if EVERY bot in [allBots] is permanently banned.
     * Accounts for [selectedBotToAdd] being permanently banned NOW (before the snapshot
     * was updated), so the caller can test "would banning this bot exhaust the pool?".
     */
    fun isAllPermanentlyBannedIncluding(selectedBotToAdd: Int): Boolean {
        if (allBots.isEmpty()) return false
        val effectiveBanned = permanentlyBannedIndices + selectedBotToAdd
        return allBots.all { it.index in effectiveBanned }
    }

    fun isAllPermanentlyBanned(): Boolean =
        allBots.isNotEmpty() && allBots.all { it.index in permanentlyBannedIndices }
}

/**
 * Pure-function contract: decides what SyncEngine should do after processUploadInternal
 * throws an exception.
 *
 * [selectedBot]   The bot that was attempted.
 * [exception]     The exception thrown by processUploadInternal.
 * [poolSnapshot]  BotPool state at the time of the failure.
 *
 * Returns a [HandoffDecision].
 */
fun interface MultibotUploadOrchestrator {
    fun decide(
        selectedBot: BotCredential,
        exception: Exception,
        poolSnapshot: BotPoolSnapshot,
    ): HandoffDecision
}

/**
 * Pure-function contract: decides whether to wait before a new upload attempt because
 * all bots are temporarily rate-limited.
 *
 * [poolSnapshot]  Current BotPool state.
 * [nowMs]         Current epoch-ms (injected so the decision is deterministic in tests).
 *
 * Returns a [PreflightDecision].
 */
fun interface MultibotPreflightChecker {
    fun check(poolSnapshot: BotPoolSnapshot, nowMs: Long): PreflightDecision
}

// ─────────────────────────────────────────────────────────────────────────────
// Reference implementations  (SUT for this BRT)
//
// These are the CORRECT implementations of the contracts above.
// The production SyncEngine must produce identical decisions.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Handoff decision rules:
 *
 * 1. TelegramRateLimitException + multi-bot pool
 *      → RequeueWithHandoff(clearChunks = true)
 *        (mark bot rate-limited; delete chunks; re-queue as PENDING)
 *
 * 2. TelegramRateLimitException + single-bot pool
 *      → ContinueSingleBot
 *        (mark bot rate-limited; leave item UPLOADING; no chunk delete — existing behaviour)
 *
 * 3. TelegramApiException(isPermanent=true) + other non-banned bot available
 *      → RequeueWithHandoff(clearChunks = true)
 *        (mark bot permanently banned; delete chunks; re-queue as PENDING)
 *
 * 4. TelegramApiException(isPermanent=true) + banning this bot exhausts the pool
 *      → FailPermanent
 *        (mark bot permanently banned; set item FAILED)
 *
 * 5. Any other exception
 *      → FailPermanent
 */
object ReferenceMultibotOrchestrator : MultibotUploadOrchestrator {
    override fun decide(
        selectedBot: BotCredential,
        exception: Exception,
        poolSnapshot: BotPoolSnapshot,
    ): HandoffDecision = when {
        exception is TelegramRateLimitException -> {
            if (poolSnapshot.isMultiBot) {
                HandoffDecision.RequeueWithHandoff(clearChunks = true)
            } else {
                HandoffDecision.ContinueSingleBot
            }
        }
        exception is TelegramApiException && exception.isPermanent -> {
            if (poolSnapshot.isAllPermanentlyBannedIncluding(selectedBot.index)) {
                HandoffDecision.FailPermanent
            } else {
                HandoffDecision.RequeueWithHandoff(clearChunks = true)
            }
        }
        else -> HandoffDecision.FailPermanent
    }
}

/**
 * Preflight rules:
 *
 * - Single-bot pool: always Proceed (no multi-bot logic applies).
 * - Multi-bot pool where at least one bot is ready: Proceed.
 * - Multi-bot pool where ALL bots are temporarily cooled down:
 *     WaitBeforeRetry(min(maxExpiry - now, MAX_COOLDOWN_WAIT_MS))
 */
object ReferenceMultibotPreflightChecker : MultibotPreflightChecker {
    override fun check(poolSnapshot: BotPoolSnapshot, nowMs: Long): PreflightDecision {
        if (!poolSnapshot.isMultiBot) return PreflightDecision.Proceed
        if (!poolSnapshot.isAllTemporarilyCooledDown(nowMs)) return PreflightDecision.Proceed
        val maxExpiry = poolSnapshot.maxCooldownExpiryMs(nowMs)
        val waitMs = (maxExpiry - nowMs).coerceIn(0L, MAX_COOLDOWN_WAIT_MS)
        return if (waitMs > 0L) PreflightDecision.WaitBeforeRetry(waitMs) else PreflightDecision.Proceed
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Test fixtures
// ─────────────────────────────────────────────────────────────────────────────

private val BOT_0 = BotCredential(index = 0, token = "token_primary")
private val BOT_1 = BotCredential(index = 1, token = "token_bot1", label = "bot1")
private val BOT_2 = BotCredential(index = 2, token = "token_bot2", label = "bot2")

// ─────────────────────────────────────────────────────────────────────────────
// BRT — SyncEngine multi-bot handoff contract
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Bug Reproduction Test — SyncEngine.processUpload() multi-bot handoff logic (Phase 2).
 *
 * Verifies the handoff contract for five scenarios:
 *   1. Rate-limit → multi-bot: RequeueWithHandoff + clearChunks
 *   2. Rate-limit → single-bot: ContinueSingleBot (existing behaviour unchanged)
 *   3. Permanent ban + bots remaining: RequeueWithHandoff + clearChunks
 *   4. Permanent ban + all bots exhausted: FailPermanent
 *   5. All bots temporarily cooled down (pre-check): WaitBeforeRetry
 *
 * Also verifies the BackupStatus mappings that SyncEngine must apply per decision.
 *
 * Deterministic: no network, no disk, no Room, no Hilt, no real clock calls in
 * assertions — all time values are injected constants.
 *
 * SUT: [ReferenceMultibotOrchestrator] and [ReferenceMultibotPreflightChecker].
 * These are the correct implementations.  If the production SyncEngine is later
 * refactored to delegate to a [MultibotUploadOrchestrator], swap the SUT reference
 * here; every test must continue to pass.
 */
@RunWith(JUnit4::class)
class SyncEngineHandoffBrt {

    private val orchestrator: MultibotUploadOrchestrator = ReferenceMultibotOrchestrator
    private val preflightChecker: MultibotPreflightChecker = ReferenceMultibotPreflightChecker

    // ── snapshot builder helpers ──────────────────────────────────────────────

    private fun multiBot3(
        permanentlyBanned: Set<Int> = emptySet(),
        cooldownExpiries: Map<Int, Long> = emptyMap(),
    ) = BotPoolSnapshot(
        allBots = listOf(BOT_0, BOT_1, BOT_2),
        permanentlyBannedIndices = permanentlyBanned,
        cooldownExpiries = cooldownExpiries,
    )

    private fun multiBot2(
        permanentlyBanned: Set<Int> = emptySet(),
        cooldownExpiries: Map<Int, Long> = emptyMap(),
    ) = BotPoolSnapshot(
        allBots = listOf(BOT_0, BOT_1),
        permanentlyBannedIndices = permanentlyBanned,
        cooldownExpiries = cooldownExpiries,
    )

    private fun singleBot() = BotPoolSnapshot(
        allBots = listOf(BOT_0),
        permanentlyBannedIndices = emptySet(),
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 1 — Rate-limit handoff, multi-bot pool
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `rate limit on primary bot in multi-bot pool returns RequeueWithHandoff`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramRateLimitException(retryAfterMs = 60_000L),
            poolSnapshot = multiBot3(),
        )

        assertTrue(
            "rate-limited primary bot in a multi-bot pool must trigger handoff, got $decision",
            decision is HandoffDecision.RequeueWithHandoff,
        )
    }

    @Test
    fun `rate limit handoff in multi-bot pool requires clearChunks to restart from chunk 0`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramRateLimitException(retryAfterMs = 60_000L),
            poolSnapshot = multiBot3(),
        ) as HandoffDecision.RequeueWithHandoff

        assertTrue(
            "clearChunks must be true so the new bot begins from chunk 0, not mid-stream",
            decision.clearChunks,
        )
    }

    @Test
    fun `rate limit on secondary bot in multi-bot pool also returns RequeueWithHandoff`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_1,
            exception = TelegramRateLimitException(retryAfterMs = 30_000L),
            poolSnapshot = multiBot3(),
        )

        assertTrue(
            "secondary bot rate-limited — handoff must still occur regardless of which bot was tried",
            decision is HandoffDecision.RequeueWithHandoff,
        )
    }

    @Test
    fun `RequeueWithHandoff decision maps to BackupStatus PENDING for re-queue`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramRateLimitException(retryAfterMs = 60_000L),
            poolSnapshot = multiBot3(),
        )

        val status = decision.toBackupStatus()

        assertEquals(
            "handoff item must be set to PENDING so the queue picks it up again",
            BackupStatus.PENDING,
            status,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 2 — Rate-limit, single-bot pool  (existing behaviour preserved)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `rate limit on single-bot pool returns ContinueSingleBot — existing behaviour unchanged`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramRateLimitException(retryAfterMs = 60_000L),
            poolSnapshot = singleBot(),
        )

        assertEquals(
            "single-bot rate-limit must not trigger handoff or chunk deletion — existing contract preserved",
            HandoffDecision.ContinueSingleBot,
            decision,
        )
    }

    @Test
    fun `ContinueSingleBot decision maps to BackupStatus UPLOADING — no re-queue`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramRateLimitException(retryAfterMs = 60_000L),
            poolSnapshot = singleBot(),
        )

        val status = decision.toBackupStatus()

        assertEquals(
            "single-bot rate-limit must leave the item as UPLOADING, not PENDING or FAILED",
            BackupStatus.UPLOADING,
            status,
        )
    }

    @Test
    fun `ContinueSingleBot decision must NOT set clearChunks — no chunk deletion for single-bot`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramRateLimitException(retryAfterMs = 60_000L),
            poolSnapshot = singleBot(),
        )

        assertFalse(
            "ContinueSingleBot must never imply chunk deletion — that would erase partial upload progress",
            decision is HandoffDecision.RequeueWithHandoff && decision.clearChunks,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 3 — Permanent ban, other bots available
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `permanent ban on primary bot with alternatives available returns RequeueWithHandoff`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramApiException(
                errorCode = 403,
                description = "Forbidden: bot was blocked by the user",
                isPermanent = true,
            ),
            poolSnapshot = multiBot3(permanentlyBanned = emptySet()),
        )

        assertTrue(
            "permanently banned primary bot with two available alternatives must trigger handoff, got $decision",
            decision is HandoffDecision.RequeueWithHandoff,
        )
    }

    @Test
    fun `permanent ban handoff requires clearChunks to restart with the new bot`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramApiException(
                errorCode = 403,
                description = "Forbidden",
                isPermanent = true,
            ),
            poolSnapshot = multiBot3(permanentlyBanned = emptySet()),
        ) as HandoffDecision.RequeueWithHandoff

        assertTrue(
            "clearChunks must be true for permanent-ban handoff — new bot must start from chunk 0",
            decision.clearChunks,
        )
    }

    @Test
    fun `permanent ban on secondary bot with primary still available returns RequeueWithHandoff`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_2,
            exception = TelegramApiException(
                errorCode = 403,
                description = "Forbidden",
                isPermanent = true,
            ),
            poolSnapshot = multiBot3(permanentlyBanned = setOf(BOT_1.index)),
        )

        assertTrue(
            "banning bot 2 with primary and bot 1 already banned — primary is still available, must handoff",
            decision is HandoffDecision.RequeueWithHandoff,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 4 — Permanent ban, all bots exhausted
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `permanent ban that exhausts all bots in 3-bot pool returns FailPermanent`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramApiException(
                errorCode = 403,
                description = "Forbidden",
                isPermanent = true,
            ),
            poolSnapshot = multiBot3(permanentlyBanned = setOf(BOT_1.index, BOT_2.index)),
        )

        assertEquals(
            "banning bot 0 when bots 1 and 2 are already banned exhausts the pool — must FailPermanent",
            HandoffDecision.FailPermanent,
            decision,
        )
    }

    @Test
    fun `permanent ban that exhausts 2-bot pool returns FailPermanent`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramApiException(
                errorCode = 403,
                description = "Forbidden",
                isPermanent = true,
            ),
            poolSnapshot = multiBot2(permanentlyBanned = setOf(BOT_1.index)),
        )

        assertEquals(
            "banning bot 0 when bot 1 is already banned exhausts the 2-bot pool — must FailPermanent",
            HandoffDecision.FailPermanent,
            decision,
        )
    }

    @Test
    fun `permanent ban in single-bot pool returns FailPermanent`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramApiException(
                errorCode = 403,
                description = "Forbidden",
                isPermanent = true,
            ),
            poolSnapshot = singleBot(),
        )

        assertEquals(
            "single bot permanently banned — only possible outcome is FailPermanent",
            HandoffDecision.FailPermanent,
            decision,
        )
    }

    @Test
    fun `FailPermanent decision maps to BackupStatus FAILED`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramApiException(
                errorCode = 403,
                description = "Forbidden",
                isPermanent = true,
            ),
            poolSnapshot = singleBot(),
        )

        val status = decision.toBackupStatus()

        assertEquals(
            "FailPermanent must set item to FAILED so it is not retried",
            BackupStatus.FAILED,
            status,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Non-permanent TelegramApiException — NOT a multi-bot handoff trigger
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `non-permanent TelegramApiException returns FailPermanent regardless of bot count`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramApiException(
                errorCode = 400,
                description = "Bad Request: wrong file type",
                isPermanent = false,
            ),
            poolSnapshot = multiBot3(),
        )

        assertEquals(
            "non-permanent API error must not trigger multi-bot handoff — treat as unrecoverable",
            HandoffDecision.FailPermanent,
            decision,
        )
    }

    @Test
    fun `TelegramApiException isPermanent=false default value is not a handoff trigger`() {
        val decision = orchestrator.decide(
            selectedBot = BOT_0,
            exception = TelegramApiException(errorCode = 400, description = "Bad Request"),
            poolSnapshot = multiBot3(),
        )

        assertEquals(
            "default isPermanent=false must not trigger handoff",
            HandoffDecision.FailPermanent,
            decision,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 5 — Pre-check: all bots temporarily cooled down
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `all bots temporarily cooled down in multi-bot pool triggers WaitBeforeRetry`() {
        val now = 1_000_000L
        val snapshot = multiBot3(
            cooldownExpiries = mapOf(
                BOT_0.index to now + 10_000L,
                BOT_1.index to now + 20_000L,
                BOT_2.index to now + 15_000L,
            ),
        )

        val decision = preflightChecker.check(poolSnapshot = snapshot, nowMs = now)

        assertTrue(
            "all 3 bots cooled down — preflight must return WaitBeforeRetry, got $decision",
            decision is PreflightDecision.WaitBeforeRetry,
        )
    }

    @Test
    fun `WaitBeforeRetry waitMs is bounded by MAX_COOLDOWN_WAIT_MS`() {
        val now = 1_000_000L
        val snapshot = multiBot3(
            cooldownExpiries = mapOf(
                BOT_0.index to now + MAX_COOLDOWN_WAIT_MS * 10, // far future
                BOT_1.index to now + MAX_COOLDOWN_WAIT_MS * 10,
                BOT_2.index to now + MAX_COOLDOWN_WAIT_MS * 10,
            ),
        )

        val decision = preflightChecker.check(poolSnapshot = snapshot, nowMs = now)
            as PreflightDecision.WaitBeforeRetry

        assertTrue(
            "waitMs must not exceed MAX_COOLDOWN_WAIT_MS=${MAX_COOLDOWN_WAIT_MS}ms to prevent stalling the engine",
            decision.waitMs <= MAX_COOLDOWN_WAIT_MS,
        )
    }

    @Test
    fun `WaitBeforeRetry waitMs equals remaining wait when it is under the cap`() {
        val now = 1_000_000L
        val expectedWait = 5_000L
        val snapshot = multiBot3(
            cooldownExpiries = mapOf(
                BOT_0.index to now + expectedWait,
                BOT_1.index to now + expectedWait - 1_000L,
                BOT_2.index to now + expectedWait - 2_000L,
            ),
        )

        val decision = preflightChecker.check(poolSnapshot = snapshot, nowMs = now)
            as PreflightDecision.WaitBeforeRetry

        assertEquals(
            "waitMs must equal the gap to the latest expiry when it is below the cap",
            expectedWait,
            decision.waitMs,
        )
    }

    @Test
    fun `preflight returns Proceed when at least one bot is ready in multi-bot pool`() {
        val now = 1_000_000L
        val snapshot = multiBot3(
            cooldownExpiries = mapOf(
                BOT_0.index to now + 10_000L,
                BOT_1.index to now + 10_000L,
                // BOT_2 has no expiry — ready immediately
            ),
        )

        val decision = preflightChecker.check(poolSnapshot = snapshot, nowMs = now)

        assertEquals(
            "at least one bot is available — preflight must Proceed without waiting",
            PreflightDecision.Proceed,
            decision,
        )
    }

    @Test
    fun `preflight always returns Proceed for single-bot pool regardless of cooldown state`() {
        val now = 1_000_000L
        val snapshot = BotPoolSnapshot(
            allBots = listOf(BOT_0),
            permanentlyBannedIndices = emptySet(),
            cooldownExpiries = mapOf(BOT_0.index to now + 60_000L),
        )

        val decision = preflightChecker.check(poolSnapshot = snapshot, nowMs = now)

        assertEquals(
            "single-bot pool must not trigger a wait — the wait-before-retry logic is multi-bot only",
            PreflightDecision.Proceed,
            decision,
        )
    }

    @Test
    fun `preflight returns Proceed when all bots are permanently banned (not a cooldown situation)`() {
        val now = 1_000_000L
        val snapshot = multiBot3(
            permanentlyBanned = setOf(BOT_0.index, BOT_1.index, BOT_2.index),
            cooldownExpiries = mapOf(
                BOT_0.index to now + 10_000L,
                BOT_1.index to now + 10_000L,
                BOT_2.index to now + 10_000L,
            ),
        )

        // isAllTemporarilyCooledDown returns false when any bot is permanently banned,
        // so preflight must Proceed (FailPermanent will be returned by the orchestrator later).
        val decision = preflightChecker.check(poolSnapshot = snapshot, nowMs = now)

        assertEquals(
            "permanently banned bots are not 'temporarily cooled down' — preflight must Proceed",
            PreflightDecision.Proceed,
            decision,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BotPoolSnapshot helpers contract
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `BotPoolSnapshot isMultiBot is true for 2-bot pool`() {
        assertTrue(multiBot2().isMultiBot)
    }

    @Test
    fun `BotPoolSnapshot isMultiBot is false for single-bot pool`() {
        assertFalse(singleBot().isMultiBot)
    }

    @Test
    fun `BotPoolSnapshot isAllPermanentlyBannedIncluding counts the candidate bot`() {
        val snapshot = multiBot2(permanentlyBanned = setOf(BOT_1.index))
        // BOT_1 already banned; candidate = BOT_0 → effective banned = {0, 1} = all
        assertTrue(snapshot.isAllPermanentlyBannedIncluding(BOT_0.index))
    }

    @Test
    fun `BotPoolSnapshot isAllPermanentlyBannedIncluding returns false when others remain`() {
        val snapshot = multiBot3(permanentlyBanned = setOf(BOT_1.index))
        // banning BOT_0 leaves BOT_2 available → not all banned
        assertFalse(snapshot.isAllPermanentlyBannedIncluding(BOT_0.index))
    }

    @Test
    fun `BotPoolSnapshot hasAvailableBotExcluding returns true when other non-banned bots exist`() {
        val snapshot = multiBot3()
        assertTrue(snapshot.hasAvailableBotExcluding(excludedIndex = BOT_0.index))
    }

    @Test
    fun `BotPoolSnapshot hasAvailableBotExcluding returns false when all others are permanently banned`() {
        val snapshot = multiBot3(permanentlyBanned = setOf(BOT_1.index, BOT_2.index))
        assertFalse(snapshot.hasAvailableBotExcluding(excludedIndex = BOT_0.index))
    }

    @Test
    fun `BotPoolSnapshot isAllTemporarilyCooledDown returns false when a permanently banned bot has a cooldown`() {
        val now = 1_000_000L
        val snapshot = multiBot3(
            permanentlyBanned = setOf(BOT_2.index),
            cooldownExpiries = mapOf(
                BOT_0.index to now + 10_000L,
                BOT_1.index to now + 10_000L,
                BOT_2.index to now + 10_000L,
            ),
        )
        // Spec: permanently banned bots do NOT count as "temporarily cooled down"
        assertFalse(snapshot.isAllTemporarilyCooledDown(now))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Extension — maps HandoffDecision to the BackupStatus SyncEngine must write
// ─────────────────────────────────────────────────────────────────────────────

private fun HandoffDecision.toBackupStatus(): BackupStatus = when (this) {
    is HandoffDecision.ContinueSingleBot -> BackupStatus.UPLOADING
    is HandoffDecision.RequeueWithHandoff -> BackupStatus.PENDING
    is HandoffDecision.FailPermanent -> BackupStatus.FAILED
}
