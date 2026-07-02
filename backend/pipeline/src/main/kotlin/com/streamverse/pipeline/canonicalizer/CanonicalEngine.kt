package com.streamverse.pipeline.canonicalizer

import com.streamverse.pipeline.model.*
import com.streamverse.pipeline.telemetry.MetricsCollector
import com.streamverse.pipeline.telemetry.StructuredLogger

class CanonicalEngine(
    private val logger: StructuredLogger,
    private val metrics: MetricsCollector,
) {
    private val aliasDict = AliasDictionary()
    private val matchingEngine = MatchingEngine(aliasDict)

    data class DedupStats(
        val totalInput: Int,
        val resolvedChannels: Int,
        val duplicatesMerged: Int,
        val multiSourceChannels: Int,
    )

    fun process(channels: List<RawChannel>): List<CanonicalChannel> {
        val startMs = System.currentTimeMillis()
        logger.info("CanonicalEngine", "Canonicalizing ${channels.size} raw channels")

        val byHashKey = mutableMapOf<String, MutableSet<String>>()
        val byExactName = mutableMapOf<String, String>()
        val byTvgId = mutableMapOf<String, String>()
        val byId = linkedMapOf<String, MutableRawChannel>()

        var duplicatesMerged = 0
        var multiSourceCount = 0

        for (raw in channels) {
            val name = NameNormalizer.cleanDisplayName(raw.displayName)
            if (name.isBlank()) continue
            val hk = NameNormalizer.hashKey(name, aliasDict)
            if (hk.isBlank()) continue

            val existing = matchExisting(raw, name, hk, byExactName, byHashKey, byTvgId, byId)
            if (existing != null) {
                val info = SourceInfo(
                    type = raw.source,
                    referenceId = raw.id,
                    streamUrl = raw.streamUrl,
                    headers = raw.headers,
                    drmKeyId = raw.drmKeyId,
                    drmKey = raw.drmKey,
                )
                if (raw.source !in existing.sources) {
                    existing.sources[raw.source] = info
                    existing.totalSources = existing.sources.size
                    multiSourceCount++
                    existing.healthySources = existing.sources.count { it.value.available }
                }
                duplicatesMerged++
            } else {
                val id = generateId(raw)
                val info = SourceInfo(
                    type = raw.source,
                    referenceId = raw.id,
                    streamUrl = raw.streamUrl,
                    headers = raw.headers,
                    drmKeyId = raw.drmKeyId,
                    drmKey = raw.drmKey,
                )
                val canonical = MutableRawChannel(
                    id = id,
                    displayName = name,
                    logoUrl = raw.logoUrl,
                    quality = qualityFrom(raw.quality),
                    category = normalizeCategory(raw.category),
                    language = raw.language,
                    country = raw.country,
                    tvgId = raw.tvgId,
                    sources = mutableMapOf(raw.source to info),
                    totalSources = 1,
                    healthySources = 1,
                )
                byId[id] = canonical
                byHashKey.getOrPut(hk) { mutableSetOf() }.add(id)
                byExactName[name.lowercase().trim()] = id
                if (!raw.tvgId.isNullOrBlank()) byTvgId[raw.tvgId.trim().lowercase()] = id
            }
        }

        val result = byId.values.map { it.toCanonical() }
        val elapsed = System.currentTimeMillis() - startMs
        val stats = DedupStats(
            totalInput = channels.size,
            resolvedChannels = result.size,
            duplicatesMerged = duplicatesMerged,
            multiSourceChannels = result.count { it.sources.size > 1 },
        )

        logger.info("CanonicalEngine",
            "Canonicalized ${result.size} channels from ${channels.size} inputs " +
            "(${stats.duplicatesMerged} merges, ${stats.multiSourceChannels} multi-source) in ${elapsed}ms")

        metrics.gauge("canonical.input", channels.size.toDouble())
        metrics.gauge("canonical.output", result.size.toDouble())
        metrics.gauge("canonical.merges", duplicatesMerged.toDouble())

        return result
    }

    private fun matchExisting(
        raw: RawChannel,
        name: String,
        hk: String,
        byExactName: Map<String, String>,
        byHashKey: Map<String, Set<String>>,
        byTvgId: Map<String, String>,
        byId: Map<String, MutableRawChannel>,
    ): MutableRawChannel? {
        val norm = name.trim().lowercase()
        byExactName[norm]?.let { return byId[it] }
        byHashKey[hk]?.firstOrNull()?.let { return byId[it] }
        if (!raw.tvgId.isNullOrBlank()) {
            byTvgId[raw.tvgId.trim().lowercase()]?.let { return byId[it] }
        }
        return null
    }

    private fun generateId(raw: RawChannel): String {
        val prefix = when (raw.source) {
            SourceType.BROADCASTER -> "br"
            SourceType.FREE_CHANNEL -> "free"
            SourceType.YOUTUBE_TV -> "yt"
            SourceType.SPORTS_EVENTS -> "sport"
            SourceType.WORLD_TV -> "world"
            SourceType.GLOBAL_INDEX -> "idx"
            SourceType.RADIO -> "radio"
        }
        return "${prefix}_${raw.id}"
    }

    private fun qualityFrom(q: String?) = when (q?.uppercase()) {
        "4K" -> Quality._4K
        "FHD", "1080P" -> Quality.FHD
        "HD", "720P" -> Quality.HD
        "SD", "480P", "360P" -> Quality.SD
        else -> null
    }

    private val categoryAliases = mapOf(
        "news" to "News", "sports" to "Sports", "movie" to "Movies",
        "movies" to "Movies", "film" to "Movies", "kids" to "Kids",
        "children" to "Kids", "music" to "Music", "documentary" to "Documentary",
        "docu" to "Documentary", "religious" to "Religious", "religion" to "Religious",
        "lifestyle" to "Lifestyle", "comedy" to "Comedy", "science" to "Science",
        "entertainment" to "Entertainment", "business" to "Business",
        "education" to "Education", "travel" to "Travel", "nature" to "Nature",
        "weather" to "Weather", "shopping" to "Shopping", "animation" to "Animation",
        "anime" to "Animation", "series" to "Entertainment", "drama" to "Entertainment",
        "reality" to "Entertainment", "talk" to "Entertainment", "variety" to "Entertainment",
        "general" to "General", "international" to "International", "local" to "Local",
        "public" to "General",
    )

    private fun normalizeCategory(raw: String?): String? {
        if (raw == null || raw.isBlank()) return null
        val lower = raw.lowercase().trim()
        return categoryAliases[lower] ?: raw.trim().replaceFirstChar { it.uppercaseChar() }
    }

    private class MutableRawChannel(
        val id: String,
        var displayName: String,
        var logoUrl: String?,
        var quality: Quality?,
        var category: String?,
        var language: String?,
        var country: String?,
        var tvgId: String?,
        val sources: MutableMap<SourceType, SourceInfo>,
        var totalSources: Int,
        var healthySources: Int,
    ) {
        fun toCanonical(): CanonicalChannel {
            val checksumInput = sources.entries.sortedBy { it.key.name }
                .joinToString("|") { "${it.key.name}:${it.value.referenceId}:${it.value.streamUrl}" }
            return CanonicalChannel(
                id = id,
                displayName = displayName,
                logoUrl = logoUrl,
                quality = quality,
                category = category,
                language = language,
                country = country,
                tvgId = tvgId,
                sources = sources.toMap(),
                totalSources = sources.size,
                healthySources = sources.count { it.value.available },
                checksum = java.security.MessageDigest.getInstance("MD5")
                    .digest(checksumInput.toByteArray()).joinToString("") { "%02x".format(it) },
            )
        }
    }
}
