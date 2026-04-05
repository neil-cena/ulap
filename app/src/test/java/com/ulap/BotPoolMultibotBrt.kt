package com.ulap

import com.ulap.data.remote.BotBanStore
import com.ulap.data.remote.BotPool
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private class FakeCredentialRepository(
    private val primaryToken: String?,
    private val additionals: List<BotCredential>,
) : CredentialRepository {
    override fun getBotToken(): String? = primaryToken
    override fun getChatId(): String? = null
    override fun saveCredentials(token: String, chatId: String) {}
    override fun clearCredentials() {}
    override fun hasCredentials(): Boolean = primaryToken != null
    override fun getLastIndexFileId(): String? = null
    override fun setLastIndexFileId(fileId: String?) {}
    override fun getAdditionalBotTokens(): List<BotCredential> = additionals
    override fun saveAdditionalBotTokens(bots: List<BotCredential>) {}
    override fun clearAdditionalBots() {}
}

/**
 * Bug Reproduction Test — BotPool multi-bot enhancements (Phase 2 / autonomous-debugging).
 *
 * Tests the following NEW methods that do NOT yet exist on BotPool:
 *   - markPermanentlyBanned(botIndex)
 *   - isPermanentlyBanned(botIndex): Boolean
 *   - isAllPermanentlyBanned(): Boolean
 *   - selectForUploadExcluding(excludeIndices): BotCredential?
 *   - isAllBotsTemporarilyCooledDown(): Boolean
 *   - maxTempCooldownExpiryMs(): Long
 *   - clearCooldowns() must also clear permanentBans
 *   - selectForUpload() must skip permanently banned bots
 *
 * Every test will FAIL (compilation error) until those methods are added to BotPool.
 * Once correctly implemented every test must PASS.
 *
 * Deterministic: no network, no disk, no real clock writes — all time values injected via
 * markRateLimited with a far-future delta. No shared state between tests (@Before resets pool).
 */
@RunWith(JUnit4::class)
class BotPoolMultibotBrt {

    // -------------------------------------------------------------------------
    // Fixture: 3-bot pool — primary (index 0) + two additionals (index 1, 2)
    // -------------------------------------------------------------------------

    private lateinit var pool: BotPool

    private val primaryToken = "token_primary"
    private val additionalBots = listOf(
        BotCredential(index = 1, token = "token_bot1", label = "bot1"),
        BotCredential(index = 2, token = "token_bot2", label = "bot2"),
    )

    @Before
    fun setUp() {
        pool = BotPool(FakeCredentialRepository(primaryToken, additionalBots), BotBanStore.noOpForTest())
    }

    // -------------------------------------------------------------------------
    // markPermanentlyBanned + isPermanentlyBanned
    // -------------------------------------------------------------------------

    @Test
    fun `markPermanentlyBanned stores ban and isPermanentlyBanned returns true`() {
        assertFalse("bot 1 should not be banned before any call", pool.isPermanentlyBanned(1))

        pool.markPermanentlyBanned(1)

        assertTrue("bot 1 should be permanently banned after markPermanentlyBanned", pool.isPermanentlyBanned(1))
    }

    @Test
    fun `isPermanentlyBanned returns false for unbanned bots`() {
        pool.markPermanentlyBanned(1)

        assertFalse("bot 0 was not banned", pool.isPermanentlyBanned(0))
        assertFalse("bot 2 was not banned", pool.isPermanentlyBanned(2))
    }

    @Test
    fun `permanentBan is independent of cooldowns — markRateLimited does not create a permanent ban`() {
        pool.markRateLimited(0, retryAfterMs = 60_000L)

        assertFalse("a rate-limited bot is NOT permanently banned", pool.isPermanentlyBanned(0))
    }

    // -------------------------------------------------------------------------
    // isAllPermanentlyBanned
    // -------------------------------------------------------------------------

    @Test
    fun `isAllPermanentlyBanned returns false when no bots are banned`() {
        assertFalse(pool.isAllPermanentlyBanned())
    }

    @Test
    fun `isAllPermanentlyBanned returns false when only some bots are banned`() {
        pool.markPermanentlyBanned(0)
        pool.markPermanentlyBanned(1)
        // bot 2 not banned

        assertFalse(pool.isAllPermanentlyBanned())
    }

    @Test
    fun `isAllPermanentlyBanned returns true only when every bot is banned`() {
        pool.markPermanentlyBanned(0)
        pool.markPermanentlyBanned(1)
        pool.markPermanentlyBanned(2)

        assertTrue(pool.isAllPermanentlyBanned())
    }

    // -------------------------------------------------------------------------
    // selectForUploadExcluding
    // -------------------------------------------------------------------------

    @Test
    fun `selectForUploadExcluding with empty exclude set returns a bot`() {
        val selected = pool.selectForUploadExcluding(emptySet())

        assertNotNull("should return a bot when nothing is excluded", selected)
    }

    @Test
    fun `selectForUploadExcluding excludes requested indices`() {
        val excludeSet = setOf(0, 1)

        val selected = pool.selectForUploadExcluding(excludeSet)

        assertNotNull("bot 2 is available and not excluded", selected)
        assertEquals("only bot 2 should be returned", 2, selected!!.index)
    }

    @Test
    fun `selectForUploadExcluding returns null when all non-excluded bots are cooled down`() {
        // Exclude bot 0, cool down bots 1 and 2
        pool.markRateLimited(1, retryAfterMs = 300_000L)
        pool.markRateLimited(2, retryAfterMs = 300_000L)

        val selected = pool.selectForUploadExcluding(setOf(0))

        assertNull("all remaining bots are cooled down", selected)
    }

    @Test
    fun `selectForUploadExcluding returns null when all bots are excluded`() {
        val selected = pool.selectForUploadExcluding(setOf(0, 1, 2))

        assertNull("all bots excluded — nothing to return", selected)
    }

    @Test
    fun `selectForUploadExcluding skips permanently banned bots even if not in exclude set`() {
        pool.markPermanentlyBanned(0)
        pool.markPermanentlyBanned(1)

        // Only bot 2 is available and not banned
        val selected = pool.selectForUploadExcluding(emptySet())

        assertNotNull("bot 2 is not banned and not excluded", selected)
        assertEquals("should return bot 2", 2, selected!!.index)
    }

    @Test
    fun `selectForUploadExcluding returns null when all bots are permanently banned`() {
        pool.markPermanentlyBanned(0)
        pool.markPermanentlyBanned(1)
        pool.markPermanentlyBanned(2)

        val selected = pool.selectForUploadExcluding(emptySet())

        assertNull("no bots available when all are permanently banned", selected)
    }

    // -------------------------------------------------------------------------
    // isAllBotsTemporarilyCooledDown
    // -------------------------------------------------------------------------

    @Test
    fun `isAllBotsTemporarilyCooledDown returns false when no bots are cooled down`() {
        assertFalse(pool.isAllBotsTemporarilyCooledDown())
    }

    @Test
    fun `isAllBotsTemporarilyCooledDown returns false when only some bots are cooled down`() {
        pool.markRateLimited(0, retryAfterMs = 300_000L)
        pool.markRateLimited(1, retryAfterMs = 300_000L)
        // bot 2 not cooled down

        assertFalse(pool.isAllBotsTemporarilyCooledDown())
    }

    @Test
    fun `isAllBotsTemporarilyCooledDown returns true when all bots have a future cooldown`() {
        pool.markRateLimited(0, retryAfterMs = 300_000L)
        pool.markRateLimited(1, retryAfterMs = 300_000L)
        pool.markRateLimited(2, retryAfterMs = 300_000L)

        assertTrue(pool.isAllBotsTemporarilyCooledDown())
    }

    @Test
    fun `isAllBotsTemporarilyCooledDown returns false when a permanently banned bot has a cooldown entry`() {
        // All three are rate-limited, but bot 2 is also permanently banned.
        // The contract: permanently banned bots are NOT counted as "temporarily cooled down".
        // So if bot 2 is perm-banned and bots 0+1 are temporarily cooled down,
        // the result should still be false because not all NON-banned bots are cooled down? 
        // Actually per spec: true "when ALL bots have a future cooldown AND none are permanently banned"
        pool.markRateLimited(0, retryAfterMs = 300_000L)
        pool.markRateLimited(1, retryAfterMs = 300_000L)
        pool.markRateLimited(2, retryAfterMs = 300_000L)
        pool.markPermanentlyBanned(2)

        assertFalse(
            "when any bot is permanently banned the state is not 'all temporarily cooled down'",
            pool.isAllBotsTemporarilyCooledDown()
        )
    }

    // -------------------------------------------------------------------------
    // maxTempCooldownExpiryMs
    // -------------------------------------------------------------------------

    @Test
    fun `maxTempCooldownExpiryMs returns 0 when no bots have cooldowns`() {
        assertEquals(0L, pool.maxTempCooldownExpiryMs())
    }

    @Test
    fun `maxTempCooldownExpiryMs returns the largest expiry among cooled-down bots`() {
        val now = System.currentTimeMillis()
        pool.markRateLimited(0, retryAfterMs = 60_000L)   // expires ~now+60s
        pool.markRateLimited(1, retryAfterMs = 120_000L)  // expires ~now+120s — highest
        pool.markRateLimited(2, retryAfterMs = 30_000L)   // expires ~now+30s

        val maxExpiry = pool.maxTempCooldownExpiryMs()

        // Should be roughly now + 120_000ms; allow ±2 s for test execution drift
        assertTrue("max expiry must be ≥ now+118s", maxExpiry >= now + 118_000L)
        assertTrue("max expiry must be ≤ now+122s", maxExpiry <= now + 122_000L)
    }

    @Test
    fun `maxTempCooldownExpiryMs excludes permanently banned bots`() {
        val now = System.currentTimeMillis()
        // Bot 0 has the longest cooldown but is permanently banned — must be excluded
        pool.markRateLimited(0, retryAfterMs = 300_000L)
        pool.markPermanentlyBanned(0)
        pool.markRateLimited(1, retryAfterMs = 60_000L)

        val maxExpiry = pool.maxTempCooldownExpiryMs()

        // Must reflect bot 1's expiry (~now+60s), NOT bot 0's banned entry
        assertTrue("max expiry must be ≥ now+58s (bot 1)", maxExpiry >= now + 58_000L)
        assertTrue("max expiry must be ≤ now+62s (bot 1)", maxExpiry <= now + 62_000L)
    }

    // -------------------------------------------------------------------------
    // clearCooldowns clears permanent bans
    // -------------------------------------------------------------------------

    @Test
    fun `clearCooldowns also clears permanent bans`() {
        pool.markPermanentlyBanned(0)
        pool.markPermanentlyBanned(1)
        pool.markRateLimited(2, retryAfterMs = 300_000L)

        pool.clearCooldowns()

        assertFalse("permanent ban on bot 0 must be cleared", pool.isPermanentlyBanned(0))
        assertFalse("permanent ban on bot 1 must be cleared", pool.isPermanentlyBanned(1))
        assertEquals("cooldown expiry map must be empty after clear", 0L, pool.maxTempCooldownExpiryMs())
    }

    // -------------------------------------------------------------------------
    // selectForUpload skips permanently banned bots
    // -------------------------------------------------------------------------

    @Test
    fun `selectForUpload never returns a permanently banned bot`() {
        pool.markPermanentlyBanned(0)
        pool.markPermanentlyBanned(1)

        // Run several rounds to exhaust round-robin positions
        repeat(6) {
            val selected = pool.selectForUpload()
            assertNotNull("must return a non-null bot while bot 2 is available", selected)
            assertEquals("must only return the non-banned bot 2", 2, selected!!.index)
        }
    }

    @Test
    fun `selectForUpload returns null when all bots are permanently banned`() {
        pool.markPermanentlyBanned(0)
        pool.markPermanentlyBanned(1)
        pool.markPermanentlyBanned(2)

        val selected = pool.selectForUpload()

        assertNull("no bots available when all are permanently banned", selected)
    }
}
