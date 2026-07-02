package com.streamverse.pipeline.ingester

import com.streamverse.pipeline.model.RawChannel
import com.streamverse.pipeline.model.SourceType
import com.streamverse.pipeline.telemetry.StructuredLogger
import com.streamverse.pipeline.config.PipelineConfig
import okhttp3.OkHttpClient

class IngesterStage(
    private val config: PipelineConfig,
    private val client: OkHttpClient,
    private val logger: StructuredLogger,
) {
    private val ingestors: List<SourceIngester> = listOf(
        HostedIndexIngester(client, logger),
        StmifyIngester(config, client, logger),
    )

    data class IngestionResult(
        val channels: List<RawChannel>,
        val sourceStats: Map<SourceType, Int>,
        val durationMs: Long,
    )

    fun execute(): IngestionResult {
        val start = System.currentTimeMillis()
        logger.info("IngesterStage", "Starting channel ingestion")
        val allChannels = mutableListOf<RawChannel>()
        val stats = mutableMapOf<SourceType, Int>()

        for (ingestor in ingestors) {
            try {
                val channels = ingestor.ingest()
                allChannels.addAll(channels)
                for (ch in channels) {
                    stats[ch.source] = (stats[ch.source] ?: 0) + 1
                }
                logger.info("IngesterStage", "${ingestor.name()}: ${channels.size} channels")
            } catch (e: Exception) {
                logger.error("IngesterStage", "${ingestor.name()} failed: ${e.message}", e)
            }
        }

        val elapsed = System.currentTimeMillis() - start
        logger.info("IngesterStage", "Ingestion complete: ${allChannels.size} channels in ${elapsed}ms")

        for ((source, count) in stats.entries.sortedBy { it.key.name }) {
            logger.info("IngesterStage", "  ${source.name}: $count")
        }

        return IngestionResult(allChannels, stats, elapsed)
    }
}
