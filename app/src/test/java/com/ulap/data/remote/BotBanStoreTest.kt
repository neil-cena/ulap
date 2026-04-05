package com.ulap.data.remote

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BotBanStoreTest {

    // Use a real in-memory implementation of SharedPreferences for a full-fidelity test.
    private lateinit var prefs: SharedPreferences
    private lateinit var store: BotBanStore

    @Before
    fun setUp() {
        prefs = InMemorySharedPreferences()
        store = BotBanStore(prefs)
    }

    @Test
    fun loadBans_returnsEmpty_whenNothingStored() {
        assertTrue(store.loadBans().isEmpty())
    }

    @Test
    fun addBan_persists_and_can_be_loaded() {
        store.addBan(botIndex = 2, reason = "Detected banned via getMe")

        val bans = store.loadBans()
        assertEquals(1, bans.size)
        assertEquals("Detected banned via getMe", bans[2]?.reason)
        assertTrue((bans[2]?.timestamp ?: 0L) > 0L)
    }

    @Test
    fun addBan_multipleBots_persists_all() {
        store.addBan(0, "ban 0")
        store.addBan(3, "ban 3")

        val bans = store.loadBans()
        assertEquals(2, bans.size)
        assertEquals("ban 0", bans[0]?.reason)
        assertEquals("ban 3", bans[3]?.reason)
    }

    @Test
    fun removeBan_deletesOnlyTargetEntry() {
        store.addBan(1, "ban 1")
        store.addBan(2, "ban 2")

        store.removeBan(1)

        val bans = store.loadBans()
        assertEquals(1, bans.size)
        assertNull(bans[1])
        assertEquals("ban 2", bans[2]?.reason)
    }

    @Test
    fun clearAll_removesAllEntries() {
        store.addBan(0, "primary banned")
        store.addBan(2, "alt banned")

        store.clearAll()

        assertTrue(store.loadBans().isEmpty())
    }

    @Test
    fun addBan_overwrites_existingEntry() {
        store.addBan(1, "reason A")
        store.addBan(1, "reason B")

        val bans = store.loadBans()
        assertEquals(1, bans.size)
        assertEquals("reason B", bans[1]?.reason)
    }

    // ── In-memory SharedPreferences for hermetic JVM tests ───────────────────

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun edit() = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?) = also { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = also { pending[key] = values }
            override fun putInt(key: String, value: Int) = also { pending[key] = value }
            override fun putLong(key: String, value: Long) = also { pending[key] = value }
            override fun putFloat(key: String, value: Float) = also { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = also { pending[key] = value }
            override fun remove(key: String) = also { removals.add(key) }
            override fun clear() = also { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                data.keys.removeAll(removals)
                data.putAll(pending)
            }
        }

        override fun getAll(): Map<String, *> = data.toMap()
        override fun getString(key: String, defValue: String?) = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int) = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = data[key] as? Boolean ?: defValue
        override fun contains(key: String) = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener) }
    }
}
