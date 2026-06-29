package com.streamverse.core.data.remote.premium

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class SourceStatus { ONLINE, OFFLINE, UNKNOWN }

data class SourceHealth(
    val lastCheckedMs: Long = 0L,
    val lastSuccessMs: Long = 0L,
    val lastResponseTimeMs: Long = 0L,
    val avgResponseTimeMs: Double = 0.0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val channelCount: Int = 0,
    val status: SourceStatus = SourceStatus.UNKNOWN,
)

@Singleton
class SourceHealthTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "SourceHealthTracker"
        private const val PREFS = "premium_sources"
        private const val KEY_HEALTH = "source_health_json"
        private const val MAX_CONSECUTIVE_FAILURES = 2
        private const val ALPHA = 0.3
        private const val MAX_ENTRIES = 200
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val healthMap = ConcurrentHashMap<String, SourceHealth>()

    init { load() }

    private fun load() {
        val json = prefs.getString(KEY_HEALTH, null) ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(json, object : TypeToken<Map<String, SourceHealth>>() {}.type)
                as? Map<String, SourceHealth>
            if (map != null) healthMap.putAll(map)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load health data", e)
        }
    }

    private fun save() {
        if (healthMap.size > MAX_ENTRIES) {
            val comparator = compareBy<Map.Entry<String, SourceHealth>> {
                when (it.value.status) { SourceStatus.ONLINE -> 0; SourceStatus.UNKNOWN -> 1; SourceStatus.OFFLINE -> 2 }
            }.thenByDescending<Map.Entry<String, SourceHealth>> { it.value.lastCheckedMs }
                .thenByDescending { it.value.channelCount }
            val sorted = healthMap.entries.sortedWith(comparator)
            val originalSize = sorted.size
            val kept = sorted.take(MAX_ENTRIES).associate { it.key to it.value }
            healthMap.clear()
            healthMap.putAll(kept)
            Log.w(TAG, "Capped health map from $originalSize to $MAX_ENTRIES entries")
        }
        prefs.edit().putString(KEY_HEALTH, gson.toJson(healthMap)).apply()
    }

    fun getHealth(key: String): SourceHealth = healthMap[key] ?: SourceHealth()

    fun getSortedSources(sources: Map<String, String>): List<Pair<String, String>> {
        return sources.entries.map { (key, url) ->
            val health = getHealth(key)
            val priority = when (health.status) {
                SourceStatus.ONLINE -> 0
                SourceStatus.UNKNOWN -> 1
                SourceStatus.OFFLINE -> 2
            }
            Triple(key, url, priority)
        }.sortedWith(
            compareBy<Triple<String, String, Int>> { it.third }
                .thenBy { getHealth(it.first).avgResponseTimeMs }
                .thenByDescending { getHealth(it.first).channelCount }
        ).map { (key, url) -> key to url }
    }

    fun recordProbeResult(key: String, success: Boolean, responseTimeMs: Long) {
        val current = getHealth(key)
        val updated = current.copy(
            lastCheckedMs = System.currentTimeMillis(),
            lastResponseTimeMs = if (success) responseTimeMs else current.lastResponseTimeMs,
            avgResponseTimeMs = if (success) {
                if (current.avgResponseTimeMs == 0.0) responseTimeMs.toDouble()
                else (1.0 - ALPHA) * current.avgResponseTimeMs + ALPHA * responseTimeMs
            } else current.avgResponseTimeMs,
            successCount = current.successCount + (if (success) 1 else 0),
            failureCount = current.failureCount + (if (success) 0 else 1),
            consecutiveFailures = if (success) 0 else current.consecutiveFailures + 1,
            status = if (success) SourceStatus.ONLINE
                else if (current.consecutiveFailures + 1 >= MAX_CONSECUTIVE_FAILURES) SourceStatus.OFFLINE
                else SourceStatus.UNKNOWN,
            lastSuccessMs = if (success) System.currentTimeMillis() else current.lastSuccessMs,
        )
        healthMap[key] = updated
        save()
    }

    fun recordFetchResult(key: String, success: Boolean, channelCount: Int) {
        val current = getHealth(key)
        val updated = current.copy(
            channelCount = if (success) channelCount else current.channelCount,
            lastCheckedMs = System.currentTimeMillis(),
            lastSuccessMs = if (success) System.currentTimeMillis() else current.lastSuccessMs,
            successCount = current.successCount + (if (success) 1 else 0),
            failureCount = current.failureCount + (if (success) 0 else 1),
            consecutiveFailures = if (success) 0 else current.consecutiveFailures + 1,
            status = if (success) SourceStatus.ONLINE
                else if (current.consecutiveFailures + 1 >= MAX_CONSECUTIVE_FAILURES) SourceStatus.OFFLINE
                else SourceStatus.UNKNOWN,
        )
        healthMap[key] = updated
        if (updated.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.w(TAG, "Source marked OFFLINE after $MAX_CONSECUTIVE_FAILURES failures: $key")
        }
        save()
    }

    fun shouldRemove(key: String): Boolean {
        val health = getHealth(key)
        return health.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && key !in defaultSourceKeys
    }

    private val defaultSourceKeys = setOf("movies", "sports", "series", "documentary")

    fun getSummary(): String {
        val counts = healthMap.values.groupBy { it.status }.mapValues { it.value.size }
        return "online=${counts[SourceStatus.ONLINE] ?: 0}, " +
            "offline=${counts[SourceStatus.OFFLINE] ?: 0}, " +
            "unknown=${counts[SourceStatus.UNKNOWN] ?: 0}"
    }
}
