package com.streamverse.core.data.source.provider

enum class ProviderGroup(
    val displayName: String,
    val description: String,
    val sortOrder: Int,
) {
    OFFICIAL_BROADCASTER(
        "Official Broadcasters",
        "Direct feeds from official broadcaster CDNs — highest reliability",
        0,
    ),
    VERIFIED_CURATED(
        "Verified Channels",
        "Hand-picked channels independently verified to work",
        1,
    ),
    GLOBAL_AGGREGATOR(
        "Global Aggregators",
        "Large community-maintained indexes of thousands of channels",
        2,
    ),
    FAST_SERVICE(
        "Free Streaming Services",
        "Free ad-supported TV from Pluto, Plex, Roku, Tubi, Xumo & more",
        3,
    ),
    SPORTS_EVENTS(
        "Sports & Events",
        "Live sports and event coverage requiring dynamic stream resolution",
        4,
    ),
    WORLD_TV(
        "World TV",
        "International channels, Middle Eastern, African & diaspora programming",
        5,
    ),
    PREMIUM(
        "Premium",
        "Premium subscription channels (HBO, Showtime, Starz & more)",
        6,
    ),
    AUDIO(
        "Radio",
        "Internet radio stations from around the world",
        7,
    ),
}
