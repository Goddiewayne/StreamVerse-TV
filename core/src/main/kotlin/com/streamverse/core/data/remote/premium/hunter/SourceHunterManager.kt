package com.streamverse.core.data.remote.premium.hunter

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class HuntReport(
    val hunterName: String,
    val found: Int,
    val elapsedMs: Long,
    val error: String? = null,
)

data class HuntResult(
    val sources: List<DiscoveredSource>,
    val reports: List<HuntReport>,
)

@Singleton
class SourceHunterManager @Inject constructor(
    private val hunters: List<@JvmSuppressWildcards SourceHunter>,
) {
    companion object {
        private const val TAG = "SourceHunterManager"
        private const val DEFAULT_COOLDOWN_MS = 6L * 60 * 60 * 1000L // 6 hours
        private const val MAX_SOURCES_PER_HUNT = 15
    }

    private val lastHuntTimestamps = mutableMapOf<String, Long>()
    private val knownSourceKeys = mutableSetOf<String>()

    fun registerExistingSources(keys: Set<String>) {
        knownSourceKeys.clear()
        knownSourceKeys.addAll(keys)
    }

    fun isHuntNeeded(hunterName: String): Boolean {
        val last = lastHuntTimestamps[hunterName] ?: 0L
        return (System.currentTimeMillis() - last) > DEFAULT_COOLDOWN_MS
    }

    suspend fun runHuntIfNeeded(force: Boolean = false): HuntResult = withContext(Dispatchers.IO) {
        val eligible = if (force) hunters else hunters.filter { isHuntNeeded(it.name) }
        if (eligible.isEmpty()) {
            Log.d(TAG, "All hunters within cooldown, skipping hunt")
            return@withContext HuntResult(emptyList(), emptyList())
        }

        val allSources = mutableListOf<DiscoveredSource>()
        val reports = mutableListOf<HuntReport>()

        coroutineScope {
            eligible.map { hunter ->
                async {
                    val t0 = System.currentTimeMillis()
                    try {
                        val found = hunter.hunt(HunterConfig(maxResults = MAX_SOURCES_PER_HUNT))
                        val elapsed = System.currentTimeMillis() - t0
                        lastHuntTimestamps[hunter.name] = System.currentTimeMillis()

                        val deduped = found.filter { it.url !in knownSourceKeys.map { k -> k } }
                        synchronized(allSources) { allSources.addAll(deduped) }

                        Log.i(TAG, "${hunter.name}: found ${found.size} (${deduped.size} new) in ${elapsed}ms")
                        synchronized(reports) {
                            reports.add(HuntReport(hunter.name, deduped.size, elapsed))
                        }
                    } catch (e: Exception) {
                        val elapsed = System.currentTimeMillis() - t0
                        Log.w(TAG, "${hunter.name} failed: ${e.message}")
                        synchronized(reports) {
                            reports.add(HuntReport(hunter.name, 0, elapsed, e.message))
                        }
                    }
                }
            }.awaitAll()
        }

        val deduped = allSources.distinctBy { it.url }
            .filter { it.key !in knownSourceKeys }
            .take(MAX_SOURCES_PER_HUNT)

        knownSourceKeys.addAll(deduped.map { it.key })

        Log.i(TAG, "Hunt complete: ${deduped.size} new sources from ${reports.size} hunters")
        HuntResult(deduped, reports)
    }

    suspend fun runHunter(name: String, config: HunterConfig = HunterConfig()): List<DiscoveredSource> =
        withContext(Dispatchers.IO) {
            val hunter = hunters.find { it.name == name }
                ?: return@withContext emptyList()
            val t0 = System.currentTimeMillis()
            try {
                val found = hunter.hunt(config)
                lastHuntTimestamps[name] = System.currentTimeMillis()
                val deduped = found.filter { it.key !in knownSourceKeys }
                knownSourceKeys.addAll(deduped.map { it.key })
                Log.i(TAG, "${hunter.name} (targeted): ${deduped.size} new in ${System.currentTimeMillis() - t0}ms")
                deduped
            } catch (e: Exception) {
                Log.w(TAG, "${hunter.name} (targeted) failed: ${e.message}")
                emptyList()
            }
        }

    fun getHunterNames(): List<String> = hunters.map { it.name }

    fun getLastHuntTimestamps(): Map<String, Long> = lastHuntTimestamps.toMap()
}
