package com.streamverse.pipeline.prober

import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.*
import com.streamverse.pipeline.telemetry.MetricsCollector
import com.streamverse.pipeline.telemetry.StructuredLogger
import java.util.UUID

class ProbeScheduler(
    private val config: PipelineConfig,
    private val logger: StructuredLogger,
    private val metrics: MetricsCollector,
) {

    data class SchedulingConfig(
        val healthyIntervalMs: Long,
        val unhealthyIntervalMs: Long = 300_000L,
        val newStreamIntervalMs: Long = 0L,
        val recoveredIntervalMs: Long = 60_000L,
        val popularIntervalMs: Long = 300_000L,
        val standardIntervalMs: Long = 3_600_000L,
    )

    private val scheduleConfig = SchedulingConfig(
        healthyIntervalMs = config.adaptiveProbeMaxIntervalMs,
    )

    fun generateJobs(
        channels: Map<String, List<RawChannel>>,
        healthRecords: Map<String, Map<SourceType, HealthRecord>>,
    ): List<ProbeJob> {
        val now = System.currentTimeMillis()
        val jobs = mutableListOf<ProbeJob>()

        for ((channelId, sources) in channels) {
            for (raw in sources) {
                if (raw.streamUrl == null) continue
                val key = "$channelId:${raw.source.name}"
                val record = healthRecords[channelId]?.get(raw.source)
                val shouldProbe = shouldProbeNow(record, now)

                if (shouldProbe) {
                    val priority = computePriority(record, raw.source)
                    jobs.add(ProbeJob(
                        channelId = channelId,
                        sourceType = raw.source,
                        streamUrl = raw.streamUrl,
                        probeId = UUID.randomUUID().toString(),
                        priority = priority,
                        scheduledAtMs = now,
                    ))
                }
            }
        }

        metrics.gauge("scheduler.jobs_generated", jobs.size.toDouble())
        logger.info("ProbeScheduler", "Generated ${jobs.size} probe jobs")
        return jobs
    }

    fun shouldProbeNow(record: HealthRecord?, now: Long): Boolean {
        if (record == null) return true

        val lastProbe = maxOf(record.lastSuccessfulProbeMs, record.lastFailedProbeMs)
        val timeSinceLastProbe = now - lastProbe

        val interval = when {
            consecutiveFailures(record) >= config.unhealthyThreshold -> scheduleConfig.unhealthyIntervalMs
            record.totalProbes == 0L -> scheduleConfig.newStreamIntervalMs
            record.consecutiveSuccesses >= config.healthyThreshold && record.totalSuccesses > 10 ->
                scheduleConfig.healthyIntervalMs
            record.consecutiveSuccesses in 1 until config.healthyThreshold ->
                scheduleConfig.recoveredIntervalMs
            else -> scheduleConfig.standardIntervalMs
        }

        return timeSinceLastProbe >= interval
    }

    fun computePriority(record: HealthRecord?, sourceType: SourceType): Int {
        if (record == null) return 10
        return when {
            consecutiveFailures(record) >= config.unhealthyThreshold -> 9
            record.totalProbes == 0L -> 8
            record.consecutiveFailures > 0 -> 7
            record.consecutiveSuccesses < config.healthyThreshold -> 6
            else -> 1 + minOf(record.consecutiveSuccesses / 10, 5)
        }
    }

    private fun consecutiveFailures(record: HealthRecord): Int {
        return if (record.lastFailedProbeMs > record.lastSuccessfulProbeMs)
            record.consecutiveFailures else 0
    }

    public override fun toString(): String = "ProbeScheduler(healthyInterval=${scheduleConfig.healthyIntervalMs}ms)"
}
