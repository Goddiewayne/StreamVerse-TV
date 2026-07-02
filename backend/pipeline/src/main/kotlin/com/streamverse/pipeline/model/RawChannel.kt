package com.streamverse.pipeline.model

enum class SourceType {
    BROADCASTER, FREE_CHANNEL, YOUTUBE_TV, SPORTS_EVENTS, WORLD_TV, GLOBAL_INDEX, RADIO
}

enum class Quality { SD, HD, FHD, _4K }

data class SourceInfo(
    val type: SourceType,
    val referenceId: String,
    val streamUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drmKeyId: String? = null,
    val drmKey: String? = null,
    val latencyMs: Long = -1,
    val available: Boolean = true,
    val lastCheckedMs: Long = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
)

data class RawChannel(
    val id: String,
    val displayName: String,
    val streamUrl: String?,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val language: String?,
    val quality: String?,
    val tvgId: String? = null,
    val source: SourceType,
    val headers: Map<String, String> = emptyMap(),
    val drmKeyId: String? = null,
    val drmKey: String? = null,
)
