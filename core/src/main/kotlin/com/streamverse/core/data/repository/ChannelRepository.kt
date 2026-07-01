package com.streamverse.core.data.repository

import android.content.Context
import android.util.Log
import com.streamverse.core.data.ChannelCacheManager
import com.streamverse.core.data.SmartCacheManager
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.local.ChannelSearchDao
import com.streamverse.core.data.local.ChannelSearchFts
import com.streamverse.core.data.source.provider.ProviderRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import com.streamverse.core.data.model.RadioStation
import com.streamverse.core.data.remote.dlhd.DlhdClient
import com.streamverse.core.data.remote.free.FreeChannel
import com.streamverse.core.data.remote.free.FreeLiveClient
import com.streamverse.core.data.remote.iptv.IptvChannel
import com.streamverse.core.data.remote.iptv.IptvClient
import com.streamverse.core.data.remote.broadcaster.BroadcasterClient
import com.streamverse.core.data.remote.youtube.YouTubeTvClient
import com.streamverse.core.data.remote.youtube.YouTubeTvChannel

import com.streamverse.core.data.remote.hosted.HostedIndexClient
import com.streamverse.core.data.remote.MergedIndexClient
import com.streamverse.core.data.remote.radio.RadioBrowserClient
import com.streamverse.core.data.remote.stmify.PrimeVideoClient
import com.streamverse.core.data.remote.stmify.StmifyClient
import com.streamverse.core.data.source.ChannelCanonicalizer
import com.streamverse.core.data.source.EntityResolutionEngine
import com.streamverse.core.data.source.IncrementalMergeState
import com.streamverse.core.data.source.LogicalChannelMatcher
import com.streamverse.core.data.source.MetadataAggregator
import com.streamverse.core.data.source.SourceItem
import com.streamverse.core.data.source.SourceRegistry
import com.streamverse.core.data.source.SourceRegistryInitializer
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.ChannelSummary
import com.streamverse.core.domain.model.toSummary

import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.CategoryNormalizer
import com.streamverse.core.util.ChannelNameFormatter
import com.streamverse.core.util.PerformanceMonitor
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class LoadingPhase { IDLE, CACHE, LOADING, DONE }

enum class ProviderLoadingPhase { PENDING, LOADING, COMPLETE, FAILED, SKIPPED }

@Singleton
class ChannelRepository @Inject constructor(
    private val dlhdClient: DlhdClient,
    private val stmifyClient: StmifyClient,
    private val primeVideoClient: PrimeVideoClient,
    private val hostedIndexClient: HostedIndexClient,
    private val mergedIndexClient: MergedIndexClient,
    private val iptvClient: IptvClient,
    private val radioBrowserClient: RadioBrowserClient,
    private val broadcasterClient: BroadcasterClient,
    private val freeLiveClient: FreeLiveClient,
    private val youtubeTvClient: YouTubeTvClient,
    private val cacheManager: ChannelCacheManager,
    private val smartCacheManager: SmartCacheManager,
    private val sourcePreferences: SourcePreferences,
    private val dispatchers: StreamVerseDispatchers,
    private val sourceRegistry: SourceRegistry,
    @Suppress("unused") private val channelMatcher: LogicalChannelMatcher,
    private val metadataAggregator: MetadataAggregator,
    @Suppress("unused") private val registryInitializer: SourceRegistryInitializer,
    private val providerRegistry: ProviderRegistry,
    private val channelSearchDao: ChannelSearchDao,
    @ApplicationContext private val appContext: Context,
) {
    private val entityResolutionEngine = EntityResolutionEngine()
    private val incrementalMergeState = IncrementalMergeState(entityResolutionEngine, metadataAggregator)
    private val loadInProgress = AtomicBoolean(false)

    companion object {
        private const val DEAD_CHANNELS_PREFS = "dead_channels"
        private const val DEAD_CHANNELS_MAX_AGE = 7 * 24 * 60 * 60 * 1000L
        private const val PLACEHOLDER_LOGO_THRESHOLD = 5
        private const val LAST_SEARCH_MAX = 500
        private val RE_SEARCH_WORD = Regex("""[\s\-/\.&']+""")

        // Patterns that identify X-rated/adult channels by display name.
        private val ADULT_NAME_PATTERNS = listOf(
            Regex("""\b18\+\s*\(""", RegexOption.IGNORE_CASE),         // "18+(Player-1)"
            Regex("""\b18\+\s*(?:onlyfans|porn|sex|adult|erotic|nude)""", RegexOption.IGNORE_CASE),
            Regex("""\bxxx\b""", RegexOption.IGNORE_CASE),              // standalone "xxx"
            Regex("""\b(?:porn|porno|pornhub|xnxx|xvideos)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:onlyfans|playboy)\b""", RegexOption.IGNORE_CASE),
        )

        /** O(1) source → SourceType mapping used during hosted-index processing. */
        val HOSTED_SOURCE_TYPE: Map<String, SourceType> = mapOf(
            "GLOBAL_INDEX" to SourceType.GLOBAL_INDEX,
            "IPTV" to SourceType.GLOBAL_INDEX,
            "FREE_TV" to SourceType.GLOBAL_INDEX,
            "FAST_TV" to SourceType.GLOBAL_INDEX,
            "PREMIUM" to SourceType.GLOBAL_INDEX,
            "FREE_CHANNEL" to SourceType.FREE_CHANNEL,
            "FREE_LIVE" to SourceType.FREE_CHANNEL,
            "RADIO" to SourceType.RADIO,
            "WORLD_TV" to SourceType.WORLD_TV,
            "STMIFY" to SourceType.WORLD_TV,
            "SPORTS_EVENTS" to SourceType.SPORTS_EVENTS,
            "DLHD" to SourceType.SPORTS_EVENTS,
            "YOUTUBE_TV" to SourceType.YOUTUBE_TV,
            "BROADCASTER" to SourceType.BROADCASTER,
            "VERIFIED" to SourceType.BROADCASTER,
            "INDEPENDENT" to SourceType.BROADCASTER,
        )
    }

    /** Returns true if the channel's display name matches adult-content patterns. */
    private fun isAdultChannel(ch: Channel): Boolean {
        val name = ch.displayName.lowercase().trim()
        return ADULT_NAME_PATTERNS.any { it.containsMatchIn(name) }
    }

    private val deadChannelSeed = setOf<String>()

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
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val channels: Flow<List<Channel>> =
        combine(_channels, sourcePreferences.enabledFlow) { chs, enabled ->
            if (chs.isEmpty()) return@combine emptyList()
            val placeholders = placeholderLogos(chs)
            val filtered = java.util.ArrayList<Channel>(chs.size)
            for (i in chs.indices) {
                val ch = chs[i]
                if (isAdultChannel(ch)) continue
                if (!hasEnabledSource(ch, enabled)) continue
                val finalCh = if (ch.logoUrl != null && ch.logoUrl in placeholders) ch.copy(logoUrl = null) else ch
                filtered.add(finalCh)
            }
            filtered.trimToSize()
            filtered
        }.flowOn(dispatchers.default)

    val channelSummaries: Flow<List<ChannelSummary>> =
        channels.map { list: List<Channel> -> list.map { it.toSummary() } }
            .flowOn(dispatchers.default)

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
        val counts = HashMap<String, Int>(chs.size / PLACEHOLDER_LOGO_THRESHOLD + 1)
        val placeholders = HashSet<String>()
        for (ch in chs) {
            val url = ch.logoUrl ?: continue
            if (url.isBlank()) continue
            if (url in placeholders) continue
            val newCount = (counts[url] ?: 0) + 1
            if (newCount >= PLACEHOLDER_LOGO_THRESHOLD) {
                counts.remove(url)
                placeholders.add(url)
            } else {
                counts[url] = newCount
            }
        }
        placeholderCacheFor = chs
        placeholderCache = placeholders
        return placeholders
    }

    // Full merged catalogue for search & radio — grows progressively with each source.
    /** Incremented after each load/reload so subscribers can re-read [getAllChannels]. */
    private val _channelRefreshTrigger = MutableStateFlow(0L)
    val channelRefreshTrigger: StateFlow<Long> = _channelRefreshTrigger.asStateFlow()

    /**
     * Radio stations for the dedicated "Radio" home row — surfaced only while the Radio source is
     * enabled. Capped so the home screen stays snappy (the full list lives in search).
     */
    val radioChannels: Flow<List<Channel>> =
        combine(_channels, sourcePreferences.enabledFlow) { all, enabled ->
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

    // Per-provider loading progress — updated in real-time as each source loads.
    private val _providerProgress = MutableStateFlow(
        SourceProvider.entries.associateWith { ProviderLoadingPhase.PENDING }
    )
    val providerProgress: StateFlow<Map<SourceProvider, ProviderLoadingPhase>> = _providerProgress.asStateFlow()

    // ── Progressive loading state ──────────────────────────────────────────────
    // Maximum concurrent source fetches (semaphore‑controlled).  Higher values load faster
    // but may saturate the device's network / memory.
    private val maxConcurrentLoads = 10
    private val loadSemaphore = Semaphore(maxConcurrentLoads)


    // Accumulated raw source results.  Each source loads into its own list, then
    // mergeSourceIntoState() feeds them into IncrementalMergeState one at a time.
    private var dlhdResults: List<com.streamverse.core.data.model.DlhdChannel> = emptyList()
    private var stmifyResults: List<com.streamverse.core.data.model.StmifyChannel> = emptyList()
    private var primeVideoResults: List<com.streamverse.core.data.model.StmifyChannel> = emptyList()
    private var iptvResults: List<IptvChannel> = emptyList()
    private var radioResults: List<com.streamverse.core.data.model.RadioStation> = emptyList()
    private var broadcasterResults: List<IptvChannel> = emptyList()
    private var freeLiveResults: List<FreeChannel> = emptyList()
    private var youtubeTvResults: List<YouTubeTvChannel> = emptyList()

    /** Runs a source fetch under a hard timeout; returns empty on timeout/error so the merge
     *  proceeds with whatever else loaded instead of hanging forever. */
    private suspend fun <T> fetchWithin(ms: Long, block: suspend () -> List<T>): List<T> =
        withTimeoutOrNull(ms) { runCatching { block() }.getOrDefault(emptyList()) } ?: emptyList()

    @Volatile private var lastEmitMs = 0L

    // Most recent search results (incl. synthesized Stmify channels) so the player can
    // resolve them by id when tapped.  Capped to avoid holding thousands of full Channel objects
    // for a broad query that the user never opens.
    @Volatile private var lastSearchResults: List<Channel> = emptyList()
        get() = field
        set(value) { field = if (value.size > LAST_SEARCH_MAX) value.take(LAST_SEARCH_MAX) else value }

    // O(1) ID lookup across all channel pools. Rebuilt on every merge.
    @Volatile private var _idIndex: Map<String, Channel> = emptyMap()

    /** A channel is shown only if at least one of its sources is from an enabled provider. */
    private fun hasEnabledSource(channel: Channel, enabled: Map<SourceProvider, Boolean>): Boolean =
        channel.sources.keys.any { enabled[SourceProvider.forType(it)] != false }

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
        if (!loadInProgress.compareAndSet(false, true)) {
            Log.d("ChannelLoad", "load already in progress — skipping duplicate call")
            return@withContext
        }
        try {
        resetSourceResults()
        _providerProgress.value = SourceProvider.entries.associateWith { ProviderLoadingPhase.PENDING }
        _loadingPhase.value = LoadingPhase.LOADING
        _isLoading.value = true
        val pm = PerformanceMonitor("ChannelLoad").also { it.start() }

        // ── Step 1: load cache & emit to UI IMMEDIATELY ─────────────────
        pm.phaseStarted("cache_load")
        val cached = cacheManager.load()
        if (cached != null && cached.isNotEmpty()) {
            _loadingPhase.value = LoadingPhase.CACHE
            _channels.value = cached
            _isLoading.value = false
        }
        pm.phaseEnded("cache_load")

        // ── Step 2: try pre-merged index (single fetch, no entity resolution) ──
        val mergedChannels = mergedIndexClient.fetchAll()
        if (mergedChannels != null && mergedChannels.isNotEmpty()) {
            pm.phaseStarted("source_fetch")
            Log.d("ChannelLoad", "Merged index: ${mergedChannels.size} channels")
            _channels.value = mergedChannels
            _idIndex = mergedChannels.associateBy { it.id }
            _providerProgress.value = _providerProgress.value.mapValues { ProviderLoadingPhase.COMPLETE }
            pm.phaseEnded("source_fetch", "${mergedChannels.size} channels")

            pm.phaseStarted("finalize")
            launch { cacheManager.save(mergedChannels) }
            launch { runCatching { smartCacheManager.runEvictionIfNeeded() } }
            launch { updateFtsIndex(mergedChannels) }
            pm.phaseEnded("finalize")

            _channelRefreshTrigger.value++
            _loadingPhase.value = LoadingPhase.DONE
            _isLoading.value = false
            Log.d("ChannelLoad", "TOTAL ${pm.elapsed("finalize")}ms — ${mergedChannels.size} channels (pre-merged)")
            pm.logSummary()
            return@withContext
        }
        Log.d("ChannelLoad", "Merged index unavailable — falling back to individual fetches")

        // ── Step 3 (fallback): build incremental state in background ──────
        val initialState = if (cached != null && cached.isNotEmpty()) {
            incrementalMergeState.apply { initializeFromChannels(cached) }
            incrementalMergeState.channels
        } else emptyList()

        // ── Step 4 (fallback): discover & launch ALL enabled sources ──────
        data class SourceDef(
            val provider: SourceProvider,
            val timeoutMs: Long,
            val tier: Int = 2,
            val skipMerge: Boolean = false,
            val load: suspend () -> Boolean,
        )

        fun buildDefs(): List<SourceDef> {
            val enabled = sourcePreferences.enabled()
            val defs = mutableListOf<SourceDef>()
            if (enabled[SourceProvider.BROADCASTER] != false) defs.add(SourceDef(SourceProvider.BROADCASTER, 5_000L, tier = 0) {
                broadcasterResults = broadcasterClient.fetchChannels(); broadcasterResults.isNotEmpty()
            })

            if (enabled[SourceProvider.WORLD_TV] != false) defs.add(SourceDef(SourceProvider.WORLD_TV, 45_000L, tier = 1) {
                coroutineScope {
                    val a = async { fetchWithin(45_000L) { stmifyClient.fetchChannels().getOrDefault(emptyList()) } }
                    val b = async { fetchWithin(45_000L) { stmifyClient.fetchChannelsFromArchive().getOrDefault(emptyList()) } }
                    val c = async { fetchWithin(10_000L) { primeVideoClient.fetchChannels().getOrDefault(emptyList()) } }
                    stmifyResults = (a.await() + b.await()).distinctBy { it.id }
                    primeVideoResults = c.await()
                }; stmifyResults.isNotEmpty() || primeVideoResults.isNotEmpty()
            })
            if (enabled[SourceProvider.FREE_CHANNEL] != false) defs.add(SourceDef(SourceProvider.FREE_CHANNEL, 20_000L, tier = 1) {
                freeLiveResults = fetchWithin(20_000L) { freeLiveClient.fetchChannels().getOrDefault(emptyList()) }
                    .filterNot { it.name.trim() in deadChannelNames }; freeLiveResults.isNotEmpty()
            })
            if (enabled[SourceProvider.YOUTUBE_TV] != false) defs.add(SourceDef(SourceProvider.YOUTUBE_TV, 15_000L, tier = 1) {
                youtubeTvResults = fetchWithin(15_000L) { youtubeTvClient.discoverChannels() }; youtubeTvResults.isNotEmpty()
            })
            if (enabled[SourceProvider.GLOBAL_INDEX] != false) defs.add(SourceDef(SourceProvider.GLOBAL_INDEX, 45_000L, tier = 2) {
                iptvResults = fetchWithin(45_000L) { iptvClient.fetchChannels().getOrDefault(emptyList()) }
                    .filterNot { it.name.trim() in deadChannelNames }; iptvResults.isNotEmpty()
            })
            if (enabled[SourceProvider.SPORTS_EVENTS] != false) defs.add(SourceDef(SourceProvider.SPORTS_EVENTS, 45_000L, tier = 2) {
                dlhdResults = fetchWithin(45_000L) { dlhdClient.fetchChannelsFromScrape().getOrDefault(emptyList()) }; dlhdResults.isNotEmpty()
            })
            if (enabled[SourceProvider.RADIO] != false) defs.add(SourceDef(SourceProvider.RADIO, 15_000L, tier = 2) {
                radioResults = fetchWithin(15_000L) { radioBrowserClient.fetchStations().getOrDefault(emptyList()) }; radioResults.isNotEmpty()
            })
            return defs
        }

        val defs = buildDefs()
        if (defs.isEmpty()) {
            _channelRefreshTrigger.value++
            _loadingPhase.value = LoadingPhase.DONE
            _isLoading.value = false
            return@withContext
        }

        val totalSources = defs.size
        var totalEmits = 0

        suspend fun processDef(def: SourceDef) {
            val useSemaphore = def.tier >= 2
            if (useSemaphore) loadSemaphore.acquire()
            _providerProgress.value = _providerProgress.value + (def.provider to ProviderLoadingPhase.LOADING)
            try {
                val t1 = System.currentTimeMillis()
                val hadData = def.load()
                Log.d("ChannelLoad", "${def.provider} ${if (hadData) "loaded" else "empty"} in ${System.currentTimeMillis() - t1}ms")
                _providerProgress.value = _providerProgress.value + (def.provider to
                    if (hadData) ProviderLoadingPhase.COMPLETE else ProviderLoadingPhase.FAILED)
                if (hadData && !def.skipMerge) {
                    mergeSourceIntoState(def.provider)
                    clearAccumulator(def.provider)
                }
                if (hadData) {
                    totalEmits++
                    if (totalEmits <= 3) {
                        emitMergedState()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs > 400L) { lastEmitMs = now; emitMergedState() }
                    }
                }
            } catch (e: Exception) {
                Log.w("ChannelLoad", "${def.provider} failed: ${e.message}")
                _providerProgress.value = _providerProgress.value + (def.provider to ProviderLoadingPhase.FAILED)
            } finally {
                if (useSemaphore) loadSemaphore.release()
            }
        }

        // Tier 0: instant local sources
        pm.phaseStarted("tier_0")
        for (def in defs.filter { it.tier == 0 }) { processDef(def) }
        pm.phaseEnded("tier_0")

        // Tier 1+2: try hosted index first
        pm.phaseStarted("source_fetch")
        val tierDefs = defs.filter { it.tier >= 1 }
        for (def in tierDefs) {
            _providerProgress.value = _providerProgress.value + (def.provider to ProviderLoadingPhase.LOADING)
        }
        val hostedIndexSucceeded = run {
            val all = hostedIndexClient.fetchAll()
            if (all.isEmpty()) false
            else {
                var kept = 0
                val byType = HashMap<SourceType, ArrayList<SourceItem>>(8)
                for (ch in all) {
                    val st = HOSTED_SOURCE_TYPE[ch.source] ?: continue
                    val list = byType.getOrPut(st) { ArrayList() }
                    list.add(SourceItem(
                        id = ch.id, name = ch.name, streamUrl = ch.streamUrl,
                        logoUrl = ch.logoUrl, category = ch.category,
                        country = ch.country, language = ch.language,
                        quality = ch.quality, headers = ch.headers ?: emptyMap(),
                        drmKeyId = ch.drmKeyId, drmKey = ch.drmKey,
                    ))
                    kept++
                }
                for ((sourceType, items) in byType) {
                    incrementalMergeState.mergeSources(items, sourceType)
                }
                emitMergedState()
                Log.d("ChannelLoad", "Hosted index: $kept channels in ${byType.size} source types (${all.size} raw)")
                _providerProgress.value = _providerProgress.value.mapValues { ProviderLoadingPhase.COMPLETE }
                true
            }
        }
        if (!hostedIndexSucceeded) {
            coroutineScope {
                for (def in tierDefs) {
                    async { processDef(def) }
                }
            }
        }
        pm.phaseEnded("source_fetch", "${incrementalMergeState.channels.size} channels")

        // ── Step 4: finalize — one sort + cache save ────────────────────
        pm.phaseStarted("finalize")
        val snapshot = incrementalMergeState.channels
        incrementalMergeState.release()
        val sorted = snapshot.sortedBy { sourceRank(it) }
        _channels.value = sorted
        _idIndex = sorted.associateBy { it.id }
        resetSourceResults()

        // Fire-and-forget: cache + FTS rebuild finish in the background so they
        // don't delay the cold‑start TOTAL metric.  These run on the IO dispatcher
        // (inherited from withContext at the top of load()).
        launch { cacheManager.save(sorted) }
        launch { runCatching { smartCacheManager.runEvictionIfNeeded() } }
        launch { updateFtsIndex(sorted) }
        pm.phaseEnded("finalize")

        _channelRefreshTrigger.value++
        _loadingPhase.value = LoadingPhase.DONE
        _isLoading.value = false
        Log.d("ChannelLoad", "TOTAL ${pm.elapsed("finalize")}ms — ${sorted.size} channels from ${totalSources} sources")
        pm.logSummary()
        } finally { loadInProgress.set(false) }
    }

    /** Merge one source type's accumulated results into the incremental state. */
    private suspend fun mergeSourceIntoState(provider: SourceProvider) {
        when (provider) {
            SourceProvider.SPORTS_EVENTS -> incrementalMergeState.mergeSources(
                dlhdResults.map { SourceItem(it.id, it.name, null, it.logoUrl, it.category, null, null, null) },
                SourceType.SPORTS_EVENTS,
            )
            SourceProvider.WORLD_TV -> {
                val allResults = stmifyResults + primeVideoResults
                incrementalMergeState.mergeSources(
                    allResults.map { SourceItem(it.id, it.name, null, it.logoUrl, it.genres.firstOrNull(), null, null, it.quality?.name) },
                    SourceType.WORLD_TV,
                )
            }
            SourceProvider.RADIO -> incrementalMergeState.mergeRadio(radioResults)
            SourceProvider.GLOBAL_INDEX -> incrementalMergeState.mergeSources(
                iptvResults.map { SourceItem(it.id, it.name, it.streamUrl, it.logoUrl, it.category, it.country, it.language, it.quality, headers = it.headers, drmKeyId = it.drmKeyId, drmKey = it.drmKey) },
                SourceType.GLOBAL_INDEX,
            )
            SourceProvider.BROADCASTER -> incrementalMergeState.mergeSources(
                broadcasterResults.map { SourceItem(it.id, it.name, it.streamUrl, it.logoUrl, it.category, it.country, it.language, it.quality, headers = it.headers, drmKeyId = it.drmKeyId, drmKey = it.drmKey) },
                SourceType.BROADCASTER,
            )
            SourceProvider.FREE_CHANNEL -> incrementalMergeState.mergeSources(
                freeLiveResults.map { SourceItem(it.id, it.name, it.streamUrl, it.logoUrl, it.category, it.country, null, null, headers = it.headers, drmKeyId = it.drmKeyId, drmKey = it.drmKey) },
                SourceType.FREE_CHANNEL,
            )
            SourceProvider.YOUTUBE_TV -> incrementalMergeState.mergeSources(
                youtubeTvResults.map { SourceItem(it.referenceId, it.displayName, it.liveUrl, null, it.category, it.country, it.language, null) },
                SourceType.YOUTUBE_TV,
            )
            else -> {}
        }
    }

    /** Emit the current merged state to the UI channels Flow. */
    private fun emitMergedState() {
        val snapshot = incrementalMergeState.channels
        _channels.value = snapshot
        val idx = HashMap<String, Channel>(snapshot.size)
        for (ch in snapshot) idx[ch.id] = ch
        _idIndex = idx
    }

    /** Rebuild the Room FTS search index from the current channel catalogue. */
    private suspend fun updateFtsIndex(channels: List<Channel>) {
        if (channels.isEmpty()) return
        try {
            channelSearchDao.clearAll()
            channels.chunked(200).forEach { chunk ->
                channelSearchDao.insertAll(chunk.map { ch ->
                    ChannelSearchFts(
                        channelId = ch.id,
                        displayName = ch.displayName,
                        aliases = ch.aliases.joinToString(" "),
                        category = ch.category ?: "",
                        language = ch.language ?: "",
                        country = ch.country ?: "",
                        description = ch.description ?: "",
                    )
                })
            }
        } catch (_: Exception) { /* FTS update is best-effort */ }
    }

    private val sourceRankMap: Map<SourceType, Int> by lazy {
        providerRegistry.prioritySorted().withIndex().associate { (i, st) -> st to i }
    }

    /** Best (highest) priority of this channel's sources — lower = earlier in list. */
    private fun sourceRank(ch: Channel): Int {
        var best = Int.MAX_VALUE
        for (st in ch.sources.keys) {
            val idx = sourceRankMap[st] ?: continue
            if (idx < best) best = idx
        }
        return best
    }

    /**
     * Re‑run the full merge pipeline with the current accumulated raw results and emit
     * the updated catalogue to [channels] / [_channels].
     */
    /** Clear all accumulated raw source results — called before a fresh load. */
    private fun resetSourceResults() {
        dlhdResults = emptyList()
        stmifyResults = emptyList()
        primeVideoResults = emptyList()
        iptvResults = emptyList()
        radioResults = emptyList()
        broadcasterResults = emptyList()
        freeLiveResults = emptyList()
        youtubeTvResults = emptyList()
    }

    /** Free one source's raw data immediately after merging to reduce peak memory. */
    private fun clearAccumulator(provider: SourceProvider) {
        when (provider) {
            SourceProvider.SPORTS_EVENTS -> dlhdResults = emptyList()
            SourceProvider.WORLD_TV -> { stmifyResults = emptyList(); primeVideoResults = emptyList() }
            SourceProvider.RADIO -> radioResults = emptyList()
            SourceProvider.GLOBAL_INDEX -> iptvResults = emptyList()
            SourceProvider.BROADCASTER -> broadcasterResults = emptyList()
            SourceProvider.FREE_CHANNEL -> freeLiveResults = emptyList()
            SourceProvider.YOUTUBE_TV -> youtubeTvResults = emptyList()
            else -> {}
        }
    }

    /**
     * Re‑fetch everything from scratch.  Invalidates the on‑disk cache and re‑runs [load].
     */
    suspend fun reload() {
        cacheManager.invalidate()
        lastSearchResults = emptyList()
        load()
    }

    // ── Search across all loaded sources ──

    // ── Search across premium (DLHD + Stmify) + extended (IPTV/FreeTV/Radio/FastTV) ──

    suspend fun searchChannels(query: String, category: String? = null): List<Channel> = withContext(dispatchers.io) {
        val q = query.lowercase().trim()
        val pool = _channels.value
        val localResults = if (q.isEmpty()) {
            if (category != null) pool.filter { it.category == category } else emptyList<Channel>()
        } else {
            val ql = q
            val catFilter = category?.lowercase()
            // Single-pass scored filter: assign each channel a relevance score, then sort once.
            // Avoids creating separate nameHits / attrHits lists.
            data class Scored(val channel: Channel, val score: Int)
            pool.mapNotNull { ch ->
                if (catFilter != null && ch.category?.lowercase() != catFilter) return@mapNotNull null
                val n = ch.displayName.lowercase()
                val score = when {
                    n == ql -> 0
                    n.startsWith(ql) -> 1
                    n.split(RE_SEARCH_WORD).any { it.startsWith(ql) } -> 2
                    ql in (ch.category?.lowercase() ?: "") -> 3
                    ql in (ch.language?.lowercase() ?: "") -> 3
                    ql in (ch.country?.lowercase() ?: "") -> 3
                    ch.aliases.any { ql in it.lowercase() } -> 3
                    ql in (ch.description?.lowercase() ?: "") -> 3
                    n.contains(ql) -> 4
                    else -> return@mapNotNull null
                }
                Scored(ch, score)
            }.sortedWith(
                compareBy<Scored> { it.score }.thenBy { it.channel.displayName.length }
            ).map { it.channel }
        }

        val stmifyResults = if (sourcePreferences.isEnabled(SourceProvider.WORLD_TV))
            stmifyClient.searchChannels(query).getOrDefault(emptyList()) else emptyList()

        if (stmifyResults.isEmpty()) {
            val enabled = sourcePreferences.enabled()
            return@withContext localResults.filter { hasEnabledSource(it, enabled) }
                .also { lastSearchResults = it }
        }
        val stmifyMerged = stmifyResults.map { stm ->
            val stmCanon = ChannelCanonicalizer.canonicalize(stm.name, entityResolutionEngine.aliasDictionary).hashKey
            val existing = localResults.find { ch ->
                ChannelCanonicalizer.canonicalize(ch.displayName, entityResolutionEngine.aliasDictionary).hashKey == stmCanon ||
                ch.id == "stmify_${stm.id}" ||
                ch.sources.values.any { it.referenceId == stm.id }
            }
            existing?.copy(
                sources = existing.sources + (SourceType.WORLD_TV to SourceInfo(
                    type = SourceType.WORLD_TV,
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
                sources = mapOf(SourceType.WORLD_TV to SourceInfo(type = SourceType.WORLD_TV, referenceId = stm.id)),
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
    fun getAllChannels(): List<Channel> = _channels.value

    /**
     * Returns the current ID-to-Channel index for O(1) lookups.  Callers that previously rebuilt a
     * map via [channels].map { it.associateBy { it.id } } should use this instead — it avoids
     * allocating a 52k-entry map on every emission.
     *
     * Note: the returned map includes channels whose sources may all be from disabled providers.
     * Always cross-reference with [availableChannelIds] before returning a channel to the user.
     */
    fun getChannelByIdMap(): Map<String, Channel> = _idIndex

    suspend fun getChannelById(id: String): Channel? {
        val indexed = _idIndex[id]
        val searched = lastSearchResults.find { it.id == id }
        return when {
            indexed != null && searched != null ->
                indexed.copy(sources = indexed.sources + searched.sources)
            else -> indexed ?: searched
        }
    }

    suspend fun getChannelBySourceRef(sourceType: SourceType, referenceId: String): Channel? {
        val refId = referenceId.trim().lowercase()
        return _idIndex.values.find { ch ->
            ch.sources[sourceType]?.referenceId?.trim()?.lowercase() == refId
        }
    }

    suspend fun getChannelsWithSource(sourceType: SourceType): List<Channel> = withContext(dispatchers.io) {
        _idIndex.values.filter { sourceType in it.sources }
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

    suspend fun getCategories(): List<String> =
        _channels.value.mapNotNull { it.category }.distinct().sorted()

}




