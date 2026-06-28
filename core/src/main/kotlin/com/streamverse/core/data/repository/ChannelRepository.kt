package com.streamverse.core.data.repository

import android.content.Context
import android.util.Log
import com.streamverse.core.data.ChannelCacheManager
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import com.streamverse.core.data.model.RadioStation
import com.streamverse.core.data.remote.dlhd.DlhdClient
import com.streamverse.core.data.remote.fast.FastChannel
import com.streamverse.core.data.remote.fast.FastTvClient
import com.streamverse.core.data.remote.independent.IndependentClient
import com.streamverse.core.data.remote.iptv.FreeTvClient
import com.streamverse.core.data.remote.iptv.IptvChannel
import com.streamverse.core.data.remote.iptv.IptvClient
import com.streamverse.core.data.remote.premium.PremiumChannel
import com.streamverse.core.data.remote.premium.PremiumClient

import com.streamverse.core.data.remote.radio.RadioBrowserClient
import com.streamverse.core.data.remote.stmify.StmifyClient
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.ScheduleDay
import com.streamverse.core.domain.model.ScheduleEvent
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.CategoryNormalizer
import com.streamverse.core.util.ChannelNameFormatter
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

enum class LoadingPhase { IDLE, CACHE, LOADING, DONE }

@Singleton
class ChannelRepository @Inject constructor(
    private val dlhdClient: DlhdClient,
    private val stmifyClient: StmifyClient,
    private val iptvClient: IptvClient,
    private val freeTvClient: FreeTvClient,
    private val radioBrowserClient: RadioBrowserClient,
    private val fastTvClient: FastTvClient,
    private val premiumClient: PremiumClient,
    private val independentClient: IndependentClient,
    private val cacheManager: ChannelCacheManager,
    private val sourcePreferences: SourcePreferences,
    private val dispatchers: StreamVerseDispatchers,
    @ApplicationContext private val appContext: Context,
) {
    companion object {
        private const val DEAD_CHANNELS_PREFS = "dead_channels"
        private const val DEAD_CHANNELS_MAX_AGE = 7 * 24 * 60 * 60 * 1000L
        private const val PLACEHOLDER_LOGO_THRESHOLD = 5

        // Patterns that identify X-rated/adult channels by display name.
        private val ADULT_NAME_PATTERNS = listOf(
            Regex("""\b18\+\s*\(""", RegexOption.IGNORE_CASE),         // "18+(Player-1)"
            Regex("""\b18\+\s*(?:onlyfans|porn|sex|adult|erotic|nude)""", RegexOption.IGNORE_CASE),
            Regex("""\bxxx\b""", RegexOption.IGNORE_CASE),              // standalone "xxx"
            Regex("""\b(?:porn|porno|pornhub|xnxx|xvideos)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:onlyfans|playboy)\b""", RegexOption.IGNORE_CASE),
        )

        /** Words too generic to be useful for channel‑name matching. */
        private val COMMON_WORDS = setOf(
            "tv", "hd", "sd", "4k", "fhd", "uhd", "hdr", "the", "and", "for", "via",
            "channel", "network", "live", "stream", "news", "radio", "sport", "plus",
            "max", "one", "two", "free", "show", "music", "world", "asia", "africa",
            "europe", "middle", "east", "west", "north", "south", "central",
        )
    }

    /** Returns true if the channel's display name matches adult-content patterns. */
    private fun isAdultChannel(ch: Channel): Boolean {
        val name = ch.displayName.lowercase().trim()
        return ADULT_NAME_PATTERNS.any { it.containsMatchIn(name) }
    }

    private val deadChannelSeed = setOf("Channels TV", "Soundcity TV", "Channels 24")

    /** Channel display names whose streams are confirmed dead (403/geo-blocked). */
    @Volatile private var deadChannelNames: Set<String> = loadDeadChannelNames()
    private var deadChannelCacheTime: Long = 0L

    private fun loadDeadChannelNames(): Set<String> {
        val prefs = appContext.getSharedPreferences(DEAD_CHANNELS_PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("names", null)?.toSet()
        if (saved != null) {
            // Expire after 7 days — URLs may come back. Seed channels stay permanently.
            val cacheTime = prefs.getLong("cache_time", 0L)
            if (System.currentTimeMillis() - cacheTime > DEAD_CHANNELS_MAX_AGE) {
                val seedOnly = saved.filter { it in deadChannelSeed }.toSet()
                prefs.edit().putStringSet("names", seedOnly).putLong("cache_time", System.currentTimeMillis()).apply()
                return seedOnly
            }
            return saved
        }
        // First run: seed with channels whose CDN URLs are known dead (403 geo-blocked).
        prefs.edit().putStringSet("names", deadChannelSeed).putLong("cache_time", System.currentTimeMillis()).apply()
        return deadChannelSeed
    }

    private fun saveDeadChannelNames() {
        appContext.getSharedPreferences(DEAD_CHANNELS_PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet("names", deadChannelNames).putLong("cache_time", System.currentTimeMillis()).apply()
    }

    /**
     * Report a channel whose stream is confirmed dead (e.g. 403, geo-blocked).
     * It will be excluded from future enrichment until the cache expires.
     */
    fun reportDeadStream(channelId: String) {
        // We store by display name — the same name is shared across IPTV and FAST_TV sources.
        val chan = _idIndex[channelId]
        val name = chan?.displayName?.trim()
        if (name != null && name.isNotBlank()) {
            deadChannelNames = deadChannelNames + name
            saveDeadChannelNames()
        }
    }

    /** Clear all dead channel entries (both seed and user-reported). Next loadPhase2 will re-seed. */
    fun resetDeadChannels() {
        appContext.getSharedPreferences(DEAD_CHANNELS_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        deadChannelNames = deadChannelSeed
    }
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    /**
     * Home/browse channels, filtered to those with at least one enabled source. The filter
     * runs off the main thread — with extended sources enabled this list can hold thousands
     * of channels, and collectors (Home/Search/Category) collect on Dispatchers.Main.
     */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val channels: Flow<List<Channel>> =
        combine(_channels, sourcePreferences.enabledFlow) { chs, enabled ->
            val placeholders = placeholderLogos(chs)
            chs.asSequence()
                .filter { ch -> !isAdultChannel(ch) }
                .filter { ch -> hasEnabledSource(ch, enabled) }
                .map { ch ->
                    if (ch.logoUrl != null && ch.logoUrl in placeholders) ch.copy(logoUrl = null) else ch
                }
                .toList()
        }.flowOn(dispatchers.default)

    /**
     * IDs of all channels that pass the enabled‑source filter.  Derived from [channels] so it
     * automatically stays in sync.  Consumers like Recently‑Watched, Favourites and Player use
     * this to ensure a channel is only reachable when at least one of its sources is enabled.
     */
    val availableChannelIds: StateFlow<Set<String>> =
        channels.map { list -> list.mapTo(HashSet()) { it.id } }
            .stateIn(repoScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    // Memoised by list identity — `_channels.value = …` assigns a fresh list, so `===` is a
    // correct, cheap cache key that recomputes only when the merged catalogue actually changes.
    @Volatile private var placeholderCacheFor: List<Channel>? = null
    @Volatile private var placeholderCache: Set<String> = emptySet()

    /** Logo URLs shared by [PLACEHOLDER_LOGO_THRESHOLD]+ distinct channels — i.e. not real logos. */
    private fun placeholderLogos(chs: List<Channel>): Set<String> {
        if (chs === placeholderCacheFor) return placeholderCache
        val counts = HashMap<String, Int>(chs.size)
        for (ch in chs) {
            val url = ch.logoUrl ?: continue
            if (url.isBlank()) continue
            counts[url] = (counts[url] ?: 0) + 1
        }
        val set = counts.asSequence()
            .filter { it.value >= PLACEHOLDER_LOGO_THRESHOLD }
            .map { it.key }
            .toSet()
        placeholderCacheFor = chs
        placeholderCache = set
        return set
    }

    // Full merged catalogue for search & radio — grows progressively with each source.
    private val _searchable = MutableStateFlow<List<Channel>>(emptyList())

    /**
     * Radio stations for the dedicated "Radio" home row — surfaced only while the Radio source is
     * enabled. Capped so the home screen stays snappy (the full list lives in search).
     */
    val radioChannels: Flow<List<Channel>> =
        combine(_searchable, sourcePreferences.enabledFlow) { all, enabled ->
            if (enabled[com.streamverse.core.data.SourceProvider.RADIO] == false) emptyList()
            else all.asSequence()
                .filter { it.sources.containsKey(SourceType.RADIO) }
                .take(80)
                .toList()
        }.flowOn(dispatchers.default)

    private val _loadingPhase = MutableStateFlow(LoadingPhase.IDLE)
    val loadingPhase: Flow<LoadingPhase> = _loadingPhase.asStateFlow()

    // Keep legacy for compatibility
    private val _isLoading = MutableStateFlow(false)
    val isLoading: Flow<Boolean> = _isLoading.asStateFlow()

    // ── Progressive loading state ──────────────────────────────────────────────
    // Maximum concurrent source fetches (semaphore‑controlled).  Higher values load faster
    // but may saturate the device's network / memory.
    private val maxConcurrentLoads = 4
    private val loadSemaphore = Semaphore(maxConcurrentLoads)

    // Per‑source fetch timeout so a single hanging provider never blocks the whole load.
    private val sourceFetchTimeoutMs = 55_000L

    // Accumulated raw source results.  Each [SourceProvider]'s raw data is stored here by its
    // loader and the full [enrichAndMerge] pipeline re‑runs on every new batch so that
    // cross‑source deduplication & enrichment always reflects the complete catalogue.
    private var dlhdResults: List<com.streamverse.core.data.model.DlhdChannel> = emptyList()
    private var stmifyResults: List<com.streamverse.core.data.model.StmifyChannel> = emptyList()
    private var iptvResults: List<IptvChannel> = emptyList()
    private var freeTvResults: List<IptvChannel> = emptyList()
    private var radioResults: List<com.streamverse.core.data.model.RadioStation> = emptyList()
    private var fastTvResults: List<FastChannel> = emptyList()
    private var premiumResults: List<PremiumChannel> = emptyList()
    private var independentResults: List<IptvChannel> = emptyList()

    /** Runs a source fetch under a hard timeout; returns empty on timeout/error so the merge
     *  proceeds with whatever else loaded instead of hanging forever. */
    private suspend fun <T> fetchWithin(ms: Long, block: suspend () -> List<T>): List<T> =
        withTimeoutOrNull(ms) { runCatching { block() }.getOrDefault(emptyList()) } ?: emptyList()

    // Most recent search results (incl. synthesized Stmify channels) so the player can
    // resolve them by id when tapped.
    @Volatile private var lastSearchResults: List<Channel> = emptyList()

    // O(1) ID lookup across all channel pools. Rebuilt on every merge.
    @Volatile private var _idIndex: Map<String, Channel> = emptyMap()

    /**
     * Lightweight search index that pre-computes lowercased names and a word-level token map
     * so every [searchChannels] call avoids O(n * m) string operations on 15k+ items.
     */
    private class SearchIndex(private val channels: List<Channel>) {
        private val lowerNames: List<String>
        private val wordIndex: Map<String, Set<Int>>
        private val trigramIndex: Map<String, Set<Int>>
        val idIndex: Map<String, Channel>

        init {
            lowerNames = channels.map { it.displayName.lowercase() }
            idIndex = channels.associateBy { it.id }
            val wordMap = mutableMapOf<String, MutableSet<Int>>()
            val triMap = mutableMapOf<String, MutableSet<Int>>()
            for ((idx, ch) in channels.withIndex()) {
                val name = ch.displayName.lowercase()
                for (token in name.split(" ", "-", "/", ".", "&", "'")) {
                    if (token.length >= 2) wordMap.getOrPut(token) { mutableSetOf() }.add(idx)
                }
                // Trigram index for fast substring search (no full scan for 15k+ channels).
                for (i in 0..name.length - 3) {
                    triMap.getOrPut(name.substring(i, i + 3)) { mutableSetOf() }.add(idx)
                }
            }
            wordIndex = wordMap
            trigramIndex = triMap
        }

        private val tokenSplit = charArrayOf(' ', '-', '/', '.', '&', '\'')

        /**
         * Free-text search matches the channel **name only** (never the category — searching
         * "news" must return channels actually called "… news", not every News-genre channel),
         * ranked by relevance: exact > starts-with > word-prefix > substring > all-tokens-present,
         * then shorter names first. [category], when non-null, is applied purely as a filter so the
         * Category tab can scope results.
         */
        fun search(query: String, category: String? = null): List<Channel> {
            val q = query.lowercase().trim()
            if (q.isEmpty()) {
                return if (category != null) channels.filter { it.category == category } else emptyList()
            }

            val queryTokens = q.split(*tokenSplit).filter { it.length >= 2 }

            // Gather name-match candidates: word-index intersection (all tokens present) ∪ substring.
            val candidates = LinkedHashSet<Int>()
            if (queryTokens.isNotEmpty() && wordIndex.isNotEmpty()) {
                queryTokens.mapNotNull { wordIndex[it] }
                    .reduceOrNull { a, b -> a.intersect(b) }
                    ?.let { candidates.addAll(it) }
            }
            // Substring candidates via trigram index (O(1) vs O(n) full scan).
            if (q.length >= 3) {
                val triSeed = trigramIndex[q.substring(0, 3)] ?: emptySet()
                for (i in triSeed) { if (lowerNames[i].contains(q)) candidates.add(i) }
            } else {
                for (i in channels.indices) { if (lowerNames[i].contains(q)) candidates.add(i) }
            }

            fun relevance(name: String): Int = when {
                name == q -> 0
                name.startsWith(q) -> 1
                name.split(*tokenSplit).any { it.startsWith(q) } -> 2
                name.contains(q) -> 3
                else -> 4 // matched only via all-tokens-present (e.g. "sky news" → "Sky Sports News")
            }

            return candidates.asSequence()
                .map { channels[it] }
                .filter { category == null || it.category == category }
                .sortedWith(
                    compareBy(
                        { relevance(it.displayName.lowercase()) },
                        { it.displayName.length },
                        { it.displayName.lowercase() },
                    ),
                )
                .toList()
        }
    }

    @Volatile private var searchIndex: SearchIndex? = null

    private fun providerOf(type: SourceType): SourceProvider = when (type) {
        SourceType.DLHD -> SourceProvider.DLHD
        SourceType.STMIFY_FREE, SourceType.STMIFY_PREMIUM -> SourceProvider.STMIFY
        SourceType.IPTV -> SourceProvider.IPTV
        SourceType.FREE_TV -> SourceProvider.FREE_TV
        SourceType.FAST_TV -> SourceProvider.FAST_TV
        SourceType.RADIO -> SourceProvider.RADIO
        SourceType.INDEPENDENT -> SourceProvider.INDEPENDENT
        SourceType.PREMIUM -> SourceProvider.PREMIUM
    }

    /** A channel is shown only if at least one of its sources is from an enabled provider. */
    private fun hasEnabledSource(channel: Channel, enabled: Map<SourceProvider, Boolean>): Boolean =
        channel.sources.keys.any { enabled[providerOf(it)] != false }

    // ── Progressive loading pipeline ───────────────────────────────────────────
    //
    // Replaces the old two‑phase (loadPhase1 / loadPhase2) system with a single
    // adaptive pipeline that:
    //   1. Loads the on‑disk cache immediately (zero‑network first frame).
    //   2. Discovers every enabled source and launches them **concurrently**,
    //      bounded by a semaphore to avoid saturating the device.
    //   3. Merges & emits the full catalogue **after every source completion** so
    //      Home, Search, Categories, etc. progressively populate as data arrives.
    //   4. Saves the final merged catalogue to cache once all sources are done.
    //
    // There are no hard‑coded "phases".  The Home screen renders whatever channels
    // are available right now, and gets richer as more sources complete.
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Main entry point: load the catalogue from cache + all enabled sources.
     * Channels are emitted progressively — callers observe [channels] / [_channels]
     * and see the catalogue grow with each source completion.
     */
    suspend fun load() = withContext(dispatchers.io) {
        resetSourceResults()
        _loadingPhase.value = LoadingPhase.LOADING
        _isLoading.value = true
        val t0 = System.currentTimeMillis()

        // ── Step 1: on‑disk cache (zero‑network first frame) ──────────────
        val cached = cacheManager.load()
        if (cached != null && cached.isNotEmpty()) {
            _loadingPhase.value = LoadingPhase.CACHE
            _channels.value = cached
            _searchable.value = cached
            searchIndex = SearchIndex(cached)
            _idIndex = buildIdIndex(cached)
            _isLoading.value = false   // content is visible now
            Log.d("ChannelRepo", "cache hit: ${cached.size} channels")
        }

        // ── Step 2: discover enabled sources ──────────────────────────────
        val enabled = sourcePreferences.enabled()
        // ═══ Source definitions ═══
        // Each entry holds the provider, a timeout, and a suspend lambda that fetches
        // the raw source data and stores it in the corresponding accumulator field.
        // Providers with zero channels simply skip the merge step.

        data class SourceDef(
            val provider: SourceProvider,
            val timeoutMs: Long,
            val load: suspend () -> Boolean, // true = had data to merge
        )

        fun buildDefs(): List<SourceDef> {
            val defs = mutableListOf<SourceDef>()

            if (enabled[SourceProvider.DLHD] != false) defs.add(SourceDef(SourceProvider.DLHD, 45_000L) {
                val raw = fetchWithin(sourceFetchTimeoutMs) { dlhdClient.fetchChannelsFromScrape().getOrDefault(emptyList()) }
                dlhdResults = raw; raw.isNotEmpty()
            })
            if (enabled[SourceProvider.STMIFY] != false) defs.add(SourceDef(SourceProvider.STMIFY, 45_000L) {
                val a = fetchWithin(sourceFetchTimeoutMs) { stmifyClient.fetchChannels().getOrDefault(emptyList()) }
                val b = fetchWithin(sourceFetchTimeoutMs) { stmifyClient.fetchChannelsFromArchive().getOrDefault(emptyList()) }
                val merged = (a + b).distinctBy { it.id }
                stmifyResults = merged; merged.isNotEmpty()
            })
            if (enabled[SourceProvider.IPTV] != false) defs.add(SourceDef(SourceProvider.IPTV, 55_000L) {
                val raw = fetchWithin(sourceFetchTimeoutMs) { iptvClient.fetchChannels().getOrDefault(emptyList()) }
                iptvResults = raw.filterNot { it.name.trim() in deadChannelNames }; iptvResults.isNotEmpty()
            })
            if (enabled[SourceProvider.FREE_TV] != false) defs.add(SourceDef(SourceProvider.FREE_TV, 55_000L) {
                val raw = fetchWithin(sourceFetchTimeoutMs) { freeTvClient.fetchChannels().getOrDefault(emptyList()) }
                freeTvResults = raw.filterNot { it.name.trim() in deadChannelNames }; freeTvResults.isNotEmpty()
            })
            if (enabled[SourceProvider.RADIO] != false) defs.add(SourceDef(SourceProvider.RADIO, 55_000L) {
                val raw = fetchWithin(sourceFetchTimeoutMs) { radioBrowserClient.fetchStations().getOrDefault(emptyList()) }
                radioResults = raw; raw.isNotEmpty()
            })
            if (enabled[SourceProvider.FAST_TV] != false) defs.add(SourceDef(SourceProvider.FAST_TV, 55_000L) {
                val raw = fetchWithin(sourceFetchTimeoutMs) { fastTvClient.fetchChannels().getOrDefault(emptyList()) }
                fastTvResults = raw.filterNot { it.name.trim() in deadChannelNames }; fastTvResults.isNotEmpty()
            })
            if (enabled[SourceProvider.PREMIUM] != false) defs.add(SourceDef(SourceProvider.PREMIUM, 55_000L) {
                val raw = fetchWithin(sourceFetchTimeoutMs) { premiumClient.fetchChannels().getOrDefault(emptyList()) }
                premiumResults = raw; raw.isNotEmpty()
            })
            if (enabled[SourceProvider.INDEPENDENT] != false) defs.add(SourceDef(SourceProvider.INDEPENDENT, 55_000L) {
                val raw = fetchWithin(sourceFetchTimeoutMs) { independentClient.fetchChannels() }
                independentResults = raw; raw.isNotEmpty()
            })
            return defs
        }

        // ── Step 3: launch all sources concurrently — each emits on completion ──
        val defs = buildDefs()
        if (defs.isEmpty()) {
            _loadingPhase.value = LoadingPhase.DONE
            _isLoading.value = false
            Log.d("ChannelLoad", "no sources enabled — done in ${System.currentTimeMillis() - t0}ms")
            return@withContext
        }

        val totalSources = defs.size
        var completedSources = 0

        coroutineScope {
            for (def in defs) {
                async {
                    loadSemaphore.acquire()
                    try {
                        val t1 = System.currentTimeMillis()
                        val hadData = def.load()
                        val elapsed = System.currentTimeMillis() - t1
                        Log.d("ChannelLoad", "${def.provider} ${if (hadData) "loaded" else "empty"} in ${elapsed}ms")

                        // Merge ALL accumulated data and emit so the UI grows progressively.
                        mergeAndEmit()

                        synchronized(this@ChannelRepository) {
                            completedSources++
                            Log.d("ChannelLoad", "progress: $completedSources/$totalSources sources (${_channels.value.size} channels)")
                        }
                    } catch (e: Exception) {
                        Log.w("ChannelLoad", "${def.provider} failed: ${e.message}")
                        synchronized(this@ChannelRepository) { completedSources++ }
                    } finally {
                        loadSemaphore.release()
                    }
                }
            }
        }
        // All sources have completed — finish loading.
        val totalTime = System.currentTimeMillis() - t0
        Log.d("ChannelLoad", "TOTAL ${totalTime}ms — ${_channels.value.size} channels from ${totalSources} sources")

        // Finalise the index and persist cache.
        mergeAndEmit()
        cacheManager.save(_channels.value)
        cacheManager.saveSearchable(_searchable.value)

        // Side‑effect source auto‑healing (never delays startup).
        runCatching { premiumClient.runMaintenance() }

        _loadingPhase.value = LoadingPhase.DONE
        _isLoading.value = false
    }

    /**
     * Re‑run the full merge pipeline with the current accumulated raw results and emit
     * the updated catalogue to [channels] / [_channels].
     */
    private suspend fun mergeAndEmit() {
        val merged = enrichAndMerge(
            mergeQuick(dlhdResults, stmifyResults),
            iptvResults,
            freeTvResults,
            radioResults,
            fastTvResults,
            premiumResults,
            independentResults,
        )
        _channels.value = merged
        _searchable.value = merged
        searchIndex = SearchIndex(merged)
        _idIndex = buildIdIndex(merged)
    }

    /** Clear all accumulated raw source results — called before a fresh load. */
    private fun resetSourceResults() {
        dlhdResults = emptyList()
        stmifyResults = emptyList()
        iptvResults = emptyList()
        freeTvResults = emptyList()
        radioResults = emptyList()
        fastTvResults = emptyList()
        premiumResults = emptyList()
        independentResults = emptyList()
    }

    /**
     * Re‑fetch everything from scratch.  Invalidates the on‑disk cache and re‑runs [load].
     */
    suspend fun reload() {
        cacheManager.invalidate()
        cacheManager.invalidateSearchable()
        lastSearchResults = emptyList()
        load()
    }

    // ── Search across all loaded sources ──

    // ── Search across premium (DLHD + Stmify) + extended (IPTV/FreeTV/Radio/FastTV) ──

    suspend fun searchChannels(query: String, category: String? = null): List<Channel> = withContext(dispatchers.io) {
        // Use the pre-built search index (15k+ channels) for O(query_tokens) matching.
        val idx = searchIndex
        val q = query.lowercase().trim()
        val indexed = if (idx != null) {
            idx.search(query, category)
        } else {
            // No index yet (very first moments of a cold start): scan whatever set exists.
            val pool = if (_searchable.value.isNotEmpty()) _searchable.value else _channels.value
            if (q.isEmpty()) {
                if (category != null) pool.filter { it.category == category } else emptyList()
            } else pool.filter {
                it.displayName.lowercase().contains(q) && (category == null || it.category == category)
            }
        }
        // Belt-and-suspenders: always include home matches too, so a channel that's
        // already loaded is NEVER missing from search regardless of index freshness.
        val browsableHits = if (q.isNotEmpty()) _channels.value.filter {
            it.displayName.lowercase().contains(q) && (category == null || it.category == category)
        } else emptyList()
        val localResults = (indexed + browsableHits).distinctBy { it.id }

        val stmifyResults = if (sourcePreferences.isEnabled(SourceProvider.STMIFY))
            stmifyClient.searchChannels(query).getOrDefault(emptyList()) else emptyList()

        if (stmifyResults.isEmpty()) {
            val enabled = sourcePreferences.enabled()
            return@withContext localResults.filter { hasEnabledSource(it, enabled) }
                .also { lastSearchResults = it }
        }
        val stmifyMerged = stmifyResults.map { stm ->
            val stmCanon = canonicalName(stm.name)
            val existing = localResults.find { ch ->
                // Match by canonical name (so "MBC 2" merges with an indexed "MBC 2 Ⓢ"), not a
                // brittle substring check that left a duplicate clean "MBC 2" in the results.
                canonicalName(ch.displayName) == stmCanon ||
                ch.id == "stmify_${stm.id}" ||
                ch.sources.values.any { it.referenceId == stm.id }
            }
            existing?.copy(
                sources = existing.sources + (SourceType.STMIFY_FREE to SourceInfo(
                    type = SourceType.STMIFY_FREE,
                    referenceId = stm.id,
                ))
            ) ?: Channel(
                id = "stmify_${stm.id}",
                displayName = ChannelNameFormatter.stripResolution(stm.name),
                logoUrl = stm.logoUrl,
                quality = stm.quality,
                category = CategoryNormalizer.normalize(stm.genres.firstOrNull(), false),
                language = null,
                country = null,
                description = stm.description,
                sources = mapOf(SourceType.STMIFY_FREE to SourceInfo(type = SourceType.STMIFY_FREE, referenceId = stm.id)),
            )
        }
        val enabled = sourcePreferences.enabled()
        // Stmify searches server-side across genre/description; keep only results whose NAME
        // matches the query so search stays name-only. Local results are already relevance-ranked
        // and come first; Stmify-only name matches are appended.
        // Combine, MERGING sources by id. A naive (local + stmify).distinctBy{id} kept the
        // single-source local original and threw away the version we just folded the Stmify source
        // into — leaving MBC 2 looking single-source. Merge by id so all sources survive.
        val byIdResult = LinkedHashMap<String, Channel>()
        localResults.forEach { byIdResult[it.id] = it }
        stmifyMerged.filter { it.displayName.lowercase().contains(q) }.forEach { m ->
            val prev = byIdResult[m.id]
            byIdResult[m.id] = if (prev != null) prev.copy(sources = prev.sources + m.sources) else m
        }
        byIdResult.values.filter { hasEnabledSource(it, enabled) }
            .also { lastSearchResults = it }
    }

    fun getCachedChannels(): List<Channel> = _channels.value

    /** All playable channels across ALL sources. Superset of [getCachedChannels]
     *  that includes every loaded source (IPTV-org, FreeTv, Radio, FAST TV, etc.). */
    fun getAllChannels(): List<Channel> =
        if (_searchable.value.isNotEmpty()) _searchable.value else _channels.value

    suspend fun getChannelById(id: String): Channel? {
        val indexed = _idIndex[id]
        val searched = lastSearchResults.find { it.id == id }
        // Merge sources from both: the search pass may have folded in extra sources (e.g. a Stmify
        // result) that aren't in the persisted index, so the opened channel stays multi-source.
        return when {
            indexed != null && searched != null ->
                indexed.copy(sources = indexed.sources + searched.sources)
            else -> indexed ?: searched
        }
    }

    /**
     * Like [getChannelById] but returns `null` when the channel's sources are all from disabled
     * providers.  Used by Player and Favourites to enforce source availability.
     */
    suspend fun getAvailableChannel(id: String): Channel? {
        val ch = getChannelById(id) ?: return null
        val enabled = sourcePreferences.enabled()
        return if (hasEnabledSource(ch, enabled)) ch else null
    }

    private fun buildIdIndex(vararg pools: List<Channel>): Map<String, Channel> {
        val map = mutableMapOf<String, Channel>()
        for (pool in pools) {
            for (ch in pool) { map.putIfAbsent(ch.id, ch) }
        }
        return map
    }

    suspend fun getCategories(): List<String> =
        _channels.value.mapNotNull { it.category }.distinct().sorted()

    suspend fun fetchSchedule(apiKey: String?): List<ScheduleDay> = withContext(dispatchers.io) {
        if (apiKey == null) return@withContext emptyList()
        dlhdClient.fetchSchedule(apiKey).getOrDefault(emptyList())
            .groupBy { it.date }
            .map { (date, dayEvents) ->
                ScheduleDay(
                    date = date,
                    events = dayEvents.groupBy { it.category }.mapValues { (_, evts) ->
                        evts.map { ScheduleEvent(it.time, it.event, it.category, it.channelIds, date) }
                    }
                )
            }.sortedBy { it.date }
    }

    // ── Merge helpers ────────────────────────────────────────────────────────

    private fun mergeQuick(
        dlhd: List<com.streamverse.core.data.model.DlhdChannel>,
        stmify: List<com.streamverse.core.data.model.StmifyChannel>,
    ): List<Channel> {
        val merged = mutableMapOf<String, Channel>()
        for (dl in dlhd) {
            val stm = findMatchingStmify(dl.name, stmify)
            val sources = mutableMapOf(SourceType.DLHD to SourceInfo(type = SourceType.DLHD, referenceId = dl.id))
            if (stm != null) sources[SourceType.STMIFY_FREE] = SourceInfo(type = SourceType.STMIFY_FREE, referenceId = stm.id)
            val rawCat = stm?.genres?.firstOrNull() ?: dl.category
            val formattedName = ChannelNameFormatter.format(dl.name)
            // When no raw category exists, infer from the channel name itself
            val category = if (rawCat != null) {
                CategoryNormalizer.normalize(rawCat, false)
            } else {
                CategoryNormalizer.fromChannelName(formattedName)
            }
            merged["dlhd_${dl.id}"] = Channel(
                id = "dlhd_${dl.id}",
                displayName = formattedName,
                logoUrl = dl.logoUrl ?: stm?.logoUrl,
                quality = stm?.quality ?: if (dl.name.contains("4K", true)) Quality._4K else null,
                category = category,
                language = null,
                country = null,
                description = stm?.description,
                sources = sources,
            )
        }
        for (st in stmify) {
            if (merged.values.any { ch -> ch.sources.values.any { it.referenceId == st.id } }) continue
            val stFormattedName = ChannelNameFormatter.format(st.name)
            merged["stmify_${st.id}"] = Channel(
                id = "stmify_${st.id}",
                displayName = stFormattedName,
                logoUrl = st.logoUrl,
                quality = st.quality,
                category = st.genres.firstOrNull()
                    ?.let { CategoryNormalizer.normalize(it, false) }
                    ?: CategoryNormalizer.fromChannelName(stFormattedName),
                language = null,
                country = null,
                description = st.description,
                sources = mapOf(SourceType.STMIFY_FREE to SourceInfo(type = SourceType.STMIFY_FREE, referenceId = st.id)),
            )
        }
        return merged.values.toList()
    }

    private suspend fun enrichAndMerge(
        existing: List<Channel>,
        iptv: List<IptvChannel>,
        freeTv: List<IptvChannel>,
        radio: List<RadioStation>,
        fastTv: List<FastChannel> = emptyList(),
        premium: List<PremiumChannel> = emptyList(),
        independent: List<IptvChannel> = emptyList(),
    ): List<Channel> {
        // Collapse channels already in the base list that share a canonical name — e.g. "MBC 2" and
        // "MBC 2 Ⓢ" arriving from two premium sources (DLHD + Stmify) that the quick matcher missed.
        // Merge their sources under the CLEANEST display name (fewest decorative symbols).
        val existingDeduped = run {
            val m = LinkedHashMap<String, Channel>(existing.size)
            for (ch in existing) {
                val key = canonicalName(ch.displayName)
                val prev = m[key]
                m[key] = if (prev == null) ch else {
                    val keep = if (decorationScore(ch.displayName) < decorationScore(prev.displayName)) ch else prev
                    keep.copy(
                        sources = prev.sources + ch.sources,
                        logoUrl = keep.logoUrl ?: prev.logoUrl ?: ch.logoUrl,
                        category = keep.category ?: prev.category ?: ch.category,
                        country = keep.country ?: prev.country ?: ch.country,
                        language = keep.language ?: prev.language ?: ch.language,
                        quality = keep.quality ?: prev.quality ?: ch.quality,
                    )
                }
            }
            m.values.toList()
        }
        val byId = existingDeduped.associateBy { it.id }.toMutableMap()
        // Build by-name map from existing + all IDs we create (so subsequent sources find matches).
        val byNameLower = mutableMapOf<String, Channel>()
        val byCanonicalName = mutableMapOf<String, Channel>()
        val byWordIndex = mutableMapOf<String, MutableSet<String>>()
        existingDeduped.forEach { ch ->
            val norm = ch.displayName.lowercase().trim()
            byNameLower[norm] = ch
            val canon = canonicalName(ch.displayName)
            byCanonicalName[canon] = ch
            words(ch.displayName).forEach { w ->
                byWordIndex.getOrPut(w) { mutableSetOf() }.add(ch.id)
            }
        }

        fun qualityFrom(q: String?) = when (q) {
            "4K" -> Quality._4K; "FHD" -> Quality.FHD; "HD" -> Quality.HD; "SD" -> Quality.SD; else -> null
        }

        // Enrich with IPTV-org data — the largest source (10k+ channels). Yield every 500 items
        // so the coroutine stays cancellable during this long-running loop.
        for ((idx, ip) in iptv.withIndex()) {
            if (idx % 500 == 0 && idx > 0) yield()
            val norm = ip.name.lowercase().trim()
            val canon = canonicalName(ip.name)
            // O(1) lookups: exact normalized name → canonical name → no fuzzy fallback needed for most
            val existing2 = byNameLower[norm] ?: byCanonicalName[canon] ?: findByNameFuzzyFast(ip.name, byCanonicalName, byWordIndex, byId)
            val ipInfo = SourceInfo(type = SourceType.IPTV, referenceId = ip.id, streamUrl = ip.streamUrl, headers = ip.headers, drmKeyId = ip.drmKeyId, drmKey = ip.drmKey)
            if (existing2 != null) {
                if (!existing2.sources.containsKey(SourceType.IPTV)) {
                    val updated = existing2.copy(
                        sources = existing2.sources + (SourceType.IPTV to ipInfo),
                        country = existing2.country ?: ip.country,
                        language = existing2.language ?: ip.language,
                        logoUrl = existing2.logoUrl ?: ip.logoUrl,
                        category = if (existing2.category == CategoryNormalizer.C.GENERAL)
                            CategoryNormalizer.normalize(ip.category, false)
                        else existing2.category,
                    )
                    byId[updated.id] = updated
                    val upNorm = updated.displayName.lowercase().trim()
                    byNameLower[upNorm] = updated
                    val upCanon = canonicalName(updated.displayName)
                    byCanonicalName[upCanon] = updated
                }
            } else {
                val ch = Channel(
                    id = "iptv_${ip.id}",
                    displayName = ChannelNameFormatter.stripResolution(ip.name),
                    logoUrl = ip.logoUrl,
                    quality = qualityFrom(ip.quality),
                    category = CategoryNormalizer.normalize(ip.category, false),
                    language = ip.language,
                    country = ip.country,
                    description = null,
                    sources = mapOf(SourceType.IPTV to ipInfo),
                )
                byId[ch.id] = ch
                byNameLower[norm] = ch
                byCanonicalName[canon] = ch
                words(ch.displayName).forEach { w ->
                    byWordIndex.getOrPut(w) { mutableSetOf() }.add(ch.id)
                }
            }
        }

        // Enrich with FreeTv
        for ((idx, ft) in freeTv.withIndex()) {
            if (idx % 500 == 0 && idx > 0) yield()
            val norm = ft.name.lowercase().trim()
            val canon = canonicalName(ft.name)
            val existing2 = byNameLower[norm] ?: byCanonicalName[canon] ?: findByNameFuzzyFast(ft.name, byCanonicalName, byWordIndex, byId)
            val ftInfo = SourceInfo(type = SourceType.FREE_TV, referenceId = ft.id, streamUrl = ft.streamUrl, headers = ft.headers, drmKeyId = ft.drmKeyId, drmKey = ft.drmKey)
            if (existing2 != null) {
                if (!existing2.sources.containsKey(SourceType.FREE_TV)) {
                    val updated = existing2.copy(
                        sources = existing2.sources + (SourceType.FREE_TV to ftInfo),
                        country = existing2.country ?: ft.country,
                        language = existing2.language ?: ft.language,
                    )
                    byId[updated.id] = updated
                    val upNorm = updated.displayName.lowercase().trim()
                    byNameLower[upNorm] = updated
                    val upCanon = canonicalName(updated.displayName)
                    byCanonicalName[upCanon] = updated
                }
            } else {
                val ch = Channel(
                    id = "freetv_${ft.id}",
                    displayName = ChannelNameFormatter.stripResolution(ft.name),
                    logoUrl = ft.logoUrl,
                    quality = qualityFrom(ft.quality),
                    category = CategoryNormalizer.normalize(ft.category, false),
                    language = ft.language,
                    country = ft.country,
                    description = null,
                    sources = mapOf(SourceType.FREE_TV to ftInfo),
                )
                byId[ch.id] = ch
                byNameLower[norm] = ch
                byCanonicalName[canon] = ch
                words(ch.displayName).forEach { w ->
                    byWordIndex.getOrPut(w) { mutableSetOf() }.add(ch.id)
                }
            }
        }

        // Add radio channels (always "Radio" category). Deduplicate by canonical name so
        // two radio‑browser entries for e.g. "BBC Radio 1" with different station UUIDs
        // merge into one card with multiple stream URLs listed under SourceType.RADIO.
        val radioByName = mutableMapOf<String, Channel>()   // canonicalName → Channel
        for (r in radio) {
            val displayName = ChannelNameFormatter.stripResolution(r.name)
            val canon = canonicalName(displayName)
            val existing = radioByName[canon]
            if (existing != null) {
                val newInfo = SourceInfo(type = SourceType.RADIO, referenceId = r.id, streamUrl = r.streamUrl)
                if (newInfo !in existing.sources.values) {
                    val updated = existing.copy(
                        sources = existing.sources + (SourceType.RADIO to newInfo),
                        logoUrl = existing.logoUrl ?: r.logoUrl,
                        country = existing.country ?: r.country,
                        language = existing.language ?: r.language,
                    )
                    radioByName[canon] = updated
                    byId[updated.id] = updated
                }
            } else {
                val radioId = "radio_${r.id}"
                val ch = Channel(
                    id = radioId,
                    displayName = displayName,
                    logoUrl = r.logoUrl,
                    quality = null,
                    category = CategoryNormalizer.C.RADIO,
                    language = r.language,
                    country = r.country,
                    description = buildString {
                        r.codec?.let { append(it) }
                        r.bitrate?.let { if (isNotEmpty()) append(" "); append("${it}kbps") }
                    }.ifBlank { null },
                    sources = mapOf(SourceType.RADIO to SourceInfo(type = SourceType.RADIO, referenceId = r.id, streamUrl = r.streamUrl)),
                )
                radioByName[canon] = ch
                byId[ch.id] = ch
                words(ch.displayName).forEach { w ->
                    byWordIndex.getOrPut(w) { mutableSetOf() }.add(ch.id)
                }
            }
        }

// Enrich with FAST TV (Pluto TV, Samsung TV Plus, Plex, Roku)
        // If a channel name matches an existing channel, add stream source; otherwise add as new.
        for ((idx, fc) in fastTv.withIndex()) {
            if (idx % 500 == 0 && idx > 0) yield()
            val norm = fc.name.lowercase().trim()
            val canon = canonicalName(fc.name)
            val existing2 = byNameLower[norm] ?: byCanonicalName[canon] ?: findByNameFuzzyFast(fc.name, byCanonicalName, byWordIndex, byId)
            val fcInfo = SourceInfo(
                type = SourceType.FAST_TV,
                referenceId = fc.id,
                streamUrl = fc.streamUrl,
                headers = fc.headers,
                drmKeyId = fc.drmKeyId,
                drmKey = fc.drmKey,
            )
            if (existing2 != null) {
                if (!existing2.sources.containsKey(SourceType.FAST_TV)) {
                    val updated = existing2.copy(
                        sources  = existing2.sources + (SourceType.FAST_TV to fcInfo),
                        country  = existing2.country ?: fc.country,
                        logoUrl  = existing2.logoUrl ?: fc.logoUrl,
                        category = if (existing2.category == CategoryNormalizer.C.GENERAL)
                            CategoryNormalizer.normalize(fc.category, false) else existing2.category,
                    )
                    byId[updated.id] = updated
                    byNameLower[norm] = updated
                    val upCanon = canonicalName(updated.displayName)
                    byCanonicalName[upCanon] = updated
                }
            } else {
                val formattedName = ChannelNameFormatter.format(fc.name)
                val ch = Channel(
                    id          = "fast_${fc.id}",
                    displayName = formattedName,
                    logoUrl     = fc.logoUrl,
                    quality     = null,
                    category    = CategoryNormalizer.normalize(fc.category, false)
                                          .let { if (it == CategoryNormalizer.C.GENERAL) CategoryNormalizer.fromChannelName(formattedName) else it },
                    language    = null,
                    country     = fc.country,
                    description = null,
                    sources     = mapOf(SourceType.FAST_TV to fcInfo),
                )
                byId[ch.id] = ch
                byNameLower[norm] = ch
                byCanonicalName[canon] = ch
                words(ch.displayName).forEach { w ->
                    byWordIndex.getOrPut(w) { mutableSetOf() }.add(ch.id)
                }
            }
        }

        // Enrich with Premium channels (HBO, Showtime, Starz, sports, etc.)
        for ((idx, pr) in premium.withIndex()) {
            if (idx % 500 == 0 && idx > 0) yield()
            val norm = pr.name.lowercase().trim()
            val canon = canonicalName(pr.name)
            val existing2 = byNameLower[norm] ?: byCanonicalName[canon] ?: findByNameFuzzyFast(pr.name, byCanonicalName, byWordIndex, byId)
            val prInfo = SourceInfo(
                type = SourceType.PREMIUM,
                referenceId = pr.id,
                streamUrl = pr.streamUrl,
                headers = pr.headers,
                drmKeyId = pr.drmKeyId,
                drmKey = pr.drmKey,
            )
            if (existing2 != null) {
                if (!existing2.sources.containsKey(SourceType.PREMIUM)) {
                    val updated = existing2.copy(
                        sources = existing2.sources + (SourceType.PREMIUM to prInfo),
                        country = existing2.country ?: pr.country,
                        logoUrl = existing2.logoUrl ?: pr.logoUrl,
                        category = if (existing2.category == CategoryNormalizer.C.GENERAL)
                            CategoryNormalizer.normalize(pr.category, false) else existing2.category,
                    )
                    byId[updated.id] = updated
                    byNameLower[norm] = updated
                    val upCanon = canonicalName(updated.displayName)
                    byCanonicalName[upCanon] = updated
                }
            } else {
                val formattedName = ChannelNameFormatter.format(pr.name)
                val ch = Channel(
                    id          = "prem_${pr.id}",
                    displayName = formattedName,
                    logoUrl     = pr.logoUrl,
                    quality     = null,
                    category    = CategoryNormalizer.normalize(pr.category, false)
                        .let { if (it == CategoryNormalizer.C.GENERAL) CategoryNormalizer.fromChannelName(formattedName) else it },
                    language    = null,
                    country     = pr.country,
                    description = null,
                    sources     = mapOf(SourceType.PREMIUM to prInfo),
                )
                byId[ch.id] = ch
                byNameLower[norm] = ch
                byCanonicalName[canon] = ch
                words(ch.displayName).forEach { w ->
                    byWordIndex.getOrPut(w) { mutableSetOf() }.add(ch.id)
                }
            }
        }

        // Enrich with Independent (curated verified-working free streams).
        // Deduplicates by lowercase name against all existing entries.
        for ((idx, ind) in independent.withIndex()) {
            if (idx % 500 == 0 && idx > 0) yield()
            val norm = ind.name.lowercase().trim()
            val canon = canonicalName(ind.name)
            val existing2 = byNameLower[norm] ?: byCanonicalName[canon] ?: findByNameFuzzyFast(ind.name, byCanonicalName, byWordIndex, byId)
            val indInfo = SourceInfo(
                type = SourceType.INDEPENDENT,
                referenceId = ind.id,
                streamUrl = ind.streamUrl,
            )
            if (existing2 != null) {
                if (!existing2.sources.containsKey(SourceType.INDEPENDENT)) {
                    val updated = existing2.copy(
                        sources = existing2.sources + (SourceType.INDEPENDENT to indInfo),
                        country = existing2.country ?: ind.country,
                        language = existing2.language ?: ind.language,
                        logoUrl = existing2.logoUrl ?: ind.logoUrl,
                    )
                    byId[updated.id] = updated
                    val upNorm = updated.displayName.lowercase().trim()
                    byNameLower[upNorm] = updated
                    val upCanon = canonicalName(updated.displayName)
                    byCanonicalName[upCanon] = updated
                }
            } else {
                val ch = Channel(
                    id = "ind_${ind.id}",
                    displayName = ChannelNameFormatter.stripResolution(ind.name),
                    logoUrl = ind.logoUrl,
                    quality = qualityFrom(ind.quality),
                    category = CategoryNormalizer.normalize(ind.category, false),
                    language = ind.language,
                    country = ind.country,
                    description = null,
                    sources = mapOf(SourceType.INDEPENDENT to indInfo),
                )
                byId[ch.id] = ch
                byNameLower[norm] = ch
                byCanonicalName[canon] = ch
                words(ch.displayName).forEach { w ->
                    byWordIndex.getOrPut(w) { mutableSetOf() }.add(ch.id)
                }
            }
        }

        return byId.values.asSequence()
            .filter { !isAdultChannel(it) }
            .toList()
    }

    /** Normalize a name for matching: lowercase, strip quality/resolution suffixes. */
    /**
     * Canonical key for matching the SAME channel across sources, regardless of cosmetic
     * differences. Robust to special characters so e.g. "MBC 2", "MBC2", "MBC-2", "MBC 2 ᴴᴰ",
     * "MBC 2 (HD)", "MBC 2 ®", "ＭＢＣ ②" all collapse to "mbc2" and merge into one multi-source entry.
     *
     * Distinct channels stay distinct ("MBC 2 News" → "mbc2news" ≠ "mbc2").
     */
    private fun canonicalName(name: String): String {
        // NFD (canonical decomposition) folds accents (é→e) but, unlike NFKD, leaves decorative
        // symbols like Ⓢ / ᴴᴰ / ® as symbols so the alphanumeric filter STRIPS them (NFKD would turn
        // Ⓢ into the letter "s", wrongly producing "mbc2s" ≠ "mbc2").
        var s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")        // strip accents / combining marks
            .lowercase()
        // Drop bracketed quality/resolution tags anywhere: (1080p) [hd] {4k} …
        s = s.replace(
            Regex("""[\(\[\{]\s*(?:\d{3,4}[pi]|4k|fhd|uhd|hdr|hd|sd)\s*[\)\]\}]""", RegexOption.IGNORE_CASE),
            " ",
        )
        // Reduce to alphanumerics — collapses spaces, dashes, dots, bullets, symbols, emoji…
        val alnum = s.replace(Regex("[^a-z0-9]"), "")
        // Guard: a pure non-Latin name (Arabic/Chinese/…) would strip to empty and then ALL such
        // channels would collide on "" and wrongly merge. Keep a collapsed form for those instead.
        if (alnum.length < 2) {
            return s.replace(Regex("\\s+"), " ").trim()
        }
        // Drop a quality suffix that got glued on (e.g. "mbc2hd" → "mbc2"), but never to <2 chars.
        val trimmed = alnum.replace(Regex("""(?:fhd|uhd|hdr|hd|sd|4k|2160p|1080p|720p|480p|360p)$"""), "")
        return if (trimmed.length >= 2) trimmed else alnum
    }

    /** How "decorated" a name is — count of non-alphanumeric, non-space chars (symbols like Ⓢ ® ™).
     *  Lower = cleaner; used to pick the nicest display name when merging duplicate channels. */
    private fun decorationScore(name: String): Int =
        name.count { !it.isLetterOrDigit() && !it.isWhitespace() }

    /**
     * Channel match by word-level fuzzy matching — fallback when exact name and canonical
     * lookups fail.
     *
     * Extracts significant words (≥3 chars, not in COMMON_WORDS) from the query name, finds
     * all existing channels that share at least one such word via the pre-built word index,
     * then scores each candidate by Jaccard‑like word overlap.  Returns the best match if
     * the overlap meets the same 50% threshold used by [findMatchingStmify].
     *
     * The word index is built once and maintained incrementally during enrichAndMerge, so
     * this is O(k) where k ≈ query‑words × channels‑per‑word — typically <200 — never a full
     * scan.  It only fires for the minority of entries where exact/canonical matching misses.
     */
    private fun findByNameFuzzyFast(
        name: String,
        byCanonicalName: Map<String, Channel>,
        byWordIndex: Map<String, Set<String>>,
        byId: Map<String, Channel>,
    ): Channel? {
        // Also try canonical (the second fallback in the chain already does this, but
        // keeping it here keeps the method self-contained for reuse).
        byCanonicalName[canonicalName(name)]?.let { return it }

        val queryWords = words(name)
        if (queryWords.isEmpty()) return null

        // Gather candidate IDs that share at least one significant word
        val candidateIds = mutableSetOf<String>()
        for (w in queryWords) {
            byWordIndex[w]?.let { candidateIds.addAll(it) }
        }

        // Score each candidate by word overlap (≥50 % of the smaller word‑set)
        return candidateIds.asSequence()
            .mapNotNull { byId[it] }
            .maxByOrNull { ch ->
                val chWords = words(ch.displayName)
                val common = queryWords.intersect(chWords)
                val threshold = (minOf(queryWords.size, chWords.size) * 0.5f).toInt().coerceAtLeast(1)
                if (common.size >= threshold) common.size else 0
            }
    }

    /** Extract significant words from a display name — used by [findByNameFuzzyFast]. */
    private fun words(name: String): Set<String> = name.lowercase()
        .split(Regex("""[\s\-_./&]+"""))
        .filter { it.length >= 3 && it !in COMMON_WORDS }
        .toSet()

    private fun findMatchingStmify(
        dlhdName: String,
        stmify: List<com.streamverse.core.data.model.StmifyChannel>,
    ): com.streamverse.core.data.model.StmifyChannel? {
        val normalized = dlhdName.lowercase().trim()
        return stmify.firstOrNull { st ->
            val stName = st.name.lowercase().trim()
            stName == normalized || stName.contains(normalized) || normalized.contains(stName) ||
            fuzzyMatch(normalized, stName)
        }
    }

    private fun fuzzyMatch(a: String, b: String): Boolean {
        val aWords = a.split(" ", "/", "-").filter { it.length > 2 }.toSet()
        val bWords = b.split(" ", "/", "-").filter { it.length > 2 }.toSet()
        val common = aWords.intersect(bWords)
        return common.size >= (minOf(aWords.size, bWords.size) * 0.5).toInt().coerceAtLeast(1)
    }
}
