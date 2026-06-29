package com.streamverse.core.data

import android.content.Context
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.StreamPreResolver
import com.streamverse.core.util.StreamResolver
import com.streamverse.core.util.SourceResolutionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel as MpscChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One channel's verified availability + per-source scoring.
 *
 * [isLive] drives the LIVE badge across every surface. [checkedAt] gates re-checks (TTL) and
 * [failureStreak] drives exponential recheck back-off ("temporary blacklisting" of dead endpoints).
 */
data class ChannelHealth(
    val channelId: String,
    val isLive: Boolean,
    val checkedAt: Long,
    val latencyMs: Long = -1,
    val verifiedSource: SourceType? = null,
    val failureStreak: Int = 0,
)

/**
 * Background Channel Health Engine.
 *
 * Periodically verifies channel availability with cheap, concurrent, rate-limited HTTP probes and
 * publishes a **Live Availability Index** ([liveChannelIds]) that the UI binds LIVE badges to. It
 * never blocks the UI, never re-checks fresh entries, and prioritizes the channels the user is most
 * likely to watch (the screen tells it what is visible via [verify]).
 *
 * Probe policy (low bandwidth):
 *  • Direct-URL sources (IPTV/FreeTV/FAST/Independent/Radio) are probed with a `Range: bytes=0-0`
 *    GET — status + latency only, body never read.
 *  • Resolution-backed sources (DLHD/Stmify) are probed only when a pre-resolved URL is already
 *    cached, or — for explicitly prioritized ("deep") channels — resolved once via [StreamResolver].
 *  • A recorded successful playback ([SourceHealthPreferences]) or a curated INDEPENDENT source
 *    counts as positive evidence without any network call.
 *
 * Geo-awareness is implicit: probes run from the user's network, so a geo-blocked stream returns a
 * non-2xx and is correctly marked not-live for that user.
 */
@Singleton
class ChannelHealthEngine @Inject constructor(
    @ApplicationContext context: Context,
    private val streamPreResolver: StreamPreResolver,
    private val streamResolver: StreamResolver,
    private val sourceHealth: SourceHealthPreferences,
    private val sourceResolutionEngine: SourceResolutionEngine,
    private val providerRegistry: ProviderRegistry,
) {
    private val prefs = context.getSharedPreferences("sv_channel_health", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build()

    private val healthMap = ConcurrentHashMap<String, ChannelHealth>()
    private val registry = ConcurrentHashMap<String, Channel>()
    private val deepWanted = ConcurrentHashMap.newKeySet<String>()
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val work = MpscChannel<String>(MpscChannel.UNLIMITED)

    private val _liveChannelIds = MutableStateFlow<Set<String>>(emptySet())
    /** The Live Availability Index — channel ids verified currently available. UI binds badges here. */
    val liveChannelIds: StateFlow<Set<String>> = _liveChannelIds.asStateFlow()

    init {
        loadPersisted()
        repeat(MAX_CONCURRENT) {
            scope.launch {
                for (id in work) {
                    queued.remove(id)
                    runCatching { check(id) }
                }
            }
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(CLEANUP_INTERVAL_MS)
                cleanup()
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Snapshot health for a channel, if known. */
    fun health(channelId: String): ChannelHealth? = healthMap[channelId]

    fun isLive(channelId: String): Boolean = healthMap[channelId]?.isLive == true

    /**
     * Best source to open [channel] from: the last source that actually played it (persisted), else
     * the highest-priority source. The player's launch-time watchdog still handles live failover.
     */
    fun bestSource(channel: Channel): SourceType? =
        sourceResolutionEngine.bestSource(channel)

    fun availableSources(channel: Channel): List<SourceType> =
        channel.sources.keys.sortedBy { providerRegistry.priority(it) }

    /**
     * Schedule [channels] for verification, newest request first. [deep] permits a one-off stream
     * resolution for resolution-backed sources (use for small, high-value sets: favorites, recent,
     * the visible row) so premium channels can be verified too. Fresh entries are skipped.
     */
    fun verify(channels: List<Channel>, deep: Boolean = false) {
        for (ch in channels) {
            if (ch.sources.isEmpty()) continue
            registry[ch.id] = ch
            if (deep) deepWanted.add(ch.id)
            if (isFresh(ch.id)) continue
            if (queued.add(ch.id)) work.trySend(ch.id)
        }
    }

    // ── Verification ───────────────────────────────────────────────────────────

    private suspend fun check(id: String) {
        val channel = registry[id] ?: return
        val prev = healthMap[id]
        val deep = deepWanted.contains(id)

        // 1. Cheap positive evidence — no network needed.
        sourceHealth.lastGoodSource(id)?.let { good ->
            if (channel.sources.containsKey(good)) { record(id, true, -1, good, 0); return }
        }

        // 2. Probe the cheapest reachable URL per source, best-priority first, until one is live.
        for (type in availableSources(channel)) {
            val info = channel.sources[type] ?: continue
            val url = probeUrlFor(channel.id, type, info, deep) ?: continue
            val started = System.currentTimeMillis()
            val ok = probe(url, info.headers)
            if (ok) {
                record(id, true, System.currentTimeMillis() - started, type, 0)
                return
            }
        }

        // 3. Curated sources are presumed live even if we couldn't obtain a probe URL cheaply.
        if (channel.sources.containsKey(SourceType.VERIFIED)) {
            record(id, true, -1, SourceType.VERIFIED, 0); return
        }
        if (channel.sources.containsKey(SourceType.BROADCASTER)) {
            record(id, true, -1, SourceType.BROADCASTER, 0); return
        }

        record(id, false, -1, null, (prev?.failureStreak ?: 0) + 1)
    }

    /** A directly-probeable URL for a source, or null if obtaining one would be too expensive. */
    private suspend fun probeUrlFor(channelId: String, type: SourceType, info: SourceInfo, deep: Boolean): String? {
        info.streamUrl?.takeIf { it.isNotBlank() }?.let { return it }
        streamPreResolver.getCached(channelId, type)?.firstOrNull { !it.requiresBrowser && !it.forceWebView }?.let { return it.url }
        if (!deep) return null
        // Deep (prioritized) only: resolve once.
        return withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            runCatching { streamResolver.resolveAll(info) }.getOrNull()
                ?.firstOrNull { !it.requiresBrowser && !it.forceWebView }?.url
        }
    }

    private suspend fun probe(url: String, headers: Map<String, String>): Boolean = withTimeoutOrNull(PROBE_TIMEOUT_MS * 2) {
        val builder = Request.Builder().url(url).header("Range", "bytes=0-0")
        if (headers["User-Agent"] == null) builder.header("User-Agent", DEFAULT_UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        runCatching {
            probeClient.newCall(builder.get().build()).execute().use { resp ->
                resp.isSuccessful || resp.code == 206
            }
        }.getOrDefault(false)
    } ?: false

    private fun record(id: String, live: Boolean, latency: Long, source: SourceType?, streak: Int) {
        healthMap[id] = ChannelHealth(id, live, System.currentTimeMillis(), latency, source, streak)
        recomputeLiveIndex()
        persist(id, live)
    }

    private fun recomputeLiveIndex() {
        _liveChannelIds.value = healthMap.asSequence().filter { it.value.isLive }.map { it.key }.toHashSet()
    }

    // ── Freshness / back-off ─────────────────────────────────────────────────

    private fun isFresh(id: String): Boolean {
        val h = healthMap[id] ?: return false
        val age = System.currentTimeMillis() - h.checkedAt
        val ttl = if (h.isLive) LIVE_TTL_MS
        else (UNHEALTHY_BASE_TTL_MS shl h.failureStreak.coerceIn(0, 4)).coerceAtMost(MAX_BACKOFF_MS)
        return age < ttl
    }

    // ── Persistence (badges survive restarts; incremental re-verify) ───────────

    private fun persist(id: String, live: Boolean) {
        // Store only live entries; a missing entry simply means "unknown" next launch.
        val now = System.currentTimeMillis()
        val entries = healthMap.values.asSequence()
            .filter { it.isLive && now - it.checkedAt < LIVE_TTL_MS }
            .take(MAX_PERSISTED)
            .joinToString("\n") { "${it.channelId}\t${it.checkedAt}" }
        prefs.edit().putString(KEY_LIVE, entries).apply()
    }

    private fun loadPersisted() {
        val now = System.currentTimeMillis()
        prefs.getString(KEY_LIVE, null)?.split("\n")?.forEach { line ->
            val i = line.indexOf('\t')
            if (i <= 0) return@forEach
            val id = line.substring(0, i)
            val ts = line.substring(i + 1).toLongOrNull() ?: return@forEach
            if (now - ts < LIVE_TTL_MS) healthMap[id] = ChannelHealth(id, true, ts)
        }
        recomputeLiveIndex()
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        val stale = healthMap.filterValues { !it.isLive && now - it.checkedAt > STALE_ENTRY_AGE_MS }
        stale.keys.forEach { healthMap.remove(it) }
        if (healthMap.size > HEALTH_MAP_MAX_SIZE) {
            val sorted = healthMap.entries.sortedBy { it.value.checkedAt }
            val toRemove = sorted.take(healthMap.size - HEALTH_MAP_MAX_SIZE).map { it.key }
            toRemove.forEach { healthMap.remove(it) }
        }
        if (stale.isNotEmpty() || healthMap.size > HEALTH_MAP_MAX_SIZE) recomputeLiveIndex()
    }

    private companion object {
        const val MAX_CONCURRENT = 4
        const val PROBE_TIMEOUT_MS = 4_000L
        const val RESOLVE_TIMEOUT_MS = 8_000L
        const val LIVE_TTL_MS = 30 * 60 * 1_000L
        const val UNHEALTHY_BASE_TTL_MS = 5 * 60 * 1_000L
        const val MAX_BACKOFF_MS = 60 * 60 * 1_000L
        const val MAX_PERSISTED = 1_500
        const val KEY_LIVE = "live"
        const val STALE_ENTRY_AGE_MS = 24 * 60 * 60 * 1_000L
        const val HEALTH_MAP_MAX_SIZE = 2_000
        const val CLEANUP_INTERVAL_MS = 30 * 60 * 1_000L
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Priority is now centralized in ProviderRegistry
    
    }
}
