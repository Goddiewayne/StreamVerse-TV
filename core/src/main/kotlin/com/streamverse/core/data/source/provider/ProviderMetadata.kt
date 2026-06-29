package com.streamverse.core.data.source.provider

import com.streamverse.core.domain.model.SourceType

enum class GeographicScope {
    GLOBAL,
    MULTI_REGION,
    REGION_SPECIFIC,
    COUNTRY_SPECIFIC,
}

enum class ContentFocus {
    GENERAL,
    SPORTS,
    NEWS,
    ENTERTAINMENT,
    INTERNATIONAL,
    PREMIUM_MOVIES,
    RADIO_AUDIO,
}

enum class ReliabilityTier {
    VERIFIED_OFFICIAL,
    VERIFIED_COMMUNITY,
    UNVERIFIED,
}

enum class AcquisitionMethod {
    M3U_PLAYLIST,
    WEB_SCRAPE,
    REST_API,
    STATIC_ASSET,
}

enum class AuthModel {
    NONE,
    SCRAPE_ACCESS,
    SUBSCRIPTION_REQUIRED,
}

enum class RefreshStrategy {
    DAILY,
    WEEKLY,
    ON_DEMAND,
}

/**
 * Rich structured metadata for a single source provider.
 * Every [SourceType] maps to exactly one [ProviderMetadata].
 */
data class ProviderMetadata(
    val id: String,
    val displayName: String,
    val description: String,
    val group: ProviderGroup,
    val geographicScope: GeographicScope,
    val contentFocus: ContentFocus,
    val reliabilityTier: ReliabilityTier,
    val acquisitionMethod: AcquisitionMethod,
    val authModel: AuthModel,
    val sourceType: SourceType,
    val requiresResolution: Boolean = false,
    val epgSupported: Boolean = false,
    val refreshStrategy: RefreshStrategy = RefreshStrategy.DAILY,
    val isDefaultEnabled: Boolean = true,
    val overlappingTypes: Set<SourceType> = emptySet(),
)
