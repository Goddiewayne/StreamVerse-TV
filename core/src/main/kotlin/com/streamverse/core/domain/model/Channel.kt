package com.streamverse.core.domain.model

data class Channel(
    val id: String,
    val displayName: String,
    val logoUrl: String?,
    val quality: Quality?,
    val category: String?,
    val language: String?,
    val country: String?,
    val description: String?,
    val sources: Map<SourceType, SourceInfo>,
    val isFavorite: Boolean = false,
)

enum class Quality { SD, HD, FHD, _4K }

enum class SourceType {
    IPTV,
    FREE_TV,
    DLHD,
    STMIFY_FREE,
    STMIFY_PREMIUM,
    RADIO,
    FAST_TV,   // Pluto TV, Samsung TV Plus, Plex, Roku – free ad-supported
    INDEPENDENT, // Curated verified-working free streams (independent source)
    PREMIUM,   // Premium channels (HBO, Showtime, Starz, sports, etc.)
}

data class SourceInfo(
    val type: SourceType,
    val referenceId: String,
    val streamUrl: String? = null,
    // Per-stream HTTP headers (User-Agent/Referer/Origin) required by some CDN feeds.
    val headers: Map<String, String> = emptyMap(),
    // ClearKey DRM carried from the playlist, when present.
    val drmKeyId: String? = null,
    val drmKey: String? = null,
)

data class ScheduleEvent(
    val time: String,
    val title: String,
    val category: String,
    val channelIds: List<String>,
    val date: String,
)

data class ScheduleDay(
    val date: String,
    val events: Map<String, List<ScheduleEvent>>,
)

data class ChannelCategory(
    val name: String,
    val channels: List<Channel>,
)
