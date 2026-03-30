package com.ulap.debug

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Maximum entries kept in the ring buffer. Older entries are dropped. */
private const val MAX_ENTRIES = 200

@Singleton
class DebugLogBuffer @Inject constructor() {

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    /**
     * Secondary tap for [com.ulap.data.remote.TelegramLogger].
     * Non-suspend [log] calls [trySend] so callers are never blocked.
     * Capacity=64 with DROP_OLDEST: when the consumer is slow or disconnected,
     * the oldest buffered lines are silently discarded rather than blocking the caller.
     */
    val newEntryChannel: Channel<String> = Channel(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun log(tag: String, message: String) {
        Log.d(tag, message)
        val line = "${fmt.format(Date())} [$tag] $message"
        val current = _entries.value
        _entries.value = if (current.size >= MAX_ENTRIES) {
            current.drop(current.size - MAX_ENTRIES + 1) + line
        } else {
            current + line
        }
        newEntryChannel.trySend(line)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
