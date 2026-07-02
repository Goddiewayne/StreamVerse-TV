package com.streamverse.core.data

import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.SourceResolutionEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class ChannelHealth(
    val channelId: String,
    val isLive: Boolean,
    val checkedAt: Long,
    val latencyMs: Long = -1,
    val verifiedSource: SourceType? = null,
)

data class SourceHealth(
    val type: SourceType,
    val latencyMs: Long = -1,
    val lastCheckedMs: Long = 0,
    val consecutiveFailures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val averageStartupTimeMs: Long = 0,
    val lastPlaybackSuccessMs: Long = 0,
    val lastPlaybackFailureMs: Long = 0,
)

sealed interface PlaybackEvent {
    data class SourceValidated(val channelId: String, val sourceType: SourceType, val confidence: Float) : PlaybackEvent
    data class SourceFailed(val channelId: String, val sourceType: SourceType, val reason: String) : PlaybackEvent
    data class PlaybackStarted(val channelId: String, val sourceType: SourceType) : PlaybackEvent
    data class PlaybackStopped(val channelId: String) : PlaybackEvent
    data class SourceSwitched(val channelId: String, val from: SourceType?, val to: SourceType) : PlaybackEvent
}

@Singleton
class ChannelHealthEngine @Inject constructor(
    private val sourceHealth: SourceHealthPreferences,
    private val sourceResolutionEngine: SourceResolutionEngine,
    private val providerRegistry: ProviderRegistry,
) {
    private val healthMap = ConcurrentHashMap<String, ChannelHealth>()
    private val perSourceHealth = ConcurrentHashMap<String, MutableMap<SourceType, SourceHealth>>()

    private val _liveChannelIds = MutableStateFlow<Set<String>>(emptySet())
    val liveChannelIds: StateFlow<Set<String>> = _liveChannelIds.asStateFlow()

    private val _playbackEvents = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    val playbackEvents: SharedFlow<PlaybackEvent> = _playbackEvents.asSharedFlow()

    fun health(channelId: String): ChannelHealth? = healthMap[channelId]

    fun isLive(channelId: String): Boolean {
        val ch = healthMap[channelId] ?: return false
        return ch.isLive
    }

    fun sourceHealthForChannel(channelId: String): Map<SourceType, SourceHealth> =
        perSourceHealth[channelId] ?: emptyMap()

    fun bestSource(channel: Channel): SourceType? =
        sourceResolutionEngine.bestSource(channel)

    fun bestVerifiedSource(channel: Channel): SourceType? =
        bestSource(channel)

    fun availableSources(channel: Channel): List<SourceType> =
        channel.sources.keys.sortedBy { providerRegistry.priority(it) }

    fun recordPlaybackSuccess(channelId: String, sourceType: SourceType, startupTimeMs: Long = -1) {
        val now = System.currentTimeMillis()
        val existing = perSourceHealth[channelId]?.get(sourceType)
        val successes = (existing?.consecutiveSuccesses ?: 0) + 1
        val avgStartup = if (startupTimeMs > 0 && (existing?.averageStartupTimeMs ?: 0) > 0) {
            ((existing?.averageStartupTimeMs ?: 0) * 3 + startupTimeMs) / 4
        } else startupTimeMs.coerceAtLeast(existing?.averageStartupTimeMs ?: 0)
        sourceHealth.recordSuccess(channelId, sourceType)
        updateSource(channelId, sourceType,
            consecutiveSuccesses = successes,
            consecutiveFailures = 0,
            averageStartupTimeMs = avgStartup,
            lastPlaybackSuccessMs = now)
        healthMap[channelId] = ChannelHealth(channelId, true, now, verifiedSource = sourceType)
        _liveChannelIds.value = _liveChannelIds.value + channelId
        _playbackEvents.tryEmit(PlaybackEvent.SourceValidated(channelId, sourceType, 0.9f))
    }

    fun recordPlaybackFailure(channelId: String, sourceType: SourceType, reason: String = "") {
        val now = System.currentTimeMillis()
        val existing = perSourceHealth[channelId]?.get(sourceType)
        val failures = (existing?.consecutiveFailures ?: 0) + 1
        sourceHealth.recordFailure(channelId, sourceType)
        updateSource(channelId, sourceType,
            consecutiveSuccesses = 0,
            consecutiveFailures = failures,
            lastPlaybackFailureMs = now)
        _playbackEvents.tryEmit(PlaybackEvent.SourceFailed(channelId, sourceType, reason))
    }

    private fun updateSource(
        channelId: String, type: SourceType,
        consecutiveFailures: Int = -1, consecutiveSuccesses: Int = -1,
        averageStartupTimeMs: Long = -1, lastPlaybackSuccessMs: Long = -1,
        lastPlaybackFailureMs: Long = -1,
    ) {
        val perChannel = perSourceHealth.getOrPut(channelId) { ConcurrentHashMap() }
        val prev = perChannel[type]
        perChannel[type] = SourceHealth(
            type = type,
            lastCheckedMs = System.currentTimeMillis(),
            consecutiveFailures = if (consecutiveFailures >= 0) consecutiveFailures else (prev?.consecutiveFailures ?: 0),
            consecutiveSuccesses = if (consecutiveSuccesses >= 0) consecutiveSuccesses else (prev?.consecutiveSuccesses ?: 0),
            averageStartupTimeMs = if (averageStartupTimeMs >= 0) averageStartupTimeMs else (prev?.averageStartupTimeMs ?: 0),
            lastPlaybackSuccessMs = if (lastPlaybackSuccessMs >= 0) lastPlaybackSuccessMs else (prev?.lastPlaybackSuccessMs ?: 0),
            lastPlaybackFailureMs = if (lastPlaybackFailureMs >= 0) lastPlaybackFailureMs else (prev?.lastPlaybackFailureMs ?: 0),
        )
    }
}
