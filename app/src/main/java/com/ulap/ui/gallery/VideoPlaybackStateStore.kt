package com.ulap.ui.gallery

import androidx.lifecycle.SavedStateHandle

/**
 * Stores and restores video playback position + play/pause state per media item.
 *
 * Backed by [SavedStateHandle] so values survive process death and Activity
 * recreation from any configuration change (orientation, multi-window, etc.).
 *
 * Key design: per-item keying so the pager can restore position independently
 * for every video in a session without cross-item interference.
 */
class VideoPlaybackStateStore(private val savedStateHandle: SavedStateHandle) {

    fun save(itemId: String, positionMs: Long, isPlaying: Boolean) {
        savedStateHandle["vpos_$itemId"] = positionMs
        savedStateHandle["vplay_$itemId"] = isPlaying
    }

    fun getPosition(itemId: String): Long =
        savedStateHandle.get<Long>("vpos_$itemId") ?: 0L

    fun getIsPlaying(itemId: String): Boolean =
        savedStateHandle.get<Boolean>("vplay_$itemId") ?: true
}
