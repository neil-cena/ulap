package com.ulap.ui.gallery

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VideoPlaybackStateStore].
 *
 * ## Contract
 * - [VideoPlaybackStateStore.save] persists both position and play-state for an item id.
 * - [VideoPlaybackStateStore.getPosition] returns 0 when nothing was saved.
 * - [VideoPlaybackStateStore.getIsPlaying] returns true (play) when nothing was saved.
 * - Multiple item ids are stored independently (no cross-item interference).
 * - A second [save] for the same id overwrites the first.
 */
class VideoPlaybackStateStoreTest {

    private fun newStore() = VideoPlaybackStateStore(SavedStateHandle())

    // ── defaults ──────────────────────────────────────────────────────────────

    @Test
    fun getPosition_returnsZero_whenNothingSaved() {
        assertEquals(0L, newStore().getPosition("unknown-item"))
    }

    @Test
    fun getIsPlaying_returnsTrue_whenNothingSaved() {
        assertTrue(newStore().getIsPlaying("unknown-item"))
    }

    // ── round-trip ────────────────────────────────────────────────────────────

    @Test
    fun save_thenGetPosition_returnsCorrectMs() {
        val store = newStore()
        store.save("item-1", 42_000L, true)
        assertEquals(42_000L, store.getPosition("item-1"))
    }

    @Test
    fun save_thenGetIsPlaying_returnsCorrectState_whenPaused() {
        val store = newStore()
        store.save("item-1", 1000L, false)
        assertFalse(store.getIsPlaying("item-1"))
    }

    @Test
    fun save_thenGetIsPlaying_returnsCorrectState_whenPlaying() {
        val store = newStore()
        store.save("item-1", 1000L, true)
        assertTrue(store.getIsPlaying("item-1"))
    }

    // ── independence ──────────────────────────────────────────────────────────

    @Test
    fun save_multipleItems_storesEachIndependently() {
        val store = newStore()
        store.save("item-A", 1_000L, false)
        store.save("item-B", 99_000L, true)

        assertEquals(1_000L, store.getPosition("item-A"))
        assertFalse(store.getIsPlaying("item-A"))

        assertEquals(99_000L, store.getPosition("item-B"))
        assertTrue(store.getIsPlaying("item-B"))
    }

    // ── overwrite ─────────────────────────────────────────────────────────────

    @Test
    fun save_twice_overwritesPreviousValues() {
        val store = newStore()
        store.save("item-1", 1_000L, true)
        store.save("item-1", 55_000L, false)

        assertEquals(55_000L, store.getPosition("item-1"))
        assertFalse(store.getIsPlaying("item-1"))
    }
}
