package com.streamverse.core.data

import android.content.Context
import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.SourceResolutionEngine
import com.streamverse.core.util.StreamPreResolver
import com.streamverse.core.util.StreamResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel as MpscChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.FlowPreview
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Per-source health state for a single playback source on a channel. */
enum class SourceHealthState {
    UNKNOWN,
    VERIFYING,
    AVAILABLE,
    UNAVAILABLE,
}

/** One channel's verified availability + per-source scoring. */
data class ChannelHealth(
    val channelId: String,
    val isLive: Boolean,
    val checkedAt: Long,
    val latencyMs: Long = -1,
    val verifiedSource: SourceType? = null,
    val failureStreak: Int = 0,
)

/**
 * Per-channel, per-source health snapshot with confidence scoring.
 *
 * [validationConfidence] is a 0.0–1.0 score derived from:
 * - HTTP probe success → 0.3
 * - Single playback success → 0.6
 * - Multiple consecutive playback successes → 0.9+
 * - Recent playback failure halves confidence
 * - Stale data decays confidence toward 0 over time
 */
data class SourceHealth(
    val type: SourceType,
    val state: SourceHealthState = SourceHealthState.UNKNOWN,
    val latencyMs: Long = -1,
    val lastCheckedMs: Long = 0,
    val consecutiveFailures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val averageStartupTimeMs: Long = 0,
    val lastPlaybackSuccessMs: Long = 0,
    val lastPlaybackFailureMs: Long = 0,
    val validationConfidence: Float = 0f,
) {
    fun isStale(): Boolean {
        val now = System.currentTimeMillis()
        if (lastPlaybackSuccessMs > 0) return now - lastPlaybackSuccessMs > PLAYBACK_LIVE_TTL_MS
        return now - lastCheckedMs > PROBE_LIVE_TTL_MS
    }

    /** True when this source has enough confidence to be considered playable. */
    fun isPlaybackVerified(): Boolean =
        validationConfidence >= MIN_CONFIDENCE_FOR_LIVE && !isStale()

    companion object {
        const val MIN_CONFIDENCE_FOR_LIVE = 0.5f
        const val PROBE_LIVE_TTL_MS = 30 * 60 * 1000L
        const val PLAYBACK_LIVE_TTL_MS = 60 * 60 * 1000L
    }
}

/** Event emitted when playback health state changes — drives targeted UI updates. */
sealed interface PlaybackEvent {
    data class SourceValidated(val channelId: String, val sourceType: SourceType, val confidence: Float) : PlaybackEvent
    data class SourceFailed(val channelId: String, val sourceType: SourceType, val reason: String) : PlaybackEvent
    data class PlaybackStarted(val channelId: String, val sourceType: SourceType) : PlaybackEvent
    data class PlaybackStopped(val channelId: String) : PlaybackEvent
    data class SourceSwitched(val channelId: String, val from: SourceType?, val to: SourceType) : PlaybackEvent
}

@OptIn(FlowPreview::class)
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
    private val perSourceHealth = ConcurrentHashMap<String, MutableMap<SourceType, SourceHealth>>()
    private val registry = ConcurrentHashMap<String, Channel>()
    private val deepWanted = ConcurrentHashMap.newKeySet<String>()
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val work = MpscChannel<String>(MpscChannel.UNLIMITED)

    private val _liveChannelIds = MutableStateFlow<Set<String>>(emptySet())
    val liveChannelIds: StateFlow<Set<String>> = _liveChannelIds.asStateFlow()

    private val _sourceHealthUpdates = MutableStateFlow<Map<String, Map<SourceType, SourceHealth>>>(emptyMap())
    val sourceHealthUpdates: StateFlow<Map<String, Map<SourceType, SourceHealth>>> = _sourceHealthUpdates.asStateFlow()

    /** Event bus for targeted playback health updates — only subscribed components react. */
    private val _playbackEvents = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    val playbackEvents: SharedFlow<PlaybackEvent> = _playbackEvents.asSharedFlow()

    /** Signals that a recompute of _liveChannelIds is needed. Debounced to avoid
     *  flooding the main thread with badge refreshes during bulk verification. */
    private val _recomputeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private var recomputeJob: Job? = null

    init {
        loadPersisted()
        recomputeLiveIndex()

        recomputeJob = scope.launch {
            _recomputeSignal.debounce(RECOMPUTE_DEBOUNCE_MS).collect {
                recomputeLiveIndex()
            }
        }

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

    fun health(channelId: String): ChannelHealth? = healthMap[channelId]

    fun isLive(channelId: String): Boolean {
        val ch = healthMap[channelId] ?: return false
        if (!ch.isLive) return false
        val perSource = perSourceHealth[channelId] ?: return false
        return perSource.values.any { it.isPlaybackVerified() }
    }

    fun sourceHealthForChannel(channelId: String): Map<SourceType, SourceHealth> =
        perSourceHealth[channelId] ?: emptyMap()

    fun bestSource(channel: Channel): SourceType? =
        sourceResolutionEngine.bestSource(channel)

    fun bestVerifiedSource(channel: Channel): SourceType? {
        val chId = channel.id
        val perSource = perSourceHealth[chId] ?: return null
        for (type in availableSources(channel)) {
            val sh = perSource[type] ?: continue
            if (sh.isPlaybackVerified()) return type
        }
        return null
    }

    fun availableSources(channel: Channel): List<SourceType> =
        channel.sources.keys.sortedBy { providerRegistry.priority(it) }

    fun verify(channels: List<Channel>, deep: Boolean = false) {
        for (ch in channels) {
            if (ch.sources.isEmpty()) continue
            registry[ch.id] = ch
            if (deep) deepWanted.add(ch.id)
            if (isFresh(ch.id)) continue
            if (queued.add(ch.id)) work.trySend(ch.id)
        }
    }

    /**
     * Records an HTTP probe-based success (low confidence).
     * Used internally by [check] after a successful endpoint reachability test.
     * Sets confidence to 0.3 — enough for a candidate, not enough for Live badge.
     */
    fun recordExternalSuccess(channelId: String, sourceType: SourceType) {
        val prev = healthMap[channelId]
        val newStreak = if (prev?.isLive == true) 0 else (prev?.failureStreak ?: 0)
        record(channelId, true, -1, sourceType, newStreak)
        val existing = perSourceHealth[channelId]?.get(sourceType)
        val confidence = maxOf(existing?.validationConfidence ?: 0f, PROBE_CONFIDENCE)
        markSource(channelId, sourceType, SourceHealthState.AVAILABLE, -1,
            consecutiveSuccesses = (existing?.consecutiveSuccesses ?: 0) + 1,
            consecutiveFailures = 0,
            validationConfidence = confidence)
    }

    /**
     * Records a confirmed playback success (high confidence).
     * Called ONLY from player callbacks after the stream has started playing.
     * Confidence rises quickly with consecutive successes: 0.6 → 0.75 → 0.85 → 0.9+
     */
    fun recordPlaybackSuccess(channelId: String, sourceType: SourceType, startupTimeMs: Long = -1) {
        val now = System.currentTimeMillis()
        val existing = perSourceHealth[channelId]?.get(sourceType)
        val successes = (existing?.consecutiveSuccesses ?: 0) + 1
        val failures = 0
        val avgStartup = if (startupTimeMs > 0 && (existing?.averageStartupTimeMs ?: 0) > 0) {
            ((existing?.averageStartupTimeMs ?: 0) * 3 + startupTimeMs) / 4
        } else {
            startupTimeMs.coerceAtLeast(existing?.averageStartupTimeMs ?: 0)
        }
        val confidence = playbackConfidence(successes, 0, now - (existing?.lastPlaybackFailureMs ?: 0))
        sourceHealth.recordSuccess(channelId, sourceType)
        val prev = healthMap[channelId]
        val newStreak = if (prev?.isLive == true) 0 else (prev?.failureStreak ?: 0)
        record(channelId, true, -1, sourceType, newStreak)
        markSource(channelId, sourceType, SourceHealthState.AVAILABLE, -1,
            consecutiveSuccesses = successes,
            consecutiveFailures = failures,
            averageStartupTimeMs = avgStartup,
            lastPlaybackSuccessMs = now,
            validationConfidence = confidence)
        _playbackEvents.tryEmit(PlaybackEvent.SourceValidated(channelId, sourceType, confidence))
    }

    /**
     * Records a confirmed playback failure.
     * Consecutive failures halve confidence. After 3+ failures the source falls below
     * MIN_CONFIDENCE_FOR_LIVE and no longer contributes to the Live badge.
     */
    fun recordPlaybackFailure(channelId: String, sourceType: SourceType, reason: String = "") {
        val now = System.currentTimeMillis()
        val existing = perSourceHealth[channelId]?.get(sourceType)
        val failures = (existing?.consecutiveFailures ?: 0) + 1
        val successes = 0
        val confidence = playbackConfidence(0, failures, 0L)
        val state = if (failures >= 3) SourceHealthState.UNAVAILABLE else SourceHealthState.AVAILABLE
        markSource(channelId, sourceType, state, -1,
            consecutiveSuccesses = successes,
            consecutiveFailures = failures,
            lastPlaybackFailureMs = now,
            validationConfidence = confidence)
        if (failures >= 3) {
            // Check if any source still has enough confidence for Live badge
            val anyLive = perSourceHealth[channelId]?.values?.any {
                it.validationConfidence >= SourceHealth.MIN_CONFIDENCE_FOR_LIVE && !it.isStale()
            } == true
            val prev = healthMap[channelId]
            if (!anyLive && prev?.isLive == true) {
                record(channelId, false, -1, null, (prev.failureStreak) + 1)
            }
        }
        _playbackEvents.tryEmit(PlaybackEvent.SourceFailed(channelId, sourceType, reason))
    }

    fun recordExternalFailure(channelId: String, sourceType: SourceType) {
        val existing = perSourceHealth[channelId]?.get(sourceType)
        val failures = (existing?.consecutiveFailures ?: 0) + 1
        val state = if (failures >= 3) SourceHealthState.UNAVAILABLE else SourceHealthState.VERIFYING
        markSource(channelId, sourceType, state, -1,
            consecutiveFailures = failures,
            consecutiveSuccesses = 0,
            validationConfidence = playbackConfidence(0, failures, 0L))
        if (perSourceHealth[channelId]?.values?.any { it.validationConfidence >= SourceHealth.MIN_CONFIDENCE_FOR_LIVE } == true) return
        val prev = healthMap[channelId]
        if (prev?.isLive == true) {
            record(channelId, false, -1, null, (prev.failureStreak) + 1)
        }
    }

    /** Compute playback confidence from success/failure history. */
    private fun playbackConfidence(consecutiveSuccesses: Int, consecutiveFailures: Int, msSinceLastFailure: Long): Float {
        if (consecutiveFailures >= 3) return 0.1f
        if (consecutiveFailures >= 1 && msSinceLastFailure < 60_000L) return 0.2f
        val base = when {
            consecutiveSuccesses >= 5 -> 0.95f
            consecutiveSuccesses >= 3 -> 0.85f
            consecutiveSuccesses >= 1 -> 0.60f + (consecutiveSuccesses - 1) * 0.15f
            else -> 0.0f
        }
        val failurePenalty = consecutiveFailures * 0.15f
        return (base - failurePenalty).coerceIn(0f, 1f)
    }

    private suspend fun check(id: String) {
        val channel = registry[id] ?: return
        val prev = healthMap[id]
        val deep = deepWanted.contains(id)

        var anyAvailable = false

        // Mark all sources as verifying initially
        for (type in availableSources(channel)) {
            markSource(id, type, SourceHealthState.VERIFYING, -1)
        }

        // 1. Cheap positive evidence — restore from previous playback validation if still fresh
        sourceHealth.lastGoodSource(id)?.let { good ->
            if (channel.sources.containsKey(good)) {
                val existing = perSourceHealth[id]?.get(good)
                if (existing != null && existing.lastPlaybackSuccessMs > 0 &&
                    !existing.isStale() && existing.validationConfidence >= SourceHealth.MIN_CONFIDENCE_FOR_LIVE) {
                    markSource(id, good, SourceHealthState.AVAILABLE, -1,
                        consecutiveSuccesses = existing.consecutiveSuccesses,
                        consecutiveFailures = existing.consecutiveFailures,
                        lastPlaybackSuccessMs = existing.lastPlaybackSuccessMs,
                        validationConfidence = existing.validationConfidence)
                    record(id, true, -1, good, 0)
                    return
                }
            }
        }

        // 2. Probe each source
        for (type in availableSources(channel)) {
            val info = channel.sources[type] ?: continue
            val url = probeUrlFor(channel.id, type, info, deep) ?: continue
            val started = System.currentTimeMillis()
            val ok = probe(url, info.headers)
            val latency = System.currentTimeMillis() - started
            val existing = perSourceHealth[id]?.get(type)
            if (ok) {
                val retainedConfidence = if (existing?.lastPlaybackSuccessMs ?: 0 > 0 &&
                    !(existing?.isStale() ?: true)) {
                    maxOf(existing?.validationConfidence ?: 0f, PROBE_CONFIDENCE)
                } else PROBE_CONFIDENCE
                markSource(id, type, SourceHealthState.AVAILABLE, latency,
                    consecutiveSuccesses = maxOf(existing?.consecutiveSuccesses ?: 0, 1),
                    validationConfidence = retainedConfidence)
                anyAvailable = true
            } else {
                val failures = (existing?.consecutiveFailures ?: 0) + 1
                markSource(id, type, SourceHealthState.UNAVAILABLE, -1,
                    consecutiveFailures = failures,
                    consecutiveSuccesses = 0,
                    validationConfidence = playbackConfidence(0, failures, 0L))
            }
        }

        if (anyAvailable) {
            val liveSource = perSourceHealth[id]?.filterValues { it.state == SourceHealthState.AVAILABLE }
                ?.keys?.firstOrNull()
            record(id, true, -1, liveSource, 0)
            return
        }

        // 3. Curated source fallback — only if we have no probe data yet
        if (channel.sources.containsKey(SourceType.VERIFIED)) {
            markSource(id, SourceType.VERIFIED, SourceHealthState.AVAILABLE, -1,
                validationConfidence = PROBE_CONFIDENCE)
            record(id, true, -1, SourceType.VERIFIED, 0)
            return
        }
        if (channel.sources.containsKey(SourceType.BROADCASTER)) {
            markSource(id, SourceType.BROADCASTER, SourceHealthState.AVAILABLE, -1,
                validationConfidence = PROBE_CONFIDENCE)
            record(id, true, -1, SourceType.BROADCASTER, 0)
            return
        }

        record(id, false, -1, null, (prev?.failureStreak ?: 0) + 1)
    }

    private fun markSource(
        channelId: String, type: SourceType, state: SourceHealthState, latency: Long,
        consecutiveFailures: Int = -1, consecutiveSuccesses: Int = -1,
        averageStartupTimeMs: Long = -1, lastPlaybackSuccessMs: Long = -1,
        lastPlaybackFailureMs: Long = -1, validationConfidence: Float = -1f,
    ) {
        val perChannel = perSourceHealth.getOrPut(channelId) { ConcurrentHashMap() }
        val prev = perChannel[type]
        perChannel[type] = SourceHealth(
            type = type,
            state = state,
            latencyMs = if (latency >= 0) latency else (prev?.latencyMs ?: -1),
            lastCheckedMs = System.currentTimeMillis(),
            consecutiveFailures = if (consecutiveFailures >= 0) consecutiveFailures else (prev?.consecutiveFailures ?: 0),
            consecutiveSuccesses = if (consecutiveSuccesses >= 0) consecutiveSuccesses else (prev?.consecutiveSuccesses ?: 0),
            averageStartupTimeMs = if (averageStartupTimeMs >= 0) averageStartupTimeMs else (prev?.averageStartupTimeMs ?: 0),
            lastPlaybackSuccessMs = if (lastPlaybackSuccessMs >= 0) lastPlaybackSuccessMs else (prev?.lastPlaybackSuccessMs ?: 0),
            lastPlaybackFailureMs = if (lastPlaybackFailureMs >= 0) lastPlaybackFailureMs else (prev?.lastPlaybackFailureMs ?: 0),
            validationConfidence = if (validationConfidence >= 0f) validationConfidence else (prev?.validationConfidence ?: 0f),
        )
        _sourceHealthUpdates.value = _sourceHealthUpdates.value + (channelId to perChannel.toMap())
    }

    private suspend fun probeUrlFor(channelId: String, type: SourceType, info: SourceInfo, deep: Boolean): String? {
        info.streamUrl?.takeIf { it.isNotBlank() }?.let { return it }
        streamPreResolver.getCached(channelId, type)?.firstOrNull { !it.requiresBrowser && !it.forceWebView }?.let { return it.url }
        if (!deep) return null
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
        _recomputeSignal.tryEmit(Unit)
        persist(id, live)
    }

    private fun recomputeLiveIndex() {
        _liveChannelIds.value = healthMap.asSequence()
            .filter { entry ->
                if (!entry.value.isLive) return@filter false
                // Verify at least one source has sufficient confidence
                val perSource = perSourceHealth[entry.key] ?: return@filter false
                perSource.values.any { it.isPlaybackVerified() }
            }
            .map { it.key }
            .toHashSet()
    }

    private fun isFresh(id: String): Boolean {
        val h = healthMap[id] ?: return false
        val age = System.currentTimeMillis() - h.checkedAt
        val ttl = if (h.isLive) LIVE_TTL_MS
        else (UNHEALTHY_BASE_TTL_MS shl h.failureStreak.coerceIn(0, 4)).coerceAtMost(MAX_BACKOFF_MS)
        return age < ttl
    }

    private fun persist(id: String, live: Boolean) {
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
        stale.keys.forEach { perSourceHealth.remove(it) }
        if (healthMap.size > HEALTH_MAP_MAX_SIZE) {
            val sorted = healthMap.entries.sortedBy { it.value.checkedAt }
            val toRemove = sorted.take(healthMap.size - HEALTH_MAP_MAX_SIZE).map { it.key }
            toRemove.forEach { healthMap.remove(it) }
            toRemove.forEach { perSourceHealth.remove(it) }
        }
        if (stale.isNotEmpty() || healthMap.size > HEALTH_MAP_MAX_SIZE) _recomputeSignal.tryEmit(Unit)
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
        const val RECOMPUTE_DEBOUNCE_MS = 300L
        const val PROBE_CONFIDENCE = 0.5f
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
