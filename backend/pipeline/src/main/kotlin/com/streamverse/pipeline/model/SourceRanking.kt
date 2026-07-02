package com.streamverse.pipeline.model

data class SourceRanking(
    val channelId: String,
    val preferredSource: RankedSource,
    val fallbackSources: List<RankedSource>,
    val allSources: List<RankedSource>,
)

data class RankedSource(
    val sourceType: SourceType,
    val referenceId: String,
    val score: Double,
    val reliability: Double,
    val startupLatencyMs: Long = -1,
    val resolution: String? = null,
    val quality: Quality? = null,
    val isHealthy: Boolean = true,
    val rank: Int = 0,
)

data class RankingFactors(
    val reliabilityWeight: Double = 0.35,
    val startupLatencyWeight: Double = 0.20,
    val historicalStabilityWeight: Double = 0.15,
    val probeConfidenceWeight: Double = 0.10,
    val streamQualityWeight: Double = 0.10,
    val resolutionWeight: Double = 0.05,
    val geographicSuitabilityWeight: Double = 0.05,
)
