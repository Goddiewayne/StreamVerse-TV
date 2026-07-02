package com.streamverse.pipeline.model

data class CatalogueArtifact(
    val name: String,
    val relativePath: String,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val checksumSha256: String,
    val checksumMd5: String,
    val artifactType: ArtifactType,
    val generatedAtMs: Long,
)

enum class ArtifactType {
    CHANNELS, LIVE_CHANNELS, SEARCH_INDEX, CATEGORIES, REGIONS,
    RECOMMENDATIONS, PROVIDERS, FEATURED, ALIASES, VERSION, DIFF
}

data class VersionManifest(
    val schemaVersion: Int = 2,
    val pipelineVersion: String = "2.0.0",
    val catalogueVersion: Int,
    val previousCatalogueVersion: Int = -1,
    val generatedAtMs: Long = System.currentTimeMillis(),
    val channelCount: Int,
    val liveChannelCount: Int,
    val providerCount: Int,
    val sourceCount: Int,
    val artifacts: List<CatalogueArtifact> = emptyList(),
    val integrity: ManifestIntegrity = ManifestIntegrity(),
    val changelog: String = "",
)

data class ManifestIntegrity(
    val rootHash: String = "",
    val signed: Boolean = false,
    val signature: String = "",
)

data class IncrementalDiff(
    val fromVersion: Int,
    val toVersion: Int,
    val generatedAtMs: Long,
    val addedChannels: List<CanonicalChannel> = emptyList(),
    val removedChannelIds: List<String> = emptyList(),
    val updatedChannels: List<CanonicalChannel> = emptyList(),
    val liveStatusChanges: List<LiveStatusChange> = emptyList(),
    val sourceRankingChanges: List<String> = emptyList(),
    val categoryChanges: List<String> = emptyList(),
    val recommendationChanges: List<String> = emptyList(),
    val isFullSync: Boolean = false,
)

data class LiveStatusChange(
    val channelId: String,
    val displayName: String,
    val wasLive: Boolean,
    val isNowLive: Boolean,
    val changedAtMs: Long,
)

data class SearchIndexEntry(
    val id: String,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val category: String? = null,
    val country: String? = null,
    val language: String? = null,
    val logoUrl: String? = null,
    val quality: String? = null,
    val normalizedName: String = "",
    val keywords: List<String> = emptyList(),
)

data class CategoryIndex(
    val category: String,
    val channels: List<String>,
    val count: Int,
)

data class RegionalIndex(
    val country: String,
    val countryName: String,
    val channels: List<String>,
    val count: Int,
    val language: String? = null,
)

data class RecommendationSet(
    val popular: List<String> = emptyList(),
    val trending: List<String> = emptyList(),
    val recentlyAdded: List<String> = emptyList(),
    val topPicks: List<String> = emptyList(),
    val byCategory: Map<String, List<String>> = emptyMap(),
    val byRegion: Map<String, List<String>> = emptyMap(),
)

data class FeaturedChannel(
    val channelId: String,
    val displayName: String,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val priority: Int = 0,
    val reason: String? = null,
)

data class ProviderIndex(
    val sourceType: SourceType,
    val providerName: String,
    val channelCount: Int,
    val healthyCount: Int,
    val healthPct: Double,
)
