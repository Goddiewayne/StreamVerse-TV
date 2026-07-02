package com.streamverse.pipeline.ranker

import com.streamverse.pipeline.health.HealthTracker
import com.streamverse.pipeline.model.*
import com.streamverse.pipeline.telemetry.MetricsCollector
import com.streamverse.pipeline.telemetry.StructuredLogger

class SourceRanker(
    private val healthTracker: HealthTracker,
    private val logger: StructuredLogger,
    private val metrics: MetricsCollector,
) {
    private val sourceBasePriorities: Map<SourceType, Int> = mapOf(
        SourceType.BROADCASTER to 0,
        SourceType.FREE_CHANNEL to 1,
        SourceType.YOUTUBE_TV to 2,
        SourceType.SPORTS_EVENTS to 3,
        SourceType.WORLD_TV to 4,
        SourceType.GLOBAL_INDEX to 5,
        SourceType.RADIO to 6,
    )

    private val defaultFactors = RankingFactors()

    fun rankChannel(channel: CanonicalChannel, channelHealth: Map<SourceType, HealthRecord>): SourceRanking {
        val ranked = channel.sources.map { (sourceType, info) ->
            val health = channelHealth[sourceType]
            val score = computeSourceScore(sourceType, info, health)
            RankedSource(
                sourceType = sourceType,
                referenceId = info.referenceId,
                score = score,
                reliability = health?.reliabilityScore ?: 50.0,
                startupLatencyMs = health?.responseTimeMs ?: -1,
                resolution = channel.quality?.name,
                quality = channel.quality,
                isHealthy = health?.consecutiveFailures?.let { it < 5 } ?: true,
                rank = 0,
            )
        }.sortedByDescending { it.score }

        val rankedWithIndex = ranked.mapIndexed { index, source -> source.copy(rank = index + 1) }

        val preferred = rankedWithIndex.firstOrNull()
            ?: return SourceRanking(
                channelId = channel.id,
                preferredSource = RankedSource(SourceType.GLOBAL_INDEX, "", score = 0.0, reliability = 0.0),
                fallbackSources = emptyList(),
                allSources = emptyList(),
            )

        return SourceRanking(
            channelId = channel.id,
            preferredSource = preferred,
            fallbackSources = rankedWithIndex.drop(1),
            allSources = rankedWithIndex,
        )
    }

    fun rankAll(channels: List<CanonicalChannel>): List<SourceRanking> {
        val startMs = System.currentTimeMillis()
        logger.info("SourceRanker", "Ranking sources for ${channels.size} channels")

        val rankings = channels.map { channel ->
            val health = channels.flatMap { ch ->
                healthTracker.getAllChannelHealth()[ch.id]?.entries.orEmpty()
            }.groupBy({ it.key }, { it.value }).mapValues { (_, v) -> v.first() }
            rankChannel(channel, healthTracker.getAllChannelHealth()[channel.id].orEmpty())
        }

        val elapsed = System.currentTimeMillis() - startMs
        val withPreferred = rankings.count { it.allSources.isNotEmpty() }
        logger.info("SourceRanker",
            "Ranked ${rankings.size} channels ($withPreferred have preferred source) in ${elapsed}ms")

        metrics.gauge("ranking.channels", rankings.size.toDouble())
        metrics.gauge("ranking.with_preferred", withPreferred.toDouble())

        return rankings
    }

    private fun computeSourceScore(
        sourceType: SourceType,
        info: SourceInfo,
        health: HealthRecord?,
    ): Double {
        val basePriority = 100.0 - (sourceBasePriorities[sourceType] ?: 5) * 10.0
        val latencyScore = if (info.latencyMs > 0) maxOf(0.0, 100.0 - info.latencyMs / 100.0) else 50.0
        val healthScore = health?.reliabilityScore ?: 50.0
        val availabilityBonus = if (info.available) 20.0 else -100.0
        val consecutiveFailurePenalty = if (health != null && health.consecutiveFailures > 3)
            -(health.consecutiveFailures * 15.0) else 0.0
        val successBonus = if (health != null) minOf(20.0, health.consecutiveSuccesses * 2.0) else 0.0

        return basePriority * 0.25 +
            latencyScore * 0.15 +
            healthScore * 0.30 +
            availabilityBonus +
            consecutiveFailurePenalty +
            successBonus
    }
}
