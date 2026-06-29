package com.streamverse.core.data.source.provider

import com.streamverse.core.domain.model.SourceType
import javax.inject.Inject
import javax.inject.Singleton

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

    fun priority(type: SourceType): Int =
        priorityMap[SourceType.canonicalOf(type)] ?: Int.MAX_VALUE

    fun priorityScore(type: SourceType): Float {
        val canonical = SourceType.canonicalOf(type)
        return when (canonical) {
            SourceType.VERIFIED -> 40f
            SourceType.BROADCASTER -> 38f
            SourceType.FREE_CHANNEL -> 36f
            SourceType.SPORTS_EVENTS -> 30f
            SourceType.WORLD_TV -> 28f
            SourceType.PREMIUM -> 25f
            SourceType.FAST_TV -> 20f
            SourceType.IPTV -> 15f
            SourceType.FREE_TV -> 14f
            SourceType.RADIO -> 5f
            // Deprecated types resolved above — unreachable
            SourceType.INDEPENDENT, SourceType.DLHD,
            SourceType.STMIFY_FREE, SourceType.STMIFY_PREMIUM -> 0f
        }
    }

    fun prioritySorted(): List<SourceType> = priorityMap.entries
        .sortedBy { it.value }
        .map { it.key }

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

    /** The canonical [SourceType] for the given type (identity if already canonical). */
    fun canonical(type: SourceType): SourceType = SourceType.canonicalOf(type)

    // ── Builders ─────────────────────────────────────────────────────────────

    private fun buildMetadata(): Map<SourceType, ProviderMetadata> = mapOf(
        SourceType.IPTV to ProviderMetadata(
            id = "iptv_org",
            displayName = "Global Channels",
            description = "10,000+ live channels from iptv-org's community-maintained global index",
            group = ProviderGroup.GLOBAL_AGGREGATOR,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.GENERAL,
            reliabilityTier = ReliabilityTier.VERIFIED_COMMUNITY,
            acquisitionMethod = AcquisitionMethod.M3U_PLAYLIST,
            authModel = AuthModel.NONE,
            sourceType = SourceType.IPTV,
            epgSupported = true,
            overlappingTypes = setOf(SourceType.FREE_TV, SourceType.FAST_TV),
        ),
        SourceType.FREE_TV to ProviderMetadata(
            id = "free_tv",
            displayName = "Free-to-Air TV",
            description = "Curated HD broadcast channels from Free-TV/IPTV community project",
            group = ProviderGroup.GLOBAL_AGGREGATOR,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.GENERAL,
            reliabilityTier = ReliabilityTier.VERIFIED_COMMUNITY,
            acquisitionMethod = AcquisitionMethod.M3U_PLAYLIST,
            authModel = AuthModel.NONE,
            sourceType = SourceType.FREE_TV,
            overlappingTypes = setOf(SourceType.IPTV, SourceType.FAST_TV),
        ),
        SourceType.FAST_TV to ProviderMetadata(
            id = "fast_tv",
            displayName = "Regional Live TV",
            description = "Direct live streams from iptv-org supplemental playlists across 28 countries",
            group = ProviderGroup.GLOBAL_AGGREGATOR,
            geographicScope = GeographicScope.MULTI_REGION,
            contentFocus = ContentFocus.GENERAL,
            reliabilityTier = ReliabilityTier.VERIFIED_COMMUNITY,
            acquisitionMethod = AcquisitionMethod.M3U_PLAYLIST,
            authModel = AuthModel.NONE,
            sourceType = SourceType.FAST_TV,
            overlappingTypes = setOf(SourceType.IPTV, SourceType.FREE_TV, SourceType.FREE_CHANNEL),
        ),
        SourceType.FREE_CHANNEL to ProviderMetadata(
            id = "free_channel",
            displayName = "Free Streaming Services",
            description = "Pluto TV, Plex, Roku, Tubi, Xumo & Distro TV direct CDN playlists",
            group = ProviderGroup.FAST_SERVICE,
            geographicScope = GeographicScope.MULTI_REGION,
            contentFocus = ContentFocus.GENERAL,
            reliabilityTier = ReliabilityTier.VERIFIED_COMMUNITY,
            acquisitionMethod = AcquisitionMethod.M3U_PLAYLIST,
            authModel = AuthModel.NONE,
            sourceType = SourceType.FREE_CHANNEL,
            overlappingTypes = setOf(SourceType.FAST_TV),
        ),
        SourceType.VERIFIED to ProviderMetadata(
            id = "verified",
            displayName = "Verified Channels",
            description = "Hand-picked channels from independent CDNs, verified to work",
            group = ProviderGroup.VERIFIED_CURATED,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.GENERAL,
            reliabilityTier = ReliabilityTier.VERIFIED_OFFICIAL,
            acquisitionMethod = AcquisitionMethod.STATIC_ASSET,
            authModel = AuthModel.NONE,
            sourceType = SourceType.VERIFIED,
            refreshStrategy = RefreshStrategy.WEEKLY,
        ),
        SourceType.BROADCASTER to ProviderMetadata(
            id = "broadcaster",
            displayName = "Official Broadcasters",
            description = "Direct FTA satellite and broadcaster CDN feeds from official sources",
            group = ProviderGroup.OFFICIAL_BROADCASTER,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.GENERAL,
            reliabilityTier = ReliabilityTier.VERIFIED_OFFICIAL,
            acquisitionMethod = AcquisitionMethod.STATIC_ASSET,
            authModel = AuthModel.NONE,
            sourceType = SourceType.BROADCASTER,
            refreshStrategy = RefreshStrategy.WEEKLY,
        ),
        SourceType.PREMIUM to ProviderMetadata(
            id = "premium",
            displayName = "Premium TV",
            description = "HBO, Showtime, Starz, sports & more premium subscription channels",
            group = ProviderGroup.PREMIUM,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.PREMIUM_MOVIES,
            reliabilityTier = ReliabilityTier.UNVERIFIED,
            acquisitionMethod = AcquisitionMethod.WEB_SCRAPE,
            authModel = AuthModel.SUBSCRIPTION_REQUIRED,
            sourceType = SourceType.PREMIUM,
            requiresResolution = true,
            refreshStrategy = RefreshStrategy.ON_DEMAND,
        ),
        SourceType.SPORTS_EVENTS to ProviderMetadata(
            id = "sports_events",
            displayName = "Sports & Events",
            description = "Live sports, news & entertainment channels requiring stream resolution",
            group = ProviderGroup.SPORTS_EVENTS,
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
        SourceType.WORLD_TV to ProviderMetadata(
            id = "world_tv",
            displayName = "World TV",
            description = "Middle Eastern, African & international channels with search support",
            group = ProviderGroup.WORLD_TV,
            geographicScope = GeographicScope.GLOBAL,
            contentFocus = ContentFocus.INTERNATIONAL,
            reliabilityTier = ReliabilityTier.UNVERIFIED,
            acquisitionMethod = AcquisitionMethod.WEB_SCRAPE,
            authModel = AuthModel.SCRAPE_ACCESS,
            sourceType = SourceType.WORLD_TV,
            requiresResolution = true,
            overlappingTypes = setOf(SourceType.SPORTS_EVENTS),
        ),
        SourceType.RADIO to ProviderMetadata(
            id = "radio_browser",
            displayName = "Radio Browser",
            description = "Live internet radio stations from the radio-browser.info community database",
            group = ProviderGroup.AUDIO,
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
        SourceType.VERIFIED to 0,
        SourceType.BROADCASTER to 1,
        SourceType.FREE_CHANNEL to 2,
        SourceType.SPORTS_EVENTS to 3,
        SourceType.WORLD_TV to 4,
        SourceType.PREMIUM to 5,
        SourceType.FAST_TV to 6,
        SourceType.IPTV to 7,
        SourceType.FREE_TV to 8,
        SourceType.RADIO to 9,
    )
}
