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

enum class SourceType {
    // ── Canonical names ────────────────────────────────────────────────────
    IPTV,
    FREE_TV,
    RADIO,
    FAST_TV,
    PREMIUM,
    BROADCASTER,
    FREE_CHANNEL,

    /** Hand-picked channels from independent CDNs, verified to work. */
    VERIFIED,
    /** Live sports, news & entertainment channels requiring stream resolution. */
    SPORTS_EVENTS,
    /** Middle Eastern, African & international channels with search support. */
    WORLD_TV,

    /** YouTube channels broadcasting live TV, resolved via NewPipeExtractor. */
    YOUTUBE_TV,

    // ── Deprecated aliases (kept for cache/backward compatibility) ────────
    /** @deprecated Use [VERIFIED] */
    INDEPENDENT,
    /** @deprecated Use [SPORTS_EVENTS] */
    DLHD,
    /** @deprecated Use [WORLD_TV] */
    STMIFY_FREE,
    /** @deprecated Use [WORLD_TV] */
    STMIFY_PREMIUM,
    ;

    companion object {
        /** Maps every deprecated value to its canonical replacement. */
        val canonical: Map<SourceType, SourceType> = mapOf(
            INDEPENDENT to VERIFIED,
            DLHD to SPORTS_EVENTS,
            STMIFY_FREE to WORLD_TV,
            STMIFY_PREMIUM to WORLD_TV,
        )

        /** Resolve to canonical type — identity for non-deprecated values. */
        fun canonicalOf(type: SourceType): SourceType = canonical[type] ?: type
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
