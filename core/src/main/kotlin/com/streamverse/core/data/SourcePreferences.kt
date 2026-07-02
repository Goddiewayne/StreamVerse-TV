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
 * Maps to a [SourceType] in the repository.
 *
 * Each provider belongs to a [ProviderGroup] that drives UI organisation.
 */
enum class SourceProvider(
    val displayName: String,
    val description: String,
    val group: ProviderGroup,
) {
    // ── Alpha: Local Assets (instant, no network) ─────────────────────────
    BROADCASTER(
        "Official Broadcasters",
        "Direct broadcaster & satellite feeds from official CDNs",
        ProviderGroup.ALPHA,
    ),

    // ── Beta: Aggregated Index (hosted index + M3U fallback) ──────────────
    GLOBAL_INDEX(
        "Global Channels",
        "10,000+ live channels from iptv-org and community M3U playlists",
        ProviderGroup.BETA,
    ),

    // ── Gamma: API Sources (individual API/scrape clients) ────────────────
    FREE_CHANNEL(
        "Free Streaming Services",
        "Free ad-supported streaming TV (FAST) channels from leading platforms",
        ProviderGroup.GAMMA,
    ),
    YOUTUBE_TV(
        "YouTube TV",
        "Live TV channels streaming on YouTube, prioritized by your region",
        ProviderGroup.GAMMA,
    ),
    SPORTS_EVENTS(
        "Sports & Events",
        "Live sports, news & entertainment channels",
        ProviderGroup.GAMMA,
    ),
    WORLD_TV(
        "World TV",
        "Middle Eastern, African & international channels",
        ProviderGroup.GAMMA,
    ),
    RADIO(
        "Radio",
        "Live internet radio stations from around the world",
        ProviderGroup.GAMMA,
    ),
    ;

    companion object {
        /** Resolves a [SourceType] to the user-facing [SourceProvider]. */
        fun forType(type: SourceType): SourceProvider = when (type) {
            SourceType.GLOBAL_INDEX -> GLOBAL_INDEX
            SourceType.BROADCASTER -> BROADCASTER
            SourceType.FREE_CHANNEL -> FREE_CHANNEL
            SourceType.SPORTS_EVENTS -> SPORTS_EVENTS
            SourceType.WORLD_TV -> WORLD_TV
            SourceType.YOUTUBE_TV -> YOUTUBE_TV
            SourceType.RADIO -> RADIO
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

    private val _priorityOrderFlow = MutableStateFlow(readPriorityOrder())
    val priorityOrderFlow: StateFlow<List<SourceProvider>> = _priorityOrderFlow.asStateFlow()

    private val _dataSaverFlow = MutableStateFlow(prefs.getBoolean("data_saver", false))
    val dataSaverFlow: StateFlow<Boolean> = _dataSaverFlow.asStateFlow()

    fun isDataSaverEnabled(): Boolean = _dataSaverFlow.value

    fun setDataSaverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("data_saver", enabled).apply()
        _dataSaverFlow.value = enabled
    }

    fun priorityOrder(): List<SourceProvider> = _priorityOrderFlow.value

    fun isEnabled(provider: SourceProvider): Boolean =
        _enabledFlow.value[provider] ?: true

    fun setEnabled(provider: SourceProvider, enabled: Boolean) {
        prefs.edit().putBoolean(provider.name, enabled).apply()
        _enabledFlow.value = readAll()
    }

    /** Returns the currently enabled map, using only canonical providers. */
    fun enabled(): Map<SourceProvider, Boolean> = _enabledFlow.value

    /** Persist the user's custom provider priority order. Empty list = use default. */
    fun setPriorityOrder(order: List<SourceProvider>) {
        val key = "priority_order"
        if (order.isEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, order.joinToString(",") { it.name }).apply()
        }
        _priorityOrderFlow.value = order
    }

    private fun readPriorityOrder(): List<SourceProvider> {
        val raw = prefs.getString("priority_order", null) ?: return emptyList()
        return raw.split(",").mapNotNull { name ->
            try { SourceProvider.valueOf(name) } catch (_: IllegalArgumentException) { null }
        }
    }

    private fun readAll(): Map<SourceProvider, Boolean> {
        val result = mutableMapOf<SourceProvider, Boolean>()
        for (provider in SourceProvider.entries) {
            result[provider] = prefs.getBoolean(provider.name, true)
        }
        return result
    }
}
