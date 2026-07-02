package com.streamverse.core.data

import android.content.Context
import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.SourceResolutionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class SourceHealthState {
    UNKNOWN,
    VERIFYING,
    AVAILABLE,
    UNAVAILABLE,
}

data class ChannelHealth(
    val channelId: String,
    val isLive: Boolean,
    val checkedAt: Long,
    val latencyMs: Long = -1,
    val verifiedSource: SourceType? = null,
    val failureStreak: Int = 0,
)

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

    fun isPlaybackVerified(): Boolean =
        validationConfidence >= MIN_CONFIDENCE_FOR_LIVE && !isStale()

    companion object {
        const val MIN_CONFIDENCE_FOR_LIVE = 0.5f
        const val PROBE_LIVE_TTL_MS = 30 * 60 * 1000L
        const val PLAYBACK_LIVE_TTL_MS = 60 * 60 * 1000L
    }
}

sealed interface PlaybackEvent {
    data class SourceValidated(val channelId: String, val sourceType: SourceType, val confidence: Float) : PlaybackEvent
    data class SourceFailed(val channelId: String, val sourceType: SourceType, val reason: String) : PlaybackEvent
    data class PlaybackStarted(val channelId: String, val sourceType: SourceType) : PlaybackEvent
    data class PlaybackStopped(val channelId: String) : PlaybackEvent
    data class SourceSwitched(val channelId: String, val from: SourceType?, val to: SourceType) : PlaybackEvent
}

@Singleton
class ChannelHealthEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceHealth: SourceHealthPreferences,
    private val sourceResolutionEngine: SourceResolutionEngine,
    private val providerRegistry: ProviderRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val prefs = context.getSharedPreferences("sv_channel_health", Context.MODE_PRIVATE)

    private val healthMap = ConcurrentHashMap<String, ChannelHealth>()
    private val perSourceHealth = ConcurrentHashMap<String, MutableMap<SourceType, SourceHealth>>()

    private val _liveChannelIds = MutableStateFlow<Set<String>>(emptySet())
    val liveChannelIds: StateFlow<Set<String>> = _liveChannelIds.asStateFlow()

    private val _sourceHealthUpdates = MutableStateFlow<Map<String, Map<SourceType, SourceHealth>>>(emptyMap())
    val sourceHealthUpdates: StateFlow<Map<String, Map<SourceType, SourceHealth>>> = _sourceHealthUpdates.asStateFlow()

    private val _playbackEvents = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    val playbackEvents: SharedFlow<PlaybackEvent> = _playbackEvents.asSharedFlow()

    init {
        loadPersisted()
        recomputeLiveIndex()
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

    fun degradedSourceCount(): Int = healthMap.count { (_, health) ->
        !health.isLive && health.failureStreak >= 2
    }

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
        // No-op: GitHub pipeline handles stream validation.
        // Playback-based tracking handled by recordPlaybackSuccess/Failure.
    }

    fun recordPlaybackSuccess(channelId: String, sourceType: SourceType, startupTimeMs: Long = -1) {
        val now = System.currentTimeMillis()
        val existing = perSourceHealth[channelId]?.get(sourceType)
        val successes = (existing?.consecutiveSuccesses ?: 0) + 1
        val failures = 0
        val avgStartup = if (startupTimeMs > 0 && (existing?.averageStartupTimeMs ?: 0) > 0) {
            ((existing?.averageStartupTimeMs ?: 0) * 3 + startupTimeMs) / 4
        } else startupTimeMs.coerceAtLeast(existing?.averageStartupTimeMs ?: 0)
        val confidence = playbackConfidence(successes, 0, now - (existing?.lastPlaybackFailureMs ?: 0))
        sourceHealth.recordSuccess(channelId, sourceType)
        record(channelId, true, -1, sourceType, 0)
        markSource(channelId, sourceType, SourceHealthState.AVAILABLE, -1,
            consecutiveSuccesses = successes,
            consecutiveFailures = failures,
            averageStartupTimeMs = avgStartup,
            lastPlaybackSuccessMs = now,
            validationConfidence = confidence)
        _playbackEvents.tryEmit(PlaybackEvent.SourceValidated(channelId, sourceType, confidence))
    }

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

    fun recordExternalSuccess(channelId: String, sourceType: SourceType) {
        val existing = perSourceHealth[channelId]?.get(sourceType)
        val confidence = maxOf(existing?.validationConfidence ?: 0f, 0.5f)
        markSource(channelId, sourceType, SourceHealthState.AVAILABLE, -1,
            consecutiveSuccesses = maxOf(existing?.consecutiveSuccesses ?: 0, 1),
            consecutiveFailures = 0,
            validationConfidence = confidence)
        record(channelId, true, -1, sourceType, 0)
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

    private fun markSource(
        channelId: String, type: SourceType, state: SourceHealthState, latency: Long,
        consecutiveFailures: Int = -1, consecutiveSuccesses: Int = -1,
        averageStartupTimeMs: Long = -1, lastPlaybackSuccessMs: Long = -1,
        lastPlaybackFailureMs: Long = -1, validationConfidence: Float = -1f,
    ) {
        val perChannel = perSourceHealth.getOrPut(channelId) { ConcurrentHashMap() }
        val prev = perChannel[type]
        perChannel[type] = SourceHealth(
            type = type, state = state,
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

    private fun record(id: String, live: Boolean, latency: Long, source: SourceType?, streak: Int) {
        healthMap[id] = ChannelHealth(id, live, System.currentTimeMillis(), latency, source, streak)
        recomputeLiveIndex()
        persist(id, live)
    }

    private fun recomputeLiveIndex() {
        _liveChannelIds.value = healthMap.asSequence()
            .filter { entry ->
                if (!entry.value.isLive) return@filter false
                val perSource = perSourceHealth[entry.key] ?: return@filter false
                perSource.values.any { it.isPlaybackVerified() }
            }
            .map { it.key }
            .toHashSet()
    }

    private fun persist(id: String, live: Boolean) {
        val now = System.currentTimeMillis()
        val entries = healthMap.values.asSequence()
            .filter { it.isLive && now - it.checkedAt < 30 * 60 * 1000L }
            .take(1500)
            .joinToString("\n") { "${it.channelId}\t${it.checkedAt}" }
        prefs.edit().putString("live", entries).apply()
    }

    private fun loadPersisted() {
        val now = System.currentTimeMillis()
        prefs.getString("live", null)?.split("\n")?.forEach { line ->
            val i = line.indexOf('\t')
            if (i <= 0) return@forEach
            val id = line.substring(0, i)
            val ts = line.substring(i + 1).toLongOrNull() ?: return@forEach
            if (now - ts < 30 * 60 * 1000L) healthMap[id] = ChannelHealth(id, true, ts)
        }
        recomputeLiveIndex()
    }
}
