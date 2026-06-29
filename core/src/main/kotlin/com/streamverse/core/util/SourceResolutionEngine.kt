package com.streamverse.core.util

import com.streamverse.core.data.SourceHealthPreferences
import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceResolutionEngine @Inject constructor(
    private val sourceHealth: SourceHealthPreferences,
    private val providerRegistry: ProviderRegistry,
) {
    data class ResolvedSource(
        val type: SourceType,
        val info: SourceInfo,
        val rank: Float,
    )

    fun rankSources(channel: Channel): List<SourceType> {
        val enabled = channel.sources
        if (enabled.isEmpty()) return emptyList()

        val lastGood = sourceHealth.lastGoodSource(channel.id)

        return enabled.entries
            .map { (type, info) -> type to score(type, info, lastGood) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun bestSource(channel: Channel): SourceType? =
        rankSources(channel).firstOrNull()

    fun nextSource(channel: Channel, after: SourceType, tried: Set<SourceType>): SourceType? {
        val ranked = rankSources(channel)
        val afterIdx = ranked.indexOf(after)
        for (i in (afterIdx + 1) until ranked.size) {
            if (ranked[i] !in tried) return ranked[i]
        }
        for (i in 0 until afterIdx) {
            if (ranked[i] !in tried) return ranked[i]
        }
        return null
    }

    private fun score(type: SourceType, info: SourceInfo, lastGood: SourceType?): Float {
        var s = 0f

        if (type == lastGood) s += LAST_GOOD_BONUS

        s += providerRegistry.priorityScore(type)

        if (!info.available) s -= UNAVAILABLE_PENALTY

        if (info.latencyMs in 1..5000) {
            s += (5000f - info.latencyMs) / 5000f * LATENCY_WEIGHT
        }

        s += info.reliabilityScore * RELIABILITY_WEIGHT

        val canonical = SourceType.canonicalOf(info.type)
        if (canonical == SourceType.VERIFIED || canonical == SourceType.BROADCASTER) {
            s += CURATED_BONUS
        }

        return s
    }

    companion object {
        private const val LAST_GOOD_BONUS = 50f
        private const val UNAVAILABLE_PENALTY = 200f
        private const val LATENCY_WEIGHT = 15f
        private const val RELIABILITY_WEIGHT = 20f
        private const val CURATED_BONUS = 10f
    }
}
