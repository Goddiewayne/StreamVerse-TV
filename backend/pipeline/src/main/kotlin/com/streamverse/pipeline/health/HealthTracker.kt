package com.streamverse.pipeline.health

import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.*
import com.streamverse.pipeline.persistence.HealthStore
import com.streamverse.pipeline.telemetry.MetricsCollector
import com.streamverse.pipeline.telemetry.StructuredLogger

class HealthTracker(
    private val config: PipelineConfig,
    private val healthStore: HealthStore,
    private val logger: StructuredLogger,
    private val metrics: MetricsCollector,
) {
    private val records = mutableMapOf<String, MutableMap<SourceType, HealthRecord>>()

    fun load() {
        val stored = healthStore.loadAll()
        for ((key, record) in stored) {
            val parts = key.split(":", limit = 2)
            if (parts.size != 2) continue
            val recordsForChannel = records.getOrPut(parts[0]) { mutableMapOf() }
            val st = try { SourceType.valueOf(parts[1]) } catch (_: Exception) { continue }
            recordsForChannel[st] = record
        }
        logger.info("HealthTracker", "Loaded ${records.size} channel health records")
    }

    fun save() {
        val flat = mutableMapOf<String, HealthRecord>()
        for ((channelId, sourceRecords) in records) {
            for ((sourceType, record) in sourceRecords) {
                flat["$channelId:$sourceType"] = record
            }
        }
        healthStore.saveAll(flat)
        logger.info("HealthTracker", "Saved ${flat.size} health records")
    }

    fun recordProbe(telemetry: ProbeTelemetry) {
        val key = "${telemetry.channelId}:${telemetry.sourceType.name}"
        val channelRecords = records.getOrPut(telemetry.channelId) { mutableMapOf() }
        val current = channelRecords[telemetry.sourceType] ?: HealthRecord(
            channelId = telemetry.channelId,
            sourceType = telemetry.sourceType,
            streamUrl = telemetry.streamUrl,
        )

        val updated = if (telemetry.success) {
            recordSuccess(current, telemetry)
        } else {
            recordFailure(current, telemetry)
        }
        channelRecords[telemetry.sourceType] = updated

        metrics.gauge("health.${telemetry.sourceType.name}.score", updated.reliabilityScore)
        metrics.increment(if (telemetry.success) "health.probe.success" else "health.probe.failure")
    }

    fun getHealth(channelId: String, sourceType: SourceType): HealthRecord? {
        return records[channelId]?.get(sourceType)
    }

    fun getAllChannelHealth(): Map<String, Map<SourceType, HealthRecord>> = records.toMap()

    fun getSourceHealthSnapshots(): List<SourceHealthSnapshot> {
        val snapshots = mutableListOf<SourceHealthSnapshot>()
        for ((channelId, sourceRecords) in records) {
            for ((sourceType, record) in sourceRecords) {
                snapshots.add(SourceHealthSnapshot(
                    channelId = channelId,
                    sourceType = sourceType,
                    score = record.reliabilityScore,
                    isHealthy = record.consecutiveFailures < config.unhealthyThreshold,
                    updatedAtMs = maxOf(record.lastSuccessfulProbeMs, record.lastFailedProbeMs),
                ))
            }
        }
        return snapshots
    }

    fun summarize(): HealthSummary {
        val snapshots = getSourceHealthSnapshots()
        val healthy = snapshots.count { it.isHealthy }
        val unhealthy = snapshots.count { !it.isHealthy }
        val avgScore = snapshots.map { it.score }.average()
        return HealthSummary(
            totalSources = snapshots.size,
            healthySources = healthy,
            unhealthySources = unhealthy,
            averageScore = if (avgScore.isNaN()) 0.0 else avgScore,
            channelsTracked = records.size,
        )
    }

    data class HealthSummary(
        val totalSources: Int,
        val healthySources: Int,
        val unhealthySources: Int,
        val averageScore: Double,
        val channelsTracked: Int,
    )

    private fun recordSuccess(current: HealthRecord, probe: ProbeTelemetry): HealthRecord {
        val newLatencies = current.sampleLatenciesMs.takeLast(99) + probe.durationMs
        val avgLatency = newLatencies.average()
        val totalProbes = current.totalProbes + 1
        val totalSuccesses = current.totalSuccesses + 1
        val availabilityPct = (totalSuccesses.toDouble() / totalProbes) * 100.0
        val consecutiveSuccesses = if (current.lastFailedProbeMs > current.lastSuccessfulProbeMs) 1
            else current.consecutiveSuccesses + 1
        val consecutiveFailures = if (current.lastFailedProbeMs > current.lastSuccessfulProbeMs) 0
            else current.consecutiveFailures
        val confidence = minOf(1.0, totalSuccesses / 100.0)
        val reliability = computeReliability(consecutiveSuccesses, consecutiveFailures, availabilityPct, avgLatency)

        return current.copy(
            lastSuccessfulProbeMs = probe.completedAtMs,
            consecutiveSuccesses = consecutiveSuccesses,
            consecutiveFailures = consecutiveFailures,
            averageStartupLatencyMs = avgLatency,
            availabilityPct = availabilityPct,
            reliabilityScore = reliability,
            responseTimeMs = probe.durationMs,
            validationConfidence = confidence,
            lastVerifiedMs = probe.completedAtMs,
            totalProbes = totalProbes,
            totalSuccesses = totalSuccesses,
            sampleLatenciesMs = newLatencies,
            probeHistory = current.probeHistory.takeLast(49) + ProbeSummary(
                timestampMs = probe.completedAtMs,
                success = true,
                durationMs = probe.durationMs,
                httpStatus = probe.httpStatus,
                resolution = probe.resolution,
            ),
        )
    }

    private fun recordFailure(current: HealthRecord, probe: ProbeTelemetry): HealthRecord {
        val totalProbes = current.totalProbes + 1
        val totalFailures = current.totalFailures + 1
        val availabilityPct = if (totalProbes > 0)
            ((current.totalSuccesses.toDouble()) / totalProbes) * 100.0 else 0.0
        val consecutiveFailures = current.consecutiveFailures + 1
        val consecutiveSuccesses = 0
        val confidence = maxOf(0.1, minOf(1.0, current.totalSuccesses / 100.0))
        val reliability = computeReliability(consecutiveSuccesses, consecutiveFailures, availabilityPct, current.averageStartupLatencyMs)

        return current.copy(
            lastFailedProbeMs = probe.completedAtMs,
            consecutiveSuccesses = consecutiveSuccesses,
            consecutiveFailures = consecutiveFailures,
            availabilityPct = availabilityPct,
            reliabilityScore = reliability,
            validationConfidence = confidence,
            lastVerifiedMs = probe.completedAtMs,
            totalProbes = totalProbes,
            totalFailures = totalFailures,
            probeHistory = current.probeHistory.takeLast(49) + ProbeSummary(
                timestampMs = probe.completedAtMs,
                success = false,
                durationMs = probe.durationMs,
                httpStatus = probe.httpStatus,
                failureReason = probe.failureReason,
            ),
        )
    }

    private fun computeReliability(
        consecutiveSuccesses: Int,
        consecutiveFailures: Int,
        availabilityPct: Double,
        avgLatencyMs: Double,
    ): Double {
        val availabilityScore = availabilityPct / 100.0
        val successStreakScore = minOf(1.0, consecutiveSuccesses / 20.0)
        val failurePenalty = minOf(1.0, consecutiveFailures / 10.0)
        val latencyScore = when {
            avgLatencyMs <= 0 || avgLatencyMs > 30000 -> 0.0
            avgLatencyMs < 1000 -> 1.0
            avgLatencyMs < 3000 -> 0.8
            avgLatencyMs < 5000 -> 0.6
            avgLatencyMs < 10000 -> 0.4
            else -> 0.2
        }
        return (availabilityScore * 0.40 + successStreakScore * 0.25 +
            latencyScore * 0.20 + (1.0 - failurePenalty) * 0.15) * 100.0
    }
}
