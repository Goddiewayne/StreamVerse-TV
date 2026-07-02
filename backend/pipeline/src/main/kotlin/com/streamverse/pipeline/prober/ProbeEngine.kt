package com.streamverse.pipeline.prober

import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.ProbeJob
import com.streamverse.pipeline.model.ProbeTelemetry
import com.streamverse.pipeline.model.SourceType
import com.streamverse.pipeline.telemetry.MetricsCollector
import com.streamverse.pipeline.telemetry.StructuredLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProbeEngine(
    private val config: PipelineConfig,
    private val logger: StructuredLogger,
    private val metrics: MetricsCollector,
) {
    private val probeDispatcher = Dispatchers.IO.limitedParallelism(config.maxConcurrentProbes)
    private val jobQueue = Channel<ProbeJob>(config.probeQueueCapacity)
    private val _completedProbes = MutableStateFlow(0)
    private val _failedProbes = MutableStateFlow(0)
    private val _activeWorkers = MutableStateFlow(0)

    private val hlsProbe = HlsProbe(config, logger)
    private val httpProbe = HttpProbe(config, logger)

    private var probeScope: CoroutineScope? = null
    private var isRunning = false

    val completedProbes: StateFlow<Int> = _completedProbes
    val failedProbes: StateFlow<Int> = _failedProbes
    val activeWorkers: StateFlow<Int> = _activeWorkers

    data class ProbeStats(
        val totalSubmitted: Int,
        val completed: Int,
        val failed: Int,
        val activeWorkers: Int,
        val averageLatencyMs: Double,
        val queueDepth: Int,
    )

    private val totalSubmitted = AtomicInteger(0)
    private val totalLatency = AtomicLong(0)
    private val latencySamples = AtomicInteger(0)

    private val pendingJobs = java.util.concurrent.ConcurrentLinkedQueue<ProbeJob>()

    fun submitJobs(jobs: List<ProbeJob>) {
        for (job in jobs) {
            pendingJobs.offer(job)
        }
        totalSubmitted.addAndGet(jobs.size)
        metrics.gauge("probe.queue_depth", pendingJobs.size.toDouble())
        logger.info("ProbeEngine", "Submitted ${jobs.size} probe jobs (total: ${totalSubmitted.get()})")
    }

    fun start(): List<ProbeTelemetry> {
        if (isRunning) {
            logger.warn("ProbeEngine", "Already running")
            return emptyList()
        }
        isRunning = true
        probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val results = java.util.concurrent.ConcurrentLinkedQueue<ProbeTelemetry>()
        val workerCount = config.probeWorkerPoolSize
        val barrier = java.util.concurrent.CountDownLatch(workerCount)

        logger.info("ProbeEngine", "Starting $workerCount probe workers (max ${
            config.maxConcurrentProbes
        } concurrent probes)")

        for (workerId in 1..workerCount) {
            probeScope?.launch {
                _activeWorkers.value = _activeWorkers.value + 1
                try {
                    while (isRunning) {
                        val job = pendingJobs.poll() ?: break
                        val result = executeProbe(job, "worker-$workerId")
                        if (result != null) {
                            results.add(result)
                            if (result.success) _completedProbes.value = _completedProbes.value + 1
                            else _failedProbes.value = _failedProbes.value + 1
                        }
                    }
                } finally {
                    _activeWorkers.value = _activeWorkers.value - 1
                    barrier.countDown()
                }
            }
        }

        barrier.await()

        val finalResults = results.toList()
        logger.info("ProbeEngine", "Probing complete: ${finalResults.size} probes " +
            "(${_completedProbes.value} OK, ${_failedProbes.value} failed)")

        metrics.gauge("probe.completed", _completedProbes.value.toDouble())
        metrics.gauge("probe.failed", _failedProbes.value.toDouble())
        metrics.gauge("probe.total", finalResults.size.toDouble())

        return finalResults
    }

    fun stop() {
        isRunning = false
        probeScope?.cancel()
        probeScope = null
    }

    private suspend fun executeProbe(job: ProbeJob, workerId: String): ProbeTelemetry? {
        return withContext(probeDispatcher) {
            val url = job.streamUrl
            val probeResult = when {
                url.contains(".m3u8") || url.contains(".m3u") -> hlsProbe.probe(url)
                else -> httpProbe.probe(url)
            }

            val elapsed = System.currentTimeMillis() - job.scheduledAtMs
            totalLatency.addAndGet(elapsed)
            latencySamples.incrementAndGet()
            metrics.gauge("probe.latency_ms", elapsed.toDouble())
            metrics.increment(if (probeResult.success) "probe.success" else "probe.failure")

            ProbeTelemetry(
                probeId = job.probeId,
                channelId = job.channelId,
                sourceType = job.sourceType,
                streamUrl = url,
                startedAtMs = job.scheduledAtMs,
                completedAtMs = System.currentTimeMillis(),
                durationMs = elapsed,
                success = probeResult.success,
                manifestAvailable = probeResult.manifestAvailable,
                playlistValid = probeResult.playlistValid,
                segmentAccessible = probeResult.segmentAccessible,
                playbackStartupMs = probeResult.playbackStartupMs,
                decoderCompatible = probeResult.decoderCompatible,
                resolution = probeResult.resolution,
                width = probeResult.width,
                height = probeResult.height,
                codec = probeResult.codec,
                bitrate = probeResult.bitrate,
                encrypted = probeResult.encrypted,
                requiresAuth = probeResult.requiresAuth,
                httpStatus = probeResult.httpStatus,
                redirectChain = probeResult.redirectChain,
                failureReason = probeResult.failureReason,
                mimeType = probeResult.mimeType,
                workerId = workerId,
            )
        }
    }

    fun getStats(): ProbeStats {
        val avgLatency = if (latencySamples.get() > 0)
            totalLatency.get().toDouble() / latencySamples.get() else 0.0
        return ProbeStats(
            totalSubmitted = totalSubmitted.get(),
            completed = _completedProbes.value,
            failed = _failedProbes.value,
            activeWorkers = _activeWorkers.value,
            averageLatencyMs = avgLatency,
            queueDepth = pendingJobs.size,
        )
    }

    data class ProbeOutcome(
        val success: Boolean,
        val manifestAvailable: Boolean = false,
        val playlistValid: Boolean = false,
        val segmentAccessible: Boolean = false,
        val playbackStartupMs: Long = -1,
        val decoderCompatible: Boolean = false,
        val resolution: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val codec: String? = null,
        val bitrate: Long = -1,
        val encrypted: Boolean = false,
        val requiresAuth: Boolean = false,
        val httpStatus: Int = -1,
        val redirectChain: List<String> = emptyList(),
        val failureReason: String? = null,
        val mimeType: String? = null,
    )
}
