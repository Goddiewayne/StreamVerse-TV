package com.streamverse.core.data

import android.content.Context
import com.streamverse.core.data.source.provider.ProviderGroup
import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-facing channel source providers that can be toggled on/off in Settings.
 * Maps to one or more [SourceType]s in the repository.
 *
 * Each provider belongs to a [ProviderGroup] that drives UI organisation.
 */
enum class SourceProvider(
    val displayName: String,
    val description: String,
    val group: ProviderGroup,
) {
    // ── Official & Verified ────────────────────────────────────────────────
    VERIFIED(
        "Verified Channels",
        "Hand-picked channels independently verified to work",
        ProviderGroup.VERIFIED_CURATED,
    ),
    BROADCASTER(
        "Official Broadcasters",
        "Direct broadcaster & satellite feeds from official CDNs",
        ProviderGroup.OFFICIAL_BROADCASTER,
    ),

    // ── Global Aggregators ─────────────────────────────────────────────────
    IPTV(
        "Global Channels",
        "10,000+ live channels from the iptv-org community index",
        ProviderGroup.GLOBAL_AGGREGATOR,
    ),
    FREE_TV(
        "Free-to-Air TV",
        "Curated HD broadcast channels from Free-TV/IPTV",
        ProviderGroup.GLOBAL_AGGREGATOR,
    ),
    FAST_TV(
        "Regional Live TV",
        "Direct live streams from iptv-org supplemental playlists",
        ProviderGroup.GLOBAL_AGGREGATOR,
    ),

    // ── Free Streaming Services ────────────────────────────────────────────
    FREE_CHANNEL(
        "Free Streaming Services",
        "Pluto TV, Plex, Roku, Tubi, Xumo & Distro TV direct CDN playlists",
        ProviderGroup.FAST_SERVICE,
    ),

    // ── Sports & Events ────────────────────────────────────────────────────
    SPORTS_EVENTS(
        "Sports & Events",
        "Live sports, news & entertainment channels",
        ProviderGroup.SPORTS_EVENTS,
    ),

    // ── World TV ───────────────────────────────────────────────────────────
    WORLD_TV(
        "World TV",
        "Middle Eastern, African & international channels",
        ProviderGroup.WORLD_TV,
    ),

    // ── Premium ────────────────────────────────────────────────────────────
    PREMIUM(
        "Premium TV",
        "HBO, Showtime, Starz, sports & more premium channels",
        ProviderGroup.PREMIUM,
    ),

    // ── Audio ──────────────────────────────────────────────────────────────
    RADIO(
        "Radio",
        "Live internet radio stations from around the world",
        ProviderGroup.AUDIO,
    ),

    // ── Deprecated aliases (backward compat for shared prefs keys) ─────────
    @Deprecated("Use SPORTS_EVENTS", ReplaceWith("SPORTS_EVENTS"))
    DLHD("Live Sports & TV", "Deprecated — use Sports & Events", ProviderGroup.SPORTS_EVENTS),
    @Deprecated("Use WORLD_TV", ReplaceWith("WORLD_TV"))
    STMIFY("World TV", "Deprecated — use World TV", ProviderGroup.WORLD_TV),
    @Deprecated("Use VERIFIED", ReplaceWith("VERIFIED"))
    INDEPENDENT("Featured", "Deprecated — use Verified Channels", ProviderGroup.VERIFIED_CURATED),
    ;

    companion object {
        /** Maps old deprecated names to their canonical replacement. */
        val canonical: Map<SourceProvider, SourceProvider> = mapOf(
            DLHD to SPORTS_EVENTS,
            STMIFY to WORLD_TV,
            INDEPENDENT to VERIFIED,
        )

        fun canonicalOf(provider: SourceProvider): SourceProvider =
            canonical[provider] ?: provider

        /** Resolves a [SourceType] to the user-facing [SourceProvider]. */
        fun forType(type: SourceType): SourceProvider {
            val canonicalType = SourceType.canonicalOf(type)
            return forCanonicalType(canonicalType)
        }

        private fun forCanonicalType(type: SourceType): SourceProvider = when (type) {
            SourceType.VERIFIED -> VERIFIED
            SourceType.SPORTS_EVENTS -> SPORTS_EVENTS
            SourceType.WORLD_TV -> WORLD_TV
            SourceType.IPTV -> IPTV
            SourceType.FREE_TV -> FREE_TV
            SourceType.FAST_TV -> FAST_TV
            SourceType.FREE_CHANNEL -> FREE_CHANNEL
            SourceType.PREMIUM -> PREMIUM
            SourceType.BROADCASTER -> BROADCASTER
            SourceType.RADIO -> RADIO
            // Keep the compiler happy — deprecated values still exist
            SourceType.INDEPENDENT -> VERIFIED
            SourceType.DLHD -> SPORTS_EVENTS
            SourceType.STMIFY_FREE -> WORLD_TV
            SourceType.STMIFY_PREMIUM -> WORLD_TV
        }
    }
}

/** Number of distinct user-facing providers a channel can be watched from. */
fun Channel.sourceProviderCount(): Int =
    sources.keys.mapTo(HashSet()) { SourceProvider.forType(it) }.size

/**
 * Persists which [SourceProvider]s are enabled. All sources default to enabled.
 * Handles migration from deprecated provider names to canonical ones.
 */
@Singleton
class SourcePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("sv_sources", Context.MODE_PRIVATE)

    private val _enabledFlow = MutableStateFlow(readAll())
    val enabledFlow: StateFlow<Map<SourceProvider, Boolean>> = _enabledFlow.asStateFlow()

    fun isEnabled(provider: SourceProvider): Boolean {
        val canonical = SourceProvider.canonicalOf(provider)
        // Check canonical key first, then fall back to deprecated key for migration
        return if (prefs.contains(canonical.name)) {
            prefs.getBoolean(canonical.name, true)
        } else {
            val deprecated = SourceProvider.canonical.entries.firstOrNull { it.value == canonical }?.key
            if (deprecated != null && prefs.contains(deprecated.name)) {
                prefs.getBoolean(deprecated.name, true)
            } else {
                true
            }
        }
    }

    fun setEnabled(provider: SourceProvider, enabled: Boolean) {
        val canonical = SourceProvider.canonicalOf(provider)
        prefs.edit().putBoolean(canonical.name, enabled).apply()
        _enabledFlow.value = readAll()
    }

    /** Returns the currently enabled map, using only canonical providers. */
    fun enabled(): Map<SourceProvider, Boolean> = _enabledFlow.value

    /** Migrates any old deprecated SharedPreferences keys to the new canonical key. */
    fun migrate() {
        SourceProvider.canonical.forEach { (deprecated, canonical) ->
            if (prefs.contains(deprecated.name) && !prefs.contains(canonical.name)) {
                val value = prefs.getBoolean(deprecated.name, true)
                prefs.edit().putBoolean(canonical.name, value).apply()
            }
        }
    }

    private fun readAll(): Map<SourceProvider, Boolean> {
        migrate()
        val result = mutableMapOf<SourceProvider, Boolean>()
        // Canonical providers first
        for (provider in SourceProvider.entries.filter { it !in SourceProvider.canonical.keys }) {
            result[provider] = isEnabled(provider)
        }
        // Deprecated aliases mirror their canonical value
        for ((deprecated, canonical) in SourceProvider.canonical) {
            result[deprecated] = result[canonical] ?: true
        }
        return result
    }
}
