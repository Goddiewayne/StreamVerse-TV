package com.streamverse.core.util

import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.SourceHealthPreferences
import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import javax.inject.Inject
import javax.inject.Singleton

data class SourceSelection(
    val type: SourceType,
    val confidence: SourceConfidence,
)

enum class SourceConfidence {
    CONFIRMED_PLAYABLE,
    PREVIOUSLY_GOOD,
    USER_PREFERRED,
    HIGHEST_RANKED,
    FALLBACK,
}

@Singleton
class SourceSelector @Inject constructor(
    private val healthEngine: ChannelHealthEngine,
    private val sourceHealth: SourceHealthPreferences,
    private val sourceResolutionEngine: SourceResolutionEngine,
    private val providerRegistry: ProviderRegistry,
) {

    fun selectBestSource(
        channel: Channel,
        userPreferredSource: SourceType? = null,
        excludeSources: Set<SourceType> = emptySet(),
    ): SourceSelection {
        val channelId = channel.id
        val sources = channel.sources.keys
            .filter { it !in excludeSources }

        // 1. Last good source remembered from user playback
        val lastGood = sourceHealth.lastGoodSource(channelId)
        if (lastGood != null && lastGood in sources) {
            return SourceSelection(lastGood, SourceConfidence.PREVIOUSLY_GOOD)
        }

        // 2. User preferred source
        if (userPreferredSource != null && userPreferredSource in sources) {
            return SourceSelection(userPreferredSource, SourceConfidence.USER_PREFERRED)
        }

        // 3. Highest ranked source from pipeline
        val ranked = sourceResolutionEngine.bestSource(channel)
        if (ranked != null && ranked in sources) {
            return SourceSelection(ranked, SourceConfidence.HIGHEST_RANKED)
        }

        // 4. First available source as fallback
        for (type in sources.sortedBy { providerRegistry.priority(it) }) {
            return SourceSelection(type, SourceConfidence.FALLBACK)
        }

        return SourceSelection(SourceType.BROADCASTER, SourceConfidence.FALLBACK)
    }

    fun selectDefaultVerifiedSource(channel: Channel, excludeSources: Set<SourceType> = emptySet()): SourceSelection {
        return selectBestSource(channel, excludeSources = excludeSources)
    }

    fun selectForFailover(
        channel: Channel,
        currentSource: SourceType,
        triedSources: Set<SourceType>,
        hasCachedStream: (SourceType) -> Boolean = { false },
    ): SourceSelection? {
        val excluded = triedSources + currentSource
        val candidates = channel.sources.keys
            .filter { it !in excluded }
            .sortedBy { providerRegistry.priority(it) }

        // Cached streams first (instant failover)
        for (type in candidates) {
            if (hasCachedStream(type)) {
                return SourceSelection(type, SourceConfidence.CONFIRMED_PLAYABLE)
            }
        }

        // Any remaining source
        for (type in candidates) {
            return SourceSelection(type, SourceConfidence.FALLBACK)
        }
        return null
    }

    fun nextBestSource(
        channel: Channel,
        currentSource: SourceType,
        excludeSources: Set<SourceType> = emptySet(),
    ): SourceSelection? {
        val failed = excludeSources + currentSource
        val ordered = channel.sources.keys
            .filter { it !in failed }
            .sortedBy { providerRegistry.priority(it) }

        for (type in ordered) {
            return SourceSelection(type, SourceConfidence.FALLBACK)
        }
        return null
    }

    fun recalculateDefault(
        channel: Channel,
        currentPlaybackSource: SourceType?,
        isManualSelection: Boolean,
        isPlaybackActive: Boolean,
        excludeSources: Set<SourceType> = emptySet(),
    ): SourceSelection? {
        if (isManualSelection && isPlaybackActive && currentPlaybackSource != null) {
            return null
        }
        return selectDefaultVerifiedSource(channel, excludeSources)
    }

    fun isVerifiedPlayable(channel: Channel, sourceType: SourceType): Boolean {
        return sourceType in channel.sources
    }
}
