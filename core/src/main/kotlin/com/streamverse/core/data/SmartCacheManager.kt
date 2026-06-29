package com.streamverse.core.data

import android.content.Context
import android.util.Log
import com.streamverse.core.data.epg.EpgManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class CacheTier(val label: String, val maxAgeMs: Long, val maxBytes: Long) {
    HOT("Active streams & now-playing", 30 * 60 * 1000L, 10L * 1024 * 1024),
    WARM("Channel catalogue & EPG", 2 * 60 * 60 * 1000L, 60L * 1024 * 1024),
    COLD("Search index & offline stash", 24 * 60 * 60 * 1000L, 150L * 1024 * 1024),
}

data class CacheStats(
    val tier: CacheTier,
    val sizeBytes: Long,
    val entryCount: Int,
    val lastEvictionMs: Long,
)

@Singleton
class SmartCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelCacheManager: ChannelCacheManager,
    private val epgManager: EpgManager,
) {
    companion object {
        private const val TAG = "SmartCacheManager"
        private const val PREFS_NAME = "sv_smart_cache"
        private const val KEY_LAST_EVICT = "last_evict_"
        private const val EVICT_INTERVAL_MS = 30 * 60 * 1000L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val hotCache = ConcurrentHashMap<String, Any>()
    private var hotCacheTime: Long = 0L

    fun putHot(key: String, value: Any) {
        if (hotCache.size > 200) {
            val keysToRemove = hotCache.keys.take(50)
            keysToRemove.forEach { hotCache.remove(it) }
        }
        hotCache[key] = value
        hotCacheTime = System.currentTimeMillis()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getHot(key: String): T? {
        if (System.currentTimeMillis() - hotCacheTime > CacheTier.HOT.maxAgeMs) {
            hotCache.clear()
            return null
        }
        return hotCache[key] as? T
    }

    fun tierSizeBytes(tier: CacheTier): Long = when (tier) {
        CacheTier.HOT -> hotCache.entries.sumOf { estimateEntrySize(it.value) }
        CacheTier.WARM -> {
            var size = 0L
            size += channelCacheFileLength()
            size += epgManager.diskCacheSizeBytes()
            size += httpCacheSize()
            size
        }
        CacheTier.COLD -> {
            var size = 0L
            val cacheDir = context.cacheDir
            size += File(cacheDir, "sv_searchable_v1.json").let { if (it.exists()) it.length() else 0L }
            size += deadChannelsCacheSize()
            size += searchHistorySize()
            size
        }
    }

    fun tierStats(tier: CacheTier): CacheStats = CacheStats(
        tier = tier,
        sizeBytes = tierSizeBytes(tier),
        entryCount = when (tier) {
            CacheTier.HOT -> hotCache.size
            CacheTier.WARM -> {
                var count = 0
                if (channelCacheFileLength() > 0) count++
                if (epgManager.diskCacheSizeBytes() > 0) count++
                count
            }
            CacheTier.COLD -> {
                var count = 0
                val searchableFile = File(context.cacheDir, "sv_searchable_v1.json")
                if (searchableFile.exists()) count++
                count
            }
        },
        lastEvictionMs = prefs.getLong(KEY_LAST_EVICT + tier.name, 0L),
    )

    fun allStats(): List<CacheStats> = CacheTier.entries.map { tierStats(it) }

    fun totalSizeBytes(): Long = CacheTier.entries.sumOf { tierSizeBytes(it) }

    fun evict(tier: CacheTier) {
        when (tier) {
            CacheTier.HOT -> {
                hotCache.clear()
                hotCacheTime = 0L
            }
            CacheTier.WARM -> {
                channelCacheManager.invalidate()
                channelCacheManager.invalidateSearchable()
                epgManager.invalidate()
                epgManager.clearDiskCache()
                clearHttpCache()
            }
            CacheTier.COLD -> {
                File(context.cacheDir, "sv_searchable_v1.json").delete()
            }
        }
        prefs.edit().putLong(KEY_LAST_EVICT + tier.name, System.currentTimeMillis()).apply()
        Log.d(TAG, "Evicted ${tier.name} cache")
    }

    fun evictAll() {
        CacheTier.entries.forEach { evict(it) }
    }

    fun runEvictionIfNeeded() {
        val now = System.currentTimeMillis()
        for (tier in CacheTier.entries) {
            val lastEvict = prefs.getLong(KEY_LAST_EVICT + tier.name, 0L)
            if (now - lastEvict > EVICT_INTERVAL_MS) {
                val size = tierSizeBytes(tier)
                if (size > tier.maxBytes) {
                    evict(tier)
                    Log.d(TAG, "Auto-evicted ${tier.name}: ${size / 1024}KB exceeded ${tier.maxBytes / 1024}KB limit")
                }
            }
        }
        evictExpiredHotEntries()
        evictExpiredWarmEntries()
        evictExpiredEpgFiles()
    }

    private fun evictExpiredEpgFiles() {
        val epgDir = File(context.cacheDir, "epg")
        if (!epgDir.exists()) return
        val gzFiles = epgDir.listFiles()?.filter { it.name.endsWith(".gz") } ?: return
        if (gzFiles.size <= 10) return
        gzFiles.sortedByDescending { it.lastModified() }
            .drop(10)
            .forEach { it.delete() }
        Log.d(TAG, "Cleaned up ${gzFiles.size - 10} old EPG files")
    }

    private fun evictExpiredHotEntries() {
        if (hotCache.isNotEmpty() && System.currentTimeMillis() - hotCacheTime > CacheTier.HOT.maxAgeMs) {
            hotCache.clear()
        }
    }

    private fun evictExpiredWarmEntries() {
        val channelFile = File(context.cacheDir, "sv_channels_v9.json")
        if (channelFile.exists() && System.currentTimeMillis() - channelFile.lastModified() > CacheTier.WARM.maxAgeMs) {
            channelCacheManager.invalidate()
        }
        val searchableFile = File(context.cacheDir, "sv_searchable_v1.json")
        if (searchableFile.exists() && System.currentTimeMillis() - searchableFile.lastModified() > CacheTier.COLD.maxAgeMs) {
            searchableFile.delete()
        }
    }

    private fun channelCacheFileLength(): Long {
        val file = File(context.cacheDir, "sv_channels_v9.json")
        return if (file.exists()) file.length() else 0L
    }

    private fun httpCacheSize(): Long {
        val dir = File(context.cacheDir, "http_cache")
        return if (dir.exists()) dir.walkTopDown().sumOf { if (it.isFile) it.length() else 0L } else 0L
    }

    private fun clearHttpCache() {
        val dir = File(context.cacheDir, "http_cache")
        if (dir.exists()) dir.deleteRecursively()
    }

    private fun deadChannelsCacheSize(): Long = 200L

    private fun searchHistorySize(): Long = 500L

    private fun estimateEntrySize(value: Any): Long = when (value) {
        is String -> value.length.toLong()
        is List<*> -> value.size * 512L
        is Map<*, *> -> value.size * 256L
        else -> 1024L
    }
}
