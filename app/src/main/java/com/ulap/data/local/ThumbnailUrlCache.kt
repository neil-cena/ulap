package com.ulap.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val CACHE_FILE_NAME = "ulap_thumb_urls.json"

@Singleton
class ThumbnailUrlCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()
    private val type = object : TypeToken<Map<String, String>>() {}.type

    @Volatile
    private var cache: Map<String, String>? = null

    private val file: File
        get() = File(context.cacheDir, CACHE_FILE_NAME)

    private fun loadFromDisk(): Map<String, String> {
        return synchronized(this) {
            cache?.let { return it }
            val f = file
            if (!f.exists()) {
                emptyMap<String, String>().also { cache = it }
            } else {
                try {
                    val json = f.readText(Charsets.UTF_8)
                    @Suppress("UNCHECKED_CAST")
                    (gson.fromJson(json, type) as? Map<String, String> ?: emptyMap()).also { cache = it }
                } catch (_: Exception) {
                    emptyMap<String, String>().also { cache = it }
                }
            }
        }
    }

    fun get(itemId: String): String? = loadFromDisk()[itemId]

    /** Returns a snapshot of all cached URLs (e.g. to pre-populate UI state). */
    fun getAll(): Map<String, String> = loadFromDisk()

    suspend fun put(itemId: String, url: String) = withContext(Dispatchers.IO) {
        val updated = synchronized(this@ThumbnailUrlCache) {
            val current = loadFromDisk()
            (current + (itemId to url)).also { cache = it }
        }
        try {
            file.writeText(gson.toJson(updated), Charsets.UTF_8)
        } catch (_: Exception) { }
    }
}
