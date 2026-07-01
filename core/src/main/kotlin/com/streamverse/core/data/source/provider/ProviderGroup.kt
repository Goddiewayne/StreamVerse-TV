package com.streamverse.core.data.source.provider

/**
 * Groups source providers by how they are fetched, not by content category.
 *
 * | Group  | Fetch method              | Providers                          |
 * |--------|---------------------------|------------------------------------|
 * | Alpha  | Local asset JSON (instant)| BROADCASTER                        |
 * | Beta   | Hosted index + M3U fallback| GLOBAL_INDEX                      |
 * | Gamma  | Individual API/scrape     | FREE_CHANNEL, YOUTUBE_TV, WORLD_TV,|
 * |        |                           | SPORTS_EVENTS, RADIO               |
 */
enum class ProviderGroup(
    val displayName: String,
    val description: String,
    val sortOrder: Int,
) {
    ALPHA(
        "Alpha — Local Assets",
        "Instant, low-latency sources loaded from bundled assets — no network required",
        0,
    ),
    BETA(
        "Beta — Aggregated Index",
        "Primary channel index fetched from hosted index with M3U fallback",
        1,
    ),
    GAMMA(
        "Gamma — API Sources",
        "Individual streaming services fetched via REST APIs or web scraping",
        2,
    ),
}
