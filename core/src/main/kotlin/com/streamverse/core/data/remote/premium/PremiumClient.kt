package com.streamverse.core.data.remote.premium

import android.content.Context
import android.util.Log
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.local.DiscoveredSourceDao
import com.streamverse.core.data.local.DiscoveredSourceEntity
import com.streamverse.core.data.remote.m3u.M3uParser
import com.streamverse.core.data.remote.premium.hunter.DiscoveredSource
import com.streamverse.core.data.remote.premium.hunter.SourceHunterManager
import com.streamverse.core.util.StreamVerseDispatchers
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class PremiumChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val source: String,
    val headers: Map<String, String> = emptyMap(),
    val drmKeyId: String? = null,
    val drmKey: String? = null,
)

/**
 * Fetches premium channel streams from dynamically managed playlist sources.
 * Source URLs are persisted in Room and periodically validated and refreshed
 * via GitHub search and source hunters — so the channel catalogue self-heals.
 * Integrates with [ChannelHealthEngine] to trigger source hunting when
 * channel health degradation is detected across many channels.
 */
@Singleton
class PremiumClient @Inject constructor(
    private val dispatchers: StreamVerseDispatchers,
    private val healthTracker: SourceHealthTracker,
    private val sourceHunterManager: SourceHunterManager,
    private val discoveredSourceDao: DiscoveredSourceDao,
    private val healthEngine: ChannelHealthEngine,
    okHttpClient: OkHttpClient,
    @ApplicationContext private val appContext: Context,
) {
    companion object {
        private const val TAG = "PremiumClient"
        private const val PREFS = "premium_sources"
        private const val KEY_DISCOVERED_AT = "discovered_at_ms"
        private const val DISCOVERY_COOLDOWN_MS = 24L * 60 * 60 * 1000L
        private const val MAX_DISCOVERED = 50
        private const val MAX_NEW_PER_DISCOVERY = 5
        private const val DEGRADATION_THRESHOLD = 20
    }

    private val fetchClient = okHttpClient.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private val probeClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val defaultSources: Map<String, String> = linkedMapOf(
        "movies"      to "https://iptv-org.github.io/iptv/categories/movies.m3u",
        "sports"      to "https://iptv-org.github.io/iptv/categories/sports.m3u",
        "series"      to "https://iptv-org.github.io/iptv/categories/series.m3u",
        "documentary" to "https://iptv-org.github.io/iptv/categories/documentary.m3u",
    )

    @Volatile private var discoveredSources: Map<String, String> = emptyMap()

    init {
        // Load seed from bundled asset (synchronous, no Room dependency)
        try {
            val input = appContext.assets.open("premium_sources.json").bufferedReader()
            val seed = gson.fromJson(
                input.readText(), object : TypeToken<Map<String, String>>() {}.type
            ) as? Map<*, *>
            if (seed != null && seed.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                discoveredSources = seed as Map<String, String>
            }
        } catch (_: Exception) { /* no asset bundled — fine */ }
    }

    // ── Source URL management ─────────────────────────────────────────────

    /** Persist discovered sources to Room (called from maintenance coroutine context). */
    private suspend fun persistToRoom() {
        if (discoveredSources.size > MAX_DISCOVERED) {
            discoveredSources = discoveredSources.entries.take(MAX_DISCOVERED).associate { it.key to it.value }
        }
        discoveredSourceDao.clearAll()
        val entities = discoveredSources.map { (key, url) ->
            DiscoveredSourceEntity(key = key, url = url, label = key, hunterName = "legacy")
        }
        discoveredSourceDao.insertAll(entities)
    }

    /** Sync in-memory sources from Room DB — called at start of maintenance & fetch. */
    private suspend fun syncFromRoom() {
        discoveredSources = discoveredSourceDao.getAll().associate { it.key to it.url }
        sourceHunterManager.registerExistingSources(discoveredSources.keys)
    }

    /** All active source URLs (defaults + discovered / persisted). */
    private fun getSources(): Map<String, String> = defaultSources + discoveredSources

    // ── Channel fetching ──────────────────────────────────────────────────

    suspend fun fetchChannels(forceRefresh: Boolean = false): Result<List<PremiumChannel>> =
        withContext(dispatchers.io) {
            runCatching {
                syncFromRoom()

                val sorted = healthTracker.getSortedSources(getSources())
                Log.d(TAG, "Fetching ${sorted.size} sources (health: ${healthTracker.getSummary()})")
                val all = coroutineScope {
                    sorted.map { (key, url) ->
                        async {
                            try {
                                val t0 = System.currentTimeMillis()
                                val entries = M3uParser.parse(url, fetchClient)
                                val elapsed = System.currentTimeMillis() - t0
                                healthTracker.recordFetchResult(key, true, entries.size)
                                Log.d(TAG, "$key: ${entries.size} channels in ${elapsed}ms from $url")
                                entries.map { entry ->
                                    PremiumChannel(
                                        id      = "prem_${key}_${(entry.tvgId ?: entry.name).hashCode().and(0x7FFFFFFF)}",
                                        name    = entry.name,
                                        streamUrl = entry.streamUrl,
                                        logoUrl = entry.logoUrl,
                                        category = entry.category ?: "Premium",
                                        country  = key.take(2).uppercase().let {
                                            M3uParser.inferCountry(entry.tvgId) ?: it
                                        },
                                        source   = key,
                                        headers  = entry.headers,
                                        drmKeyId = entry.drmKeyId,
                                        drmKey   = entry.drmKey,
                                    )
                                }
                            } catch (e: Exception) {
                                healthTracker.recordFetchResult(key, false, 0)
                                if (healthTracker.shouldRemove(key) && key !in defaultSources) {
                                    Log.w(TAG, "Removing dead source: $key")
                                    discoveredSources = discoveredSources - key
                                    discoveredSourceDao.delete(key)
                                } else {
                                    Log.w(TAG, "$key failed: ${e.message}")
                                }
                                emptyList()
                            }
                        }
                    }.map { it.await() }.flatten()
                }
                Log.d(TAG, "total premium: ${all.size} channels")
                all
            }
        }

    // ── Periodic maintenance (called from ChannelRepository after phase2) ──

    /**
     * Validate all known sources (probe reachability), run GitHub discovery
     * to find fresh playlist URLs (at most once per 24 h), and run all
     * source hunters to discover new M3U playlists from paste sites,
     * aggregators, reseller panels, and broadcasters.
     *
     * If [ChannelHealthEngine.degradedSourceCount] exceeds the threshold,
     * source hunters are forced to run regardless of cooldown.
     */
    suspend fun runMaintenance() {
        withContext(dispatchers.io) {
            syncFromRoom()

            val now = System.currentTimeMillis()
            val lastDiscovery = prefs.getLong(KEY_DISCOVERED_AT, 0L)

            val degradedCount = healthEngine.degradedSourceCount()
            val forceHunt = degradedCount > DEGRADATION_THRESHOLD
            if (forceHunt) {
                Log.w(TAG, "Health degradation detected: $degradedCount channels — forcing source hunt")
            }

            getSources().forEach { (key, url) ->
                val (ok, elapsed) = probeSource(url)
                healthTracker.recordProbeResult(key, ok, elapsed)
                if (!ok && healthTracker.shouldRemove(key) && key !in defaultSources) {
                    Log.w(TAG, "Removing dead source during maintenance: $key")
                    discoveredSources = discoveredSources - key
                    discoveredSourceDao.delete(key)
                }
            }
            Log.d(TAG, "Maintenance complete: ${healthTracker.getSummary()} (degraded=$degradedCount)")

            if (forceHunt || (now - lastDiscovery > DISCOVERY_COOLDOWN_MS)) {
                discoverFromGitHub()
                prefs.edit().putLong(KEY_DISCOVERED_AT, System.currentTimeMillis()).apply()
            }

            // Run source hunters to find new playlist URLs
            // When health degradation is detected, bypass cooldown to find replacements faster
            val huntResult = sourceHunterManager.runHuntIfNeeded(force = forceHunt)
            if (huntResult.sources.isNotEmpty()) {
                addDiscoveredSources(huntResult.sources)
                Log.i(TAG, "Hunters discovered ${huntResult.sources.size} new source(s): ${huntResult.sources.map { it.key }}")
            }
        }
    }

    /**
     * Accept discovered sources from external hunters and merge them into
     * the persisted source map, avoiding duplicates with defaults.
     */
    suspend fun addDiscoveredSources(sources: List<DiscoveredSource>) {
        val filtered = sources.filter { s ->
            s.key.isNotBlank() && s.key !in defaultSources && s.key !in discoveredSources
        }
        if (filtered.isNotEmpty()) {
            discoveredSources = discoveredSources + filtered.associate { it.key to it.url }
            val entities = filtered.map { s ->
                DiscoveredSourceEntity(
                    key = s.key,
                    url = s.url,
                    label = s.label,
                    hunterName = s.hunterName,
                )
            }
            entities.forEach { discoveredSourceDao.insert(it) }
            sourceHunterManager.registerExistingSources(discoveredSources.keys)
        }
    }

    private fun probeSource(url: String): Pair<Boolean, Long> {
        var resp: okhttp3.Response? = null
        return try {
            val t0 = System.nanoTime()
            val req = Request.Builder().url(url)
                .header("User-Agent", "StreamVerse/1.0")
                .header("Range", "bytes=0-511")
                .build()
            resp = probeClient.newCall(req).execute()
            val elapsed = (System.nanoTime() - t0) / 1_000_000L
            resp.isSuccessful to elapsed
        } catch (e: Exception) { false to 0L }
        finally { resp?.close() }
    }

    // ── GitHub discovery ──────────────────────────────────────────────────

    private suspend fun discoverFromGitHub() {
        try {
            val repos = searchGitHubRepos("iptv+m3u+playlist", 5)
            val candidates = mutableMapOf<String, String>()

            for ((fullName, branch) in repos) {
                val m3uFiles = findM3uFiles(fullName, branch)
                for ((name, rawUrl) in m3uFiles) {
                    if (isValidM3u(rawUrl)) {
                        val key = name.removeSuffix(".m3u").removeSuffix(".m3u8")
                            .replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase().trim('_')
                        if (key.isNotBlank() && key !in defaultSources && key !in discoveredSources) {
                            candidates[key] = rawUrl
                        }
                    }
                }
            }

            if (candidates.isNotEmpty()) {
                val capped = candidates.entries.take(MAX_NEW_PER_DISCOVERY).associate { it.key to it.value }
                discoveredSources = discoveredSources + capped
                val entities = capped.map { (key, url) ->
                    DiscoveredSourceEntity(key = key, url = url, label = key, hunterName = "github")
                }
                entities.forEach { discoveredSourceDao.insert(it) }
                sourceHunterManager.registerExistingSources(discoveredSources.keys)
                Log.i(TAG, "Discovered ${capped.size} new source(s): ${capped.keys}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub discovery failed: ${e.message}")
        }
    }

    private fun searchGitHubRepos(query: String, perPage: Int): List<Pair<String, String>> {
        var resp: okhttp3.Response? = null
        return try {
            val url = "https://api.github.com/search/repositories?q=$query&sort=updated&per_page=$perPage"
            val req = Request.Builder().url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "StreamVerse/1.0")
                .build()
            resp = probeClient.newCall(req).execute()
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val items = json.getJSONArray("items")
            val repos = mutableListOf<Pair<String, String>>()
            for (i in 0 until items.length()) {
                val repo = items.getJSONObject(i)
                repos.add(repo.getString("full_name") to repo.optString("default_branch", "master"))
            }
            repos
        } catch (e: Exception) { emptyList() }
        finally { resp?.close() }
    }

    private fun findM3uFiles(fullName: String, branch: String): List<Pair<String, String>> {
        var resp: okhttp3.Response? = null
        return try {
            val url = "https://api.github.com/repos/$fullName/git/trees/$branch?recursive=1"
            val req = Request.Builder().url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "StreamVerse/1.0")
                .build()
            resp = probeClient.newCall(req).execute()
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val tree = JSONObject(body).getJSONArray("tree")
            val files = mutableListOf<Pair<String, String>>()
            for (i in 0 until tree.length()) {
                val entry = tree.getJSONObject(i)
                val path = entry.getString("path")
                val mode = entry.optString("mode", "")
                if ((path.endsWith(".m3u") || path.endsWith(".m3u8")) && mode !in setOf("120000", "160000")) {
                    val rawUrl = "https://raw.githubusercontent.com/$fullName/$branch/$path"
                    files.add(path.substringAfterLast('/') to rawUrl)
                }
            }
            files
        } catch (e: Exception) { emptyList() }
        finally { resp?.close() }
    }

    private fun isValidM3u(url: String): Boolean {
        var resp: okhttp3.Response? = null
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "StreamVerse/1.0")
                .header("Range", "bytes=0-2047")
                .build()
            resp = probeClient.newCall(req).execute()
            if (!resp.isSuccessful) return false
            val data = resp.body?.string() ?: return false
            data.startsWith("#EXTM3U") && data.count { it == '\n' } >= 5
        } catch (e: Exception) { false }
        finally { resp?.close() }
    }
}
