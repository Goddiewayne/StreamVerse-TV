package com.streamverse.pipeline.model

data class CanonicalChannel(
    val id: String,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val logoUrl: String? = null,
    val quality: Quality? = null,
    val category: String? = null,
    val language: String? = null,
    val country: String? = null,
    val description: String? = null,
    val tvgId: String? = null,
    val sources: Map<SourceType, SourceInfo> = emptyMap(),
    val sourcePriority: Int = Int.MAX_VALUE,
    val healthySources: Int = 0,
    val totalSources: Int = 0,
    val isLive: Boolean = true,
    val isVerified: Boolean = false,
    val checksum: String = "",
) {
    companion object {
        val VERIFIED_SOURCE_TYPES = SourceType.entries.toSet() - SourceType.GLOBAL_INDEX
    }
}

data class MergedCatalogue(
    val version: Int,
    val generatedAtMs: Long,
    val channels: List<CanonicalChannel>,
)
