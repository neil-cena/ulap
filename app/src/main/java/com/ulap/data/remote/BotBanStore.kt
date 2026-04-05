package com.ulap.data.remote

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists which bot indices are permanently banned across app restarts.
 *
 * Backed by [EncryptedSharedPreferences] so ban state survives process death and is
 * not readable by other apps. The ban map is serialised as a JSON object keyed by the
 * string form of [BotCredential.index].
 */
@Singleton
class BotBanStore @Inject constructor(
    private val encryptedPrefs: SharedPreferences,
) {
    private val gson = Gson()
    private val lock = Any()

    data class BanInfo(val timestamp: Long, val reason: String)

    /** Returns a snapshot of all currently-persisted bans as a map of botIndex → [BanInfo]. */
    fun loadBans(): Map<Int, BanInfo> {
        val json = encryptedPrefs.getString(KEY_BANNED_BOTS, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, BanInfo>>() {}.type
            val raw: Map<String, BanInfo> = gson.fromJson(json, type) ?: emptyMap()
            raw.mapKeys { it.key.toInt() }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Persists a ban entry for [botIndex]. */
    fun addBan(botIndex: Int, reason: String) {
        synchronized(lock) {
            val current = loadBans().toMutableMap()
            current[botIndex] = BanInfo(timestamp = System.currentTimeMillis(), reason = reason)
            save(current)
        }
    }

    /** Removes a single ban entry (e.g. after a bot is removed from the pool). */
    fun removeBan(botIndex: Int) {
        synchronized(lock) {
            val current = loadBans().toMutableMap()
            current.remove(botIndex)
            save(current)
        }
    }

    /** Clears all persisted ban entries (called when credentials are changed/reset). */
    fun clearAll() {
        encryptedPrefs.edit().remove(KEY_BANNED_BOTS).apply()
    }

    private fun save(bans: Map<Int, BanInfo>) {
        val stringKeyed: Map<String, BanInfo> = bans.mapKeys { it.key.toString() }
        encryptedPrefs.edit().putString(KEY_BANNED_BOTS, gson.toJson(stringKeyed)).apply()
    }

    companion object {
        private const val KEY_BANNED_BOTS = "banned_bots"

        /** Returns a [BotBanStore] backed by a no-op [SharedPreferences] for use in unit tests. */
        fun noOpForTest(): BotBanStore = BotBanStore(NoOpSharedPreferences)

        private object NoOpSharedPreferences : android.content.SharedPreferences {
            override fun getAll() = emptyMap<String, Any>()
            override fun getString(key: String, defValue: String?) = defValue
            override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
            override fun getInt(key: String, defValue: Int) = defValue
            override fun getLong(key: String, defValue: Long) = defValue
            override fun getFloat(key: String, defValue: Float) = defValue
            override fun getBoolean(key: String, defValue: Boolean) = defValue
            override fun contains(key: String) = false
            override fun edit() = NoOpEditor
            override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
            override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
        }

        private object NoOpEditor : android.content.SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = this
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun remove(key: String) = this
            override fun clear() = this
            override fun commit() = true
            override fun apply() {}
        }
    }
}
