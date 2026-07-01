package com.streamverse.core.data.source.provider

import com.streamverse.core.data.SourceProvider
import com.streamverse.core.domain.model.SourceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for all source provider metadata.
 *
 * Providers are grouped by fetch method into three tiers:
 * - **Alpha** (tier 0): Local asset JSON — BROADCASTER (instant, no network)
 * - **Beta**  (tier 1): Hosted index + M3U fallback — GLOBAL_INDEX
 * - **Gamma** (tier 2): Individual API/scrape clients — FREE_CHANNEL, YOUTUBE_TV,
 *   WORLD_TV, SPORTS_EVENTS, RADIO
 */
@Singleton
class ProviderRegistry @Inject constructor() {

    private val metadataMap: Map<SourceType, ProviderMetadata> = buildMetadata()
    private val groupMap: Map<ProviderGroup, List<ProviderMetadata>> =
        metadataMap.values.groupBy { it.group }.mapValues { (_, v) -> v.sortedBy { it.displayName } }
    private val priorityMap: Map<SourceType, Int> = buildPriority()
    private val acquisitionMap: Map<AcquisitionMethod, List<ProviderMetadata>> =
        metadataMap.values.groupBy { it.acquisitionMethod }

    // ── Metadata ────────────────────────────────────────────────────────────

    fun metadata(type: SourceType): ProviderMetadata =
        metadataMap[SourceType.canonicalOf(type)] ?: error("Unknown SourceType: $type")

    fun metadataOrNull(type: SourceType): ProviderMetadata? =
        metadataMap[SourceType.canonicalOf(type)]

    fun allMetadata(): Collection<ProviderMetadata> = metadataMap.values

    fun providersInGroup(group: ProviderGroup): List<ProviderMetadata> =
        groupMap[group] ?: emptyList()

    fun groups(): List<ProviderGroup> = groupMap.keys.sortedBy { it.sortOrder }

    fun displayName(type: SourceType): String = metadata(type).displayName

    fun description(type: SourceType): String = metadata(type).description

    fun group(type: SourceType): ProviderGroup = metadata(type).group

    // ── Priority ────────────────────────────────────────────────────────────

    /** Optional user-defined priority order from Settings. */
    private var priorityOverride: Map<SourceType, Int>? = null

    /** Apply a custom provider ordering (from Settings). Clears override when empty. */
    fun setPriorityOverride(order: List<SourceProvider>) {
        priorityOverride = if (order.isEmpty()) null else buildPriorityOverride(order)
    }

    /** Build a priority map from a user-ordered list of SourceProviders. */
    private fun buildPriorityOverride(order: List<SourceProvider>): Map<SourceType, Int> {
        val result = mutableMapOf<SourceType, Int>()
        order.forEachIndexed { index, provider ->
            val types = sourceTypesForProvider(provider)
            for (type in types) {
                // Only override if this SourceType exists in the default map
                if (type in priorityMap) {
                    result[type] = index
                }
            }
        }
        return result
    }

    /** Map a SourceProvider to its SourceType(s). */
    private fun sourceTypesForProvider(provider: SourceProvider): List<SourceType> = when (provider) {
        SourceProvider.GLOBAL_INDEX -> listOf(SourceType.GLOBAL_INDEX)
        SourceProvider.BROADCASTER -> listOf(SourceType.BROADCASTER)
        SourceProvider.FREE_CHANNEL -> listOf(SourceType.FREE_CHANNEL)
        SourceProvider.SPORTS_EVENTS -> listOf(SourceType.SPORTS_EVENTS)
        SourceProvider.WORLD_TV -> listOf(SourceType.WORLD_TV)
        SourceProvider.YOUTUBE_TV -> listOf(SourceType.YOUTUBE_TV)
        SourceProvider.RADIO -> listOf(SourceType.RADIO)
    }

    fun priority(type: SourceType): Int {
        val canonical = SourceType.canonicalOf(type)
        return priorityOverride?.get(canonical)
            ?: priorityMap[canonical]
            ?: Int.MAX_VALUE
    }

    fun priorityScore(type: SourceType): Float {
        val canonical = SourceType.canonicalOf(type)
        return if (priorityOverride != null) {
            (priorityOverride!![canonical] ?: Int.MAX_VALUE).toFloat()
        } else {
            when (canonical) {
                SourceType.BROADCASTER -> 40f
                SourceType.FREE_CHANNEL -> 38f
                SourceType.YOUTUBE_TV -> 35f
                SourceType.SPORTS_EVENTS -> 30f
                SourceType.WORLD_TV -> 28f
                SourceType.GLOBAL_INDEX -> 20f
                SourceType.RADIO -> 5f
            }
        }
    }

    fun prioritySorted(): List<SourceType> {
        val map = priorityOverride ?: priorityMap
        return map.entries
            .sortedBy { it.value }
            .map { it.key }
    }

    // ── SourceType convenience ───────────────────────────────────────────────

    /** Returns true for SourceTypes that require dynamic URL resolution. */
    fun requiresResolution(type: SourceType): Boolean =
        metadata(type).requiresResolution

    /** Returns true when a source type is an audio-only source. */
    fun isAudioOnly(type: SourceType): Boolean =
        metadata(type).contentFocus == ContentFocus.RADIO_AUDIO

    /** Providers whose content substantially overlaps with [type] (dedup hints). */
    fun overlappingTypes(type: SourceType): Set<SourceType> =
        metadata(type).overlappingTypes

    // ── Builders ─────────────────────────────────────────────────────────────

    private fun buildMetadata(): Map<SourceType, ProviderMetadata> = mapOf(
        // ── Alpha: Local Assets (instant, no network) ────────────────────────
        SourceType.BROADCASTER to ProviderMetadata(
            id = "broadcaster",
            displayName = "Official Broadcasters",
            description = "Direct FTA satellite and broadcaster CDN feeds from official sources",
            group = ProviderGroup.ALPHA,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.GENERAL,
            reliabilityTier = ReliabilityTier.VERIFIED_OFFICIAL,
            acquisitionMethod = AcquisitionMethod.STATIC_ASSET,
            authModel = AuthModel.NONE,
            sourceType = SourceType.BROADCASTER,
            refreshStrategy = RefreshStrategy.WEEKLY,
        ),
        // ── Beta: Aggregated Index (hosted index + M3U fallback) ──────────────
        SourceType.GLOBAL_INDEX to ProviderMetadata(
            id = "global_index",
            displayName = "Global Channels",
            description = "Merged channel index from iptv-org, Free-TV and community M3U playlists",
            group = ProviderGroup.BETA,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.GENERAL,
            reliabilityTier = ReliabilityTier.VERIFIED_COMMUNITY,
            acquisitionMethod = AcquisitionMethod.M3U_PLAYLIST,
            authModel = AuthModel.NONE,
            sourceType = SourceType.GLOBAL_INDEX,
            epgSupported = true,
        ),
        // ── Gamma: API Sources (individual API/scrape clients) ────────────────
        SourceType.FREE_CHANNEL to ProviderMetadata(
            id = "free_channel",
            displayName = "Free Streaming Services",
            description = "Pluto TV, Plex, Roku, Tubi, Xumo & Distro TV direct CDN playlists",
            group = ProviderGroup.GAMMA,
            geographicScope = GeographicScope.MULTI_REGION,
            contentFocus = ContentFocus.GENERAL,
            reliabilityTier = ReliabilityTier.VERIFIED_COMMUNITY,
            acquisitionMethod = AcquisitionMethod.M3U_PLAYLIST,
            authModel = AuthModel.NONE,
            sourceType = SourceType.FREE_CHANNEL,
        ),
        SourceType.YOUTUBE_TV to ProviderMetadata(
            id = "youtube_tv",
            displayName = "YouTube TV",
            description = "Live TV channels streaming on YouTube, resolved to HLS via NewPipeExtractor",
            group = ProviderGroup.GAMMA,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.GENERAL,
            reliabilityTier = ReliabilityTier.UNVERIFIED,
            acquisitionMethod = AcquisitionMethod.REST_API,
            authModel = AuthModel.NONE,
            sourceType = SourceType.YOUTUBE_TV,
            epgSupported = false,
            refreshStrategy = RefreshStrategy.WEEKLY,
        ),
        SourceType.WORLD_TV to ProviderMetadata(
            id = "world_tv",
            displayName = "World TV",
            description = "Middle Eastern, African & international channels with search support",
            group = ProviderGroup.GAMMA,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.INTERNATIONAL,
            reliabilityTier = ReliabilityTier.UNVERIFIED,
            acquisitionMethod = AcquisitionMethod.WEB_SCRAPE,
            authModel = AuthModel.SCRAPE_ACCESS,
            sourceType = SourceType.WORLD_TV,
            requiresResolution = true,
            overlappingTypes = setOf(SourceType.SPORTS_EVENTS),
        ),
        SourceType.SPORTS_EVENTS to ProviderMetadata(
            id = "sports_events",
            displayName = "Sports & Events",
            description = "Live sports, news & entertainment channels requiring stream resolution",
            group = ProviderGroup.GAMMA,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.SPORTS,
            reliabilityTier = ReliabilityTier.UNVERIFIED,
            acquisitionMethod = AcquisitionMethod.WEB_SCRAPE,
            authModel = AuthModel.SCRAPE_ACCESS,
            sourceType = SourceType.SPORTS_EVENTS,
            requiresResolution = true,
            epgSupported = true,
            overlappingTypes = setOf(SourceType.WORLD_TV),
        ),
        SourceType.RADIO to ProviderMetadata(
            id = "radio_browser",
            displayName = "Radio Browser",
            description = "Live internet radio stations from the radio-browser.info community database",
            group = ProviderGroup.GAMMA,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.RADIO_AUDIO,
            reliabilityTier = ReliabilityTier.VERIFIED_COMMUNITY,
            acquisitionMethod = AcquisitionMethod.REST_API,
            authModel = AuthModel.NONE,
            sourceType = SourceType.RADIO,
            epgSupported = false,
            refreshStrategy = RefreshStrategy.WEEKLY,
        ),
    )

    private fun buildPriority(): Map<SourceType, Int> = mapOf(
        SourceType.BROADCASTER to 0,
        SourceType.FREE_CHANNEL to 1,
        SourceType.YOUTUBE_TV to 2,
        SourceType.SPORTS_EVENTS to 3,
        SourceType.WORLD_TV to 4,
        SourceType.GLOBAL_INDEX to 5,
        SourceType.RADIO to 6,
    )
}
