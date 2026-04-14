package com.ulap.testutil

import android.content.SharedPreferences

class InMemorySharedPreferences : SharedPreferences {
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
