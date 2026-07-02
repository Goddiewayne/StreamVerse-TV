package com.streamverse.pipeline.generator

import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.*
import com.streamverse.pipeline.telemetry.MetricsCollector
import com.streamverse.pipeline.telemetry.StructuredLogger
import com.google.gson.Gson
import java.io.File

class DiffGenerator(
    private val config: PipelineConfig,
    private val logger: StructuredLogger,
    private val metrics: MetricsCollector,
) {
    private val gson = Gson()
    private val diffsDir = File(config.outputDir, "diffs")

    data class DiffResult(
        val diff: IncrementalDiff?,
        val path: String?,
        val size: Long,
    )

    fun loadPreviousCatalogue(version: Int): List<CanonicalChannel> {
        val file = File(config.outputDir, "channels.json")
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            val type = object : com.google.gson.reflect.TypeToken<List<CanonicalChannel>>() {}.type
            gson.fromJson(text, type) ?: emptyList()
        } catch (e: Exception) {
            logger.warn("DiffGenerator", "Failed to load previous catalogue: ${e.message}")
            emptyList()
        }
    }

    fun generate(
        new: List<CanonicalChannel>,
        fromVersion: Int,
        toVersion: Int,
    ): DiffResult? {
        if (!config.generateIncrementalDiffs) return null
        if (fromVersion < 0) {
            logger.info("DiffGenerator", "No previous version, skipping diff")
            return null
        }

        diffsDir.mkdirs()
        val old = loadPreviousCatalogue(fromVersion)
        if (old.isEmpty()) {
            logger.info("DiffGenerator", "No previous catalogue data, skipping diff")
            return null
        }

        val startMs = System.currentTimeMillis()
        val oldMap = old.associateBy { it.id }
        val newMap = new.associateBy { it.id }
        val oldIds = oldMap.keys
        val newIds = newMap.keys

        val added = newIds.subtract(oldIds).mapNotNull { newMap[it] }
        val removed = oldIds.subtract(newIds).toList()
        val updated = newIds.intersect(oldIds).filter { id ->
            oldMap[id]?.checksum != newMap[id]?.checksum
        }.mapNotNull { newMap[it] }

        val statusChanges = mutableListOf<LiveStatusChange>()
        for (id in newIds.intersect(oldIds)) {
            val o = oldMap[id]!!
            val n = newMap[id]!!
            val wasLive = o.sources.values.any { it.available }
            val isLive = n.sources.values.any { it.available }
            if (wasLive != isLive) {
                statusChanges.add(LiveStatusChange(
                    channelId = id, displayName = n.displayName,
                    wasLive = wasLive, isNowLive = isLive,
                    changedAtMs = System.currentTimeMillis(),
                ))
            }
        }

        val isFullSync = new.size > 0 && added.size.toDouble() / new.size > 0.5
        val diff = IncrementalDiff(
            fromVersion = fromVersion,
            toVersion = toVersion,
            generatedAtMs = System.currentTimeMillis(),
            addedChannels = if (isFullSync) emptyList() else added,
            removedChannelIds = if (isFullSync) emptyList() else removed,
            updatedChannels = if (isFullSync) emptyList() else updated,
            liveStatusChanges = statusChanges,
            isFullSync = isFullSync,
        )

        val json = gson.toJson(diff)
        val filename = "diff_${fromVersion}_to_${toVersion}.json"
        val file = File(config.outputDir, filename)
        file.writeText(json)

        val elapsed = System.currentTimeMillis() - startMs
        logger.info("DiffGenerator",
            "Generated diff: +${added.size} -${removed.size} ~${updated.size} live_changes=${statusChanges.size} " +
            "fullSync=$isFullSync in ${elapsed}ms")

        metrics.gauge("diff.added", added.size.toDouble())
        metrics.gauge("diff.removed", removed.size.toDouble())
        metrics.gauge("diff.updated", updated.size.toDouble())

        return DiffResult(diff, filename, json.toByteArray().size.toLong())
    }
}
