package com.ulap.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val CACHE_FILE_NAME = "ulap_thumb_urls.json"
private const val MAX_ENTRIES = 500
private const val TTL_MS = 12L * 60 * 60 * 1000 // 12 hours — Telegram CDN URLs expire within a day

@Singleton
class ThumbnailUrlCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private data class CacheEntry(
        @SerializedName("u") val url: String,
        @SerializedName("t") val cachedAt: Long,
    )

    private val gson = Gson()
    private val newType = object : TypeToken<Map<String, CacheEntry>>() {}.type
    private val legacyType = object : TypeToken<Map<String, String>>() {}.type

    @Volatile
    private var cache: Map<String, CacheEntry>? = null

    private val file: File
        get() = File(context.cacheDir, CACHE_FILE_NAME)

    private fun loadFromDisk(): Map<String, CacheEntry> {
        return synchronized(this) {
            val existing = cache
            if (existing != null) return@synchronized existing
            val f = file
            if (!f.exists()) {
                emptyMap<String, CacheEntry>().also { cache = it }
            } else {
                try {
                    val json = f.readText(Charsets.UTF_8)
                    val parsed = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        gson.fromJson(json, newType) as? Map<String, CacheEntry>
                    }.getOrNull()

                    if (parsed != null) {
                        evict(parsed).also { cache = it }
                    } else {
                        // Migrate legacy format: Map<String, String> → treat all as immediately
                        // expired so entries are replaced on next access with fresh TTL timestamps.
                        @Suppress("UNCHECKED_CAST")
                        val legacy = runCatching {
                            gson.fromJson(json, legacyType) as? Map<String, String>
                        }.getOrNull() ?: emptyMap()
                        val migrated = legacy.mapValues { (_, url) -> CacheEntry(url, cachedAt = 0L) }
                        evict(migrated).also { cache = it }
                    }
                } catch (_: Exception) {
                    emptyMap<String, CacheEntry>().also { cache = it }
                }
            }
        }
    }

    private fun evict(map: Map<String, CacheEntry>): Map<String, CacheEntry> {
        val now = System.currentTimeMillis()
        val fresh = map.filterValues { now - it.cachedAt < TTL_MS }
        return if (fresh.size > MAX_ENTRIES) {
            fresh.entries
                .sortedByDescending { it.value.cachedAt }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        } else {
            fresh
        }
    }

    fun get(itemId: String): String? {
        val entry = loadFromDisk()[itemId] ?: return null
        // Re-check TTL in case the in-memory cache has aged since last evict() ran.
        val age = System.currentTimeMillis() - entry.cachedAt
        return if (age < TTL_MS) entry.url else null
    }

    /** Returns a snapshot of all non-expired cached URLs. */
    fun getAll(): Map<String, String> {
        val now = System.currentTimeMillis()
        return loadFromDisk()
            .filterValues { now - it.cachedAt < TTL_MS }
            .mapValues { it.value.url }
    }

    suspend fun put(itemId: String, url: String) = withContext(Dispatchers.IO) {
        val updated = synchronized(this@ThumbnailUrlCache) {
            val current = loadFromDisk()
            val newEntry = CacheEntry(url, cachedAt = System.currentTimeMillis())
            evict(current + (itemId to newEntry)).also { cache = it }
        }
        try {
            file.writeText(gson.toJson(updated), Charsets.UTF_8)
        } catch (_: Exception) { }
    }

    /** Evicts expired and overflow entries from the in-memory cache and writes to disk. */
    suspend fun evictExpired() = withContext(Dispatchers.IO) {
        val evicted = synchronized(this@ThumbnailUrlCache) {
            evict(loadFromDisk()).also { cache = it }
        }
        try {
            file.writeText(gson.toJson(evicted), Charsets.UTF_8)
        } catch (_: Exception) { }
    }
}
