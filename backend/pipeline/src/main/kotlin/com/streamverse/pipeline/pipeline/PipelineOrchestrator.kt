package com.streamverse.pipeline.pipeline

import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.*
import com.streamverse.pipeline.ingester.IngesterStage
import com.streamverse.pipeline.prober.ProbeEngine
import com.streamverse.pipeline.prober.ProbeScheduler
import com.streamverse.pipeline.health.HealthTracker
import com.streamverse.pipeline.canonicalizer.CanonicalEngine
import com.streamverse.pipeline.ranker.SourceRanker
import com.streamverse.pipeline.generator.CatalogueBuilder
import com.streamverse.pipeline.generator.DiffGenerator
import com.streamverse.pipeline.publisher.GitHubPublisher
import com.streamverse.pipeline.publisher.IntegrityChecker
import com.streamverse.pipeline.publisher.ReleaseManager
import com.streamverse.pipeline.persistence.StateStore
import com.streamverse.pipeline.telemetry.MetricsCollector
import com.streamverse.pipeline.telemetry.MetricsExporter
import com.streamverse.pipeline.telemetry.StructuredLogger
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.streamverse.pipeline.persistence.HealthStore

class PipelineOrchestrator(
    private val config: PipelineConfig,
    private val logger: StructuredLogger,
    private val metrics: MetricsCollector,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val stateStore = StateStore(config, logger)
    private val healthStore = HealthStore(config, logger)
    private val healthTracker = HealthTracker(config, healthStore, logger, metrics)
    private val ingester = IngesterStage(config, client, logger)
    private val probeScheduler = ProbeScheduler(config, logger, metrics)
    private val probeEngine = ProbeEngine(config, logger, metrics)
    private val canonicalEngine = CanonicalEngine(logger, metrics)
    private val sourceRanker = SourceRanker(healthTracker, logger, metrics)
    private val catalogueBuilder = CatalogueBuilder(config, logger, metrics)
    private val diffGenerator = DiffGenerator(config, logger, metrics)
    private val gitHubPublisher = GitHubPublisher(config, logger, metrics)
    private val integrityChecker = IntegrityChecker(logger)
    private val releaseManager = ReleaseManager(config, logger)
    private val metricsExporter = MetricsExporter(metrics, logger)

    private var currentRankings: List<SourceRanking> = emptyList()
    private var currentHealthSnapshots: List<SourceHealthSnapshot> = emptyList()
    private var currentArtifacts: List<CatalogueArtifact> = emptyList()
    private var currentVersionManifest: VersionManifest? = null
    private var cachedIngestResult: IngesterStage.IngestionResult? = null

    data class PipelineResult(
        val stages: List<String>,
        val results: Map<String, StageResult>,
        val state: StateStore.PipelineState,
        val totalDurationMs: Long,
        val success: Boolean,
    )

    fun run(requestedStages: List<String> = emptyList()): PipelineResult {
        val startMs = System.currentTimeMillis()
        val previousState = stateStore.loadState()
        logger.info("Orchestrator", "Pipeline v${config.catalogueVersion} starting (run #${previousState.runCount + 1})")
        logger.info("Orchestrator", "Previous state: version=${previousState.lastCatalogueVersion} channels=${previousState.lastChannelCount}")

        val results = linkedMapOf<String, StageResult>()
        val allStages = listOf(
            ingestStage, probeStage, canonicalizeStage, rankStage, generateStage, publishStage
        )
        val toRun = if (requestedStages.isEmpty()) allStages
            else allStages.filter { it.name in requestedStages }

        logger.info("Orchestrator", "Running stages: ${toRun.joinToString(", ") { it.name }}")

        var pipelineFailed = false
        var channelCount = 0

        for (stage in toRun) {
            if (pipelineFailed && stage.name != "publish") {
                results[stage.name] = StageResult.Skipped("Previous stage failed")
                continue
            }
            val stageResult = stage.execute()
            results[stage.name] = stageResult

            when (stageResult) {
                is StageResult.Success -> {
                    metrics.increment("stage.${stage.name}.success")
                    when (stage.name) {
                        "ingest" -> channelCount = (stageResult as StageResult.Success).message
                            .substringBefore(" channels").toIntOrNull() ?: 0
                        "generate" -> {
                            // Extract artifacts from generate result
                        }
                    }
                }
                is StageResult.Failure -> {
                    metrics.increment("stage.${stage.name}.failure")
                    pipelineFailed = true
                    logger.error("Orchestrator", "Stage '${stage.name}' failed: ${stageResult.message}")
                }
                is StageResult.Skipped -> {
                    logger.info("Orchestrator", "Stage '${stage.name}' skipped: ${stageResult.reason}")
                }
            }
        }

        val newState = StateStore.PipelineState(
            lastCatalogueVersion = config.catalogueVersion,
            lastSuccessfulRunMs = maxOf(System.currentTimeMillis(), previousState.lastSuccessfulRunMs),
            lastChannelCount = maxOf(channelCount, previousState.lastChannelCount),
            runCount = previousState.runCount + 1,
            stageTimings = results.mapValues { (_, r) ->
                when (r) {
                    is StageResult.Success -> r.durationMs
                    is StageResult.Failure -> r.durationMs
                    is StageResult.Skipped -> 0L
                }
            },
        )

        stateStore.saveState(newState)
        metricsExporter.exportToFile()
        metricsExporter.exportToLog()

        val totalElapsed = System.currentTimeMillis() - startMs
        logger.info("Orchestrator",
            "Pipeline ${if (pipelineFailed) "FAILED" else "SUCCESS"} in ${totalElapsed}ms")
        logger.info("Orchestrator",
            "  ${results.size} stages, ${if (pipelineFailed) "FAILURE" else "SUCCESS"}: $channelCount channels")

        return PipelineResult(
            stages = toRun.map { it.name },
            results = results,
            state = newState,
            totalDurationMs = totalElapsed,
            success = !pipelineFailed,
        )
    }

    private val ingestStage = object : Stage {
        override val name = "ingest"
        override fun execute(): StageResult {
            val start = System.currentTimeMillis()
            val result = ingester.execute()
            cachedIngestResult = result
            return StageResult.Success(
                "${result.channels.size} channels from ${result.sourceStats.size} sources in ${result.durationMs}ms",
                System.currentTimeMillis() - start,
            )
        }
    }

    private val probeStage = object : Stage {
        override val name = "probe"
        override fun execute(): StageResult {
            if (config.skipProbe) return StageResult.Skipped("SKIP_PROBE=true")
            val start = System.currentTimeMillis()
            healthTracker.load()
            val channels = stateStore.loadCatalogue()
            val rawByChannel = channels.associate { it.id to it.sources.map { (st, info) ->
                RawChannel(it.id, it.displayName, info.streamUrl, it.logoUrl, it.category, it.country, it.language, it.quality?.name, it.tvgId, st, info.headers, info.drmKeyId, info.drmKey)
            } }
            val healthRecords = healthTracker.getAllChannelHealth()
            val jobs = probeScheduler.generateJobs(rawByChannel, healthRecords)
            probeEngine.submitJobs(jobs)
            val results = probeEngine.start()
            for (telemetry in results) healthTracker.recordProbe(telemetry)
            healthTracker.save()
            val stats = probeEngine.getStats()
            return StageResult.Success(
                "Probed ${stats.completed} sources (${stats.failed} failed, avg ${stats.averageLatencyMs.toLong()}ms) in ${System.currentTimeMillis() - start}ms",
                System.currentTimeMillis() - start,
            )
        }
    }

    private val canonicalizeStage = object : Stage {
        override val name = "canonicalize"
        override fun execute(): StageResult {
            val start = System.currentTimeMillis()
            val rawChannels = mutableListOf<RawChannel>()
            val result = cachedIngestResult ?: ingester.execute()
            rawChannels.addAll(result.channels)
            val canonical = canonicalEngine.process(rawChannels)
            stateStore.saveCatalogue(canonical)
            val multiSource = canonical.count { it.sources.size > 1 }
            return StageResult.Success(
                "${canonical.size} canonical channels (${multiSource} multi-source, ${rawChannels.size} inputs) in ${System.currentTimeMillis() - start}ms",
                System.currentTimeMillis() - start,
            )
        }
    }

    private val rankStage = object : Stage {
        override val name = "rank"
        override fun execute(): StageResult {
            val start = System.currentTimeMillis()
            val channels = stateStore.loadCatalogue()
            if (channels.isEmpty()) return StageResult.Failure("No canonical channels to rank", 0)
            healthTracker.load()
            currentRankings = sourceRanker.rankAll(channels)
            currentHealthSnapshots = healthTracker.getSourceHealthSnapshots()
            val withPreferred = currentRankings.count { it.preferredSource.score > 0 }
            return StageResult.Success(
                "Ranked ${currentRankings.size} channels ($withPreferred with preferred source) in ${System.currentTimeMillis() - start}ms",
                System.currentTimeMillis() - start,
            )
        }
    }

    private val generateStage = object : Stage {
        override val name = "generate"
        override fun execute(): StageResult {
            val start = System.currentTimeMillis()
            val channels = stateStore.loadCatalogue()
            if (channels.isEmpty()) return StageResult.Failure("No channels to generate catalogue from", 0)
            val previousState = stateStore.loadState()
            val result = catalogueBuilder.build(channels, currentRankings, currentHealthSnapshots, previousState.lastCatalogueVersion)
            currentArtifacts = result.artifacts
            currentVersionManifest = result.version

            if (config.generateIncrementalDiffs && previousState.lastCatalogueVersion >= 0) {
                diffGenerator.generate(channels, previousState.lastCatalogueVersion, config.catalogueVersion)
            }

            val integrityResult = integrityChecker.verify(java.io.File(config.outputDir), currentArtifacts)
            return StageResult.Success(
                "Generated ${currentArtifacts.size} artifacts for ${channels.size} channels " +
                "(integrity: ${if (integrityResult.allValid) "OK" else "${integrityResult.mismatched.size} failures"}) " +
                "in ${System.currentTimeMillis() - start}ms",
                System.currentTimeMillis() - start,
            )
        }
    }

    private val publishStage = object : Stage {
        override val name = "publish"
        override fun execute(): StageResult {
            val start = System.currentTimeMillis()
            if (currentVersionManifest == null) return StageResult.Failure("No version manifest to publish", 0)
            val result = gitHubPublisher.publish(currentArtifacts, currentVersionManifest!!)
            if (result.published || config.dryRun) {
                return StageResult.Success(
                    "Published ${result.filesChanged} files (commit: ${result.commitHash ?: "dry-run"})",
                    System.currentTimeMillis() - start,
                )
            } else {
                return StageResult.Failure(
                    "Publishing incomplete (filesChanged=${result.filesChanged})",
                    System.currentTimeMillis() - start,
                )
            }
        }
    }
}
