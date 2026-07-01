package com.streamverse.core.domain.model

data class Channel(
    val id: String,
    val logicalId: String = id,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val logoUrl: String?,
    val quality: Quality?,
    val category: String?,
    val language: String?,
    val country: String?,
    val description: String?,
    val sources: Map<SourceType, SourceInfo>,
    val isFavorite: Boolean = false,
    val tvgId: String? = null,
)

enum class Quality { SD, HD, FHD, _4K }

/**
 * Identifies the origin of a channel stream.
 *
 * Providers are grouped by fetch method:
 * - **Alpha** (tier 0): Local asset JSON — BROADCASTER (instant, no network)
 * - **Beta**  (tier 1): Aggregated index — GLOBAL_INDEX
 * - **Gamma** (tier 2): Individual API/scrape — the remaining 5 types
 */
enum class SourceType {
    /** Merged global channel index from M3U aggregators (iptv-org, Free-TV, community). */
    GLOBAL_INDEX,
    /** Direct FTA satellite and broadcaster CDN feeds from official sources. */
    BROADCASTER,
    /** Pluto TV, Plex, Roku, Tubi, Xumo & Distro TV direct CDN playlists. */
    FREE_CHANNEL,
    /** Live sports, news & entertainment channels requiring stream resolution. */
    SPORTS_EVENTS,
    /** Middle Eastern, African & international channels with search support. */
    WORLD_TV,
    /** YouTube channels broadcasting live TV, resolved via NewPipeExtractor. */
    YOUTUBE_TV,
    /** Live internet radio stations. */
    RADIO,
    ;

    companion object {
        /** Identity — preserved for backward compatibility. */
        fun canonicalOf(type: SourceType): SourceType = type
    }
}

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
) {
    val reliabilityScore: Float
        get() {
            val total = successCount + failureCount
            if (total == 0) return 0.5f
            return successCount.toFloat() / total
        }
}

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
