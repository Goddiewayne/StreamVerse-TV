package com.streamverse.pipeline.generator

import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.*
import com.streamverse.pipeline.telemetry.MetricsCollector
import com.streamverse.pipeline.telemetry.StructuredLogger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class CatalogueBuilder(
    private val config: PipelineConfig,
    private val logger: StructuredLogger,
    private val metrics: MetricsCollector,
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val outputDir = File(config.outputDir)
    private val artifacts = mutableListOf<CatalogueArtifact>()

    data class BuildResult(
        val channels: List<CanonicalChannel>,
        val artifacts: List<CatalogueArtifact>,
        val version: VersionManifest,
        val durationMs: Long,
    )

    fun build(
        channels: List<CanonicalChannel>,
        rankings: List<SourceRanking>,
        health: List<SourceHealthSnapshot>,
        previousVersion: Int = -1,
    ): BuildResult {
        val startMs = System.currentTimeMillis()
        logger.info("CatalogueBuilder", "Building catalogue version ${config.catalogueVersion}")
        outputDir.mkdirs()
        artifacts.clear()

        val liveChannels = channels.filter { it.sources.values.any { s -> s.available } }

        writeChannels(channels)
        writeLiveChannels(liveChannels)
        writeSearchIndex(channels)
        writeCategoryIndex(channels)
        writeRegionalIndex(channels)
        writeRecommendations(channels, rankings)
        writeProviders(channels, health)
        writeFeatured(channels)
        writeAliases(channels)
        val versionManifest = buildVersionManifest(channels, liveChannels, health, previousVersion)

        val elapsed = System.currentTimeMillis() - startMs
        logger.info("CatalogueBuilder",
            "Built ${artifacts.size} artifacts for ${channels.size} channels in ${elapsed}ms")

        metrics.gauge("catalogue.channels", channels.size.toDouble())
        metrics.gauge("catalogue.live", liveChannels.size.toDouble())
        metrics.gauge("catalogue.artifacts", artifacts.size.toDouble())
        metrics.gauge("catalogue.duration_ms", elapsed.toDouble())

        return BuildResult(channels, artifacts.toList(), versionManifest, elapsed)
    }

    fun generateDiff(
        oldChannels: List<CanonicalChannel>,
        newChannels: List<CanonicalChannel>,
        fromVersion: Int,
        toVersion: Int,
    ) {
        val diff = computeDiff(oldChannels, newChannels, fromVersion, toVersion)
        val json = gson.toJson(diff)
        writeArtifact("diff_${fromVersion}_to_${toVersion}.json", json, ArtifactType.DIFF)
        writeCompressed("diff_${fromVersion}_to_${toVersion}.json.gz", json, ArtifactType.DIFF)
    }

    private fun computeDiff(
        old: List<CanonicalChannel>,
        new: List<CanonicalChannel>,
        fromVersion: Int,
        toVersion: Int,
    ): IncrementalDiff {
        val oldMap = old.associateBy { it.id }
        val newMap = new.associateBy { it.id }
        val oldIds = oldMap.keys
        val newIds = newMap.keys

        val added = newIds.subtract(oldIds).mapNotNull { newMap[it] }
        val removed = oldIds.subtract(newIds).toList()
        val updated = newIds.intersect(oldIds).filter { id ->
            val o = oldMap[id]!!
            val n = newMap[id]!!
            o.checksum != n.checksum
        }.mapNotNull { newMap[it] }

        val statusChanges = mutableListOf<LiveStatusChange>()
        for (id in newIds.intersect(oldIds)) {
            val o = oldMap[id]!!
            val n = newMap[id]!!
            val wasLive = o.sources.values.any { it.available }
            val isLive = n.sources.values.any { it.available }
            if (wasLive != isLive) {
                statusChanges.add(LiveStatusChange(
                    channelId = id,
                    displayName = n.displayName,
                    wasLive = wasLive,
                    isNowLive = isLive,
                    changedAtMs = System.currentTimeMillis(),
                ))
            }
        }

        val fullSync = added.size.toDouble() / new.size > 0.5

        return IncrementalDiff(
            fromVersion = fromVersion,
            toVersion = toVersion,
            generatedAtMs = System.currentTimeMillis(),
            addedChannels = added,
            removedChannelIds = removed,
            updatedChannels = updated,
            liveStatusChanges = statusChanges,
            isFullSync = fullSync,
        )
    }

    private fun writeChannels(channels: List<CanonicalChannel>) {
        val json = gson.toJson(channels)
        writeArtifact("channels.json", json, ArtifactType.CHANNELS)
        writeCompressed("channels.json.gz", json, ArtifactType.CHANNELS)
    }

    private fun writeLiveChannels(live: List<CanonicalChannel>) {
        val json = gson.toJson(live)
        writeArtifact("live_channels.json", json, ArtifactType.LIVE_CHANNELS)
        writeCompressed("live_channels.json.gz", json, ArtifactType.LIVE_CHANNELS)
    }

    private fun writeSearchIndex(channels: List<CanonicalChannel>) {
        val entries = channels.map { ch ->
            val keywords = mutableListOf(ch.displayName.lowercase())
            keywords.addAll(ch.aliases.map { it.lowercase() })
            ch.category?.let { keywords.add(it.lowercase()) }
            ch.country?.let { keywords.add(it.lowercase()) }
            ch.language?.let { keywords.add(it.lowercase()) }
            SearchIndexEntry(
                id = ch.id,
                displayName = ch.displayName,
                aliases = ch.aliases,
                category = ch.category,
                country = ch.country,
                language = ch.language,
                logoUrl = ch.logoUrl,
                quality = ch.quality?.name,
                normalizedName = ch.displayName.lowercase(),
                keywords = keywords.distinct(),
            )
        }
        val json = gson.toJson(entries)
        writeArtifact("search_index.json", json, ArtifactType.SEARCH_INDEX)
        writeCompressed("search_index.json.gz", json, ArtifactType.SEARCH_INDEX)
    }

    private fun writeCategoryIndex(channels: List<CanonicalChannel>) {
        val byCat = channels.filter { it.category != null }
            .groupBy { it.category!! }
            .mapValues { (_, chs) -> chs.map { it.id } }
        val indexes = byCat.map { (cat, ids) ->
            CategoryIndex(category = cat, channels = ids, count = ids.size)
        }.sortedByDescending { it.count }
        val json = gson.toJson(indexes)
        writeArtifact("categories.json", json, ArtifactType.CATEGORIES)
        writeCompressed("categories.json.gz", json, ArtifactType.CATEGORIES)
    }

    private fun writeRegionalIndex(channels: List<CanonicalChannel>) {
        val byCountry = channels.filter { it.country != null }
            .groupBy { it.country!! }
            .mapValues { (_, chs) -> chs.map { it.id } }
        val indexes = byCountry.map { (country, ids) ->
            RegionalIndex(
                country = country,
                countryName = country.uppercase(),
                channels = ids,
                count = ids.size,
            )
        }.sortedByDescending { it.count }
        val json = gson.toJson(indexes)
        writeArtifact("regions.json", json, ArtifactType.REGIONS)
        writeCompressed("regions.json.gz", json, ArtifactType.REGIONS)
    }

    private fun writeRecommendations(channels: List<CanonicalChannel>, rankings: List<SourceRanking>) {
        val popular = channels
            .filter { it.sources.size > 1 }
            .sortedByDescending { it.sources.size }
            .take(50)
            .map { it.id }

        val byCategory = channels.filter { it.category != null }
            .groupBy { it.category!! }
            .mapValues { (_, chs) -> chs.take(20).map { it.id } }

        val recs = RecommendationSet(
            popular = popular,
            trending = popular.take(20),
            recentlyAdded = channels.takeLast(20).map { it.id },
            topPicks = channels.filter { it.sources.any { s -> s.value.available } }
                .take(30).map { it.id },
            byCategory = byCategory,
        )
        val json = gson.toJson(recs)
        writeArtifact("recommendations.json", json, ArtifactType.RECOMMENDATIONS)
        writeCompressed("recommendations.json.gz", json, ArtifactType.RECOMMENDATIONS)
    }

    private fun writeProviders(channels: List<CanonicalChannel>, health: List<SourceHealthSnapshot>) {
        val healthByType = health.groupBy { it.sourceType }
        val providers = SourceType.values().map { st ->
            val chsWithSource = channels.count { st in it.sources }
            val healthForType = healthByType[st].orEmpty()
            val healthy = healthForType.count { it.isHealthy }
            val healthPct = if (healthForType.isNotEmpty())
                (healthy.toDouble() / healthForType.size) * 100.0 else 0.0
            ProviderIndex(
                sourceType = st,
                providerName = st.name,
                channelCount = chsWithSource,
                healthyCount = healthy,
                healthPct = healthPct,
            )
        }
        val json = gson.toJson(providers)
        writeArtifact("providers.json", json, ArtifactType.PROVIDERS)
        writeCompressed("providers.json.gz", json, ArtifactType.PROVIDERS)
    }

    private fun writeFeatured(channels: List<CanonicalChannel>) {
        val broadcaster = channels.filter { SourceType.BROADCASTER in it.sources }
            .map { FeaturedChannel(it.id, it.displayName, it.logoUrl, it.category, it.country, 0, "Broadcaster") }
        val multiSource = channels.filter { it.sources.size > 2 }
            .map { FeaturedChannel(it.id, it.displayName, it.logoUrl, it.category, it.country, 1, "Multi-source") }
        val featured = (broadcaster + multiSource).distinctBy { it.channelId }.take(100)
        val json = gson.toJson(featured)
        writeArtifact("featured.json", json, ArtifactType.FEATURED)
        writeCompressed("featured.json.gz", json, ArtifactType.FEATURED)
    }

    private fun writeAliases(channels: List<CanonicalChannel>) {
        val aliasMap = channels.filter { it.aliases.isNotEmpty() }
            .flatMap { ch -> ch.aliases.map { it to ch.id } }
            .toMap()
        val json = gson.toJson(aliasMap)
        writeArtifact("aliases.json", json, ArtifactType.ALIASES)
        writeCompressed("aliases.json.gz", json, ArtifactType.ALIASES)
    }

    private fun buildVersionManifest(
        channels: List<CanonicalChannel>,
        live: List<CanonicalChannel>,
        health: List<SourceHealthSnapshot>,
        previousVersion: Int,
    ): VersionManifest {
        val providerCount = SourceType.values().count { st -> channels.any { st in it.sources } }
        val sourceCount = channels.sumOf { it.sources.size }

        val versionFile = File(outputDir, "version.json")
        if (versionFile.exists()) versionFile.delete()

        val manifest = VersionManifest(
            catalogueVersion = config.catalogueVersion,
            previousCatalogueVersion = previousVersion,
            channelCount = channels.size,
            liveChannelCount = live.size,
            providerCount = providerCount,
            sourceCount = sourceCount,
            artifacts = artifacts.toList(),
        )
        val json = gson.toJson(manifest)
        writeArtifact("version.json", json, ArtifactType.VERSION)
        return manifest
    }

    private fun writeArtifact(name: String, content: String, type: ArtifactType) {
        val file = File(outputDir, name)
        file.writeText(content)
        val uncompressed = content.toByteArray().size.toLong()
        val checksumSha256 = hashString(content, "SHA-256")
        val checksumMd5 = hashString(content, "MD5")
        artifacts.add(CatalogueArtifact(
            name = name,
            relativePath = name,
            uncompressedBytes = uncompressed,
            compressedBytes = uncompressed,
            checksumSha256 = checksumSha256,
            checksumMd5 = checksumMd5,
            artifactType = type,
            generatedAtMs = System.currentTimeMillis(),
        ))
        logger.info("CatalogueBuilder", "Wrote $name (${uncompressed} bytes, sha256=$checksumSha256)")
    }

    private fun writeCompressed(name: String, content: String, type: ArtifactType) {
        if (!config.generateCompressedArtifacts) return
        val file = File(outputDir, name)
        val uncompressedBytes = content.toByteArray()
        FileOutputStream(file).use { fos ->
            GZIPOutputStream(fos).use { gzip ->
                gzip.write(uncompressedBytes)
            }
        }
        val uncompressed = uncompressedBytes.size.toLong()
        val compressed = file.length()
        val fileBytes = file.readBytes()
        val checksumSha256 = hashBytes(fileBytes, "SHA-256")
        val checksumMd5 = hashBytes(fileBytes, "MD5")
        artifacts.add(CatalogueArtifact(
            name = name,
            relativePath = name,
            uncompressedBytes = uncompressed,
            compressedBytes = compressed,
            checksumSha256 = checksumSha256,
            checksumMd5 = checksumMd5,
            artifactType = type,
            generatedAtMs = System.currentTimeMillis(),
        ))
        logger.info("CatalogueBuilder", "Wrote $name (${uncompressed} -> ${compressed} bytes, ${if (uncompressed > 0) (100 - compressed * 100 / uncompressed) else 0}% saved)")
    }

    private fun hashString(content: String, algorithm: String): String {
        return hashBytes(content.toByteArray(), algorithm)
    }

    private fun hashBytes(bytes: ByteArray, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
