package com.streamverse.pipeline.model

data class HealthRecord(
    val channelId: String,
    val sourceType: SourceType,
    val streamUrl: String,
    val lastSuccessfulProbeMs: Long = 0,
    val lastFailedProbeMs: Long = 0,
    val consecutiveSuccesses: Int = 0,
    val consecutiveFailures: Int = 0,
    val averageStartupLatencyMs: Double = 0.0,
    val availabilityPct: Double = 100.0,
    val reliabilityScore: Double = 100.0,
    val responseTimeMs: Long = 0,
    val validationConfidence: Double = 1.0,
    val lastVerifiedMs: Long = 0,
    val totalProbes: Long = 0,
    val totalSuccesses: Long = 0,
    val totalFailures: Long = 0,
    val sampleLatenciesMs: List<Long> = emptyList(),
    val probeHistory: List<ProbeSummary> = emptyList(),
)

data class ProbeSummary(
    val timestampMs: Long,
    val success: Boolean,
    val durationMs: Long,
    val httpStatus: Int,
    val failureReason: String? = null,
    val resolution: String? = null,
)

data class SourceHealthSnapshot(
    val channelId: String,
    val sourceType: SourceType,
    val score: Double,
    val isHealthy: Boolean,
    val updatedAtMs: Long,
)
