package com.streamverse.core.util

import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.SourceHealth
import com.streamverse.core.data.SourceHealthPreferences
import com.streamverse.core.data.SourceHealthState
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

        val perSourceHealth = healthEngine.sourceHealthForChannel(channelId)

        // 1. Confirmed playable from health engine (AVAILABLE state)
        for (type in sources) {
            val sh = perSourceHealth[type] ?: continue
            if (sh.state == SourceHealthState.AVAILABLE) {
                return SourceSelection(type, SourceConfidence.CONFIRMED_PLAYABLE)
            }
        }

        // 2. Last good source remembered
        val lastGood = sourceHealth.lastGoodSource(channelId)
        if (lastGood != null && lastGood in sources) {
            val sh = perSourceHealth[lastGood]
            if (sh == null || sh.state != SourceHealthState.UNAVAILABLE) {
                return SourceSelection(lastGood, SourceConfidence.PREVIOUSLY_GOOD)
            }
        }

        // 3. User preferred source
        if (userPreferredSource != null && userPreferredSource in sources) {
            val sh = perSourceHealth[userPreferredSource]
            if (sh == null || sh.state != SourceHealthState.UNAVAILABLE) {
                return SourceSelection(userPreferredSource, SourceConfidence.USER_PREFERRED)
            }
        }

        // 4. Highest ranked healthy source
        val ranked = sourceResolutionEngine.bestSource(channel)
        if (ranked != null && ranked in sources) {
            val sh = perSourceHealth[ranked]
            if (sh == null || sh.state != SourceHealthState.UNAVAILABLE) {
                return SourceSelection(ranked, SourceConfidence.HIGHEST_RANKED)
            }
        }

        // 5. First available source as fallback
        for (type in sources.sortedBy { providerRegistry.priority(it) }) {
            return SourceSelection(type, SourceConfidence.FALLBACK)
        }

        return SourceSelection(SourceType.VERIFIED, SourceConfidence.FALLBACK)
    }

    /**
     * Select the best verified-playable source for a channel.
     * Uses [ChannelHealthEngine.bestVerifiedSource] which requires confidence >= 0.5
     * and not-stale. Falls back to [selectBestSource] ranked ordering if no verified source.
     */
    fun selectDefaultVerifiedSource(channel: Channel, excludeSources: Set<SourceType> = emptySet()): SourceSelection {
        val verified = healthEngine.bestVerifiedSource(channel)
        if (verified != null && verified !in excludeSources) {
            return SourceSelection(verified, SourceConfidence.CONFIRMED_PLAYABLE)
        }
        return selectBestSource(channel, excludeSources = excludeSources)
    }

    /**
     * Select the next best verified-playable source for failover.
     * ONLY returns sources with isPlaybackVerified() == true — never blind failover.
     * Returns null if no verified source remains (→ TV static).
     */
    fun selectForFailover(
        channel: Channel,
        currentSource: SourceType,
        triedSources: Set<SourceType>,
    ): SourceSelection? {
        val excluded = triedSources + currentSource
        val perSourceHealth = healthEngine.sourceHealthForChannel(channel.id)

        val verifiedCandidates = channel.sources.keys
            .filter { it !in excluded }
            .filter { type ->
                val sh = perSourceHealth[type] ?: return@filter false
                sh.isPlaybackVerified()
            }
            .sortedByDescending { type ->
                perSourceHealth[type]?.validationConfidence ?: 0f
            }

        for (type in verifiedCandidates) {
            return SourceSelection(type, SourceConfidence.CONFIRMED_PLAYABLE)
        }

        val fallbackCandidates = channel.sources.keys
            .filter { it !in excluded }
            .sortedByDescending { type ->
                val sh = perSourceHealth[type]
                when (sh?.state) {
                    SourceHealthState.AVAILABLE -> 3
                    null -> 2
                    SourceHealthState.VERIFYING -> 1
                    SourceHealthState.UNKNOWN -> 0
                    SourceHealthState.UNAVAILABLE -> -1
                }
            }

        for (type in fallbackCandidates) {
            val confidence = when {
                perSourceHealth[type]?.state == SourceHealthState.AVAILABLE -> SourceConfidence.CONFIRMED_PLAYABLE
                else -> SourceConfidence.FALLBACK
            }
            return SourceSelection(type, confidence)
        }
        return null
    }

    fun nextBestSource(
        channel: Channel,
        currentSource: SourceType,
        excludeSources: Set<SourceType> = emptySet(),
    ): SourceSelection? {
        val failed = excludeSources + currentSource
        val perSourceHealth = healthEngine.sourceHealthForChannel(channel.id)

        val ordered = channel.sources.keys
            .filter { it !in failed }
            .sortedByDescending { type ->
                val sh = perSourceHealth[type]
                when (sh?.state) {
                    SourceHealthState.AVAILABLE -> 3
                    null -> 2
                    SourceHealthState.VERIFYING -> 1
                    SourceHealthState.UNKNOWN -> 0
                    SourceHealthState.UNAVAILABLE -> -1
                }
            }

        for (type in ordered) {
            val confidence = when {
                perSourceHealth[type]?.state == SourceHealthState.AVAILABLE -> SourceConfidence.CONFIRMED_PLAYABLE
                else -> SourceConfidence.FALLBACK
            }
            return SourceSelection(type, confidence)
        }
        return null
    }

    /**
     * Recalculate the default active source based on current health data.
     * Does NOT override a working manual selection — returns null if the user is
     * watching a manually selected source that is currently playing.
     */
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

    /** Check whether a source is verified-playable for the given channel. */
    fun isVerifiedPlayable(channel: Channel, sourceType: SourceType): Boolean {
        val sh = healthEngine.sourceHealthForChannel(channel.id)[sourceType] ?: return false
        return sh.isPlaybackVerified()
    }
}
