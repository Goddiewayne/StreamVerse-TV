package com.streamverse.core.data

import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for channel ordering across the entire platform.
 *
 * All navigation mechanisms (zap, guide, favorites, search, recently watched)
 * reference this engine so the ordering is always consistent.
 *
 * Rules:
 * 1. Channels are sorted: category (alphabetical) → display name (alphabetical)
 * 2. Multi-source channels appear exactly once (a channel's position is determined
 *    by its first source's category, regardless of how many sources it has).
 * 3. Channels whose sources are all from disabled providers are excluded.
 * 4. Wrap-around navigation is supported (off by default, enable via [wrapAround]).
 */
@Singleton
class ChannelNavigationEngine @Inject constructor(
    private val sourcePreferences: SourcePreferences,
) {
    private var _canonical: List<Channel> = emptyList()
    private var _byId: Map<String, Channel> = emptyMap()
    private var _byIndex: Map<String, Int> = emptyMap()

    /** The current canonical channel list, filtered by enabled sources. */
    val canonical: List<Channel> get() = _canonical

    /** Rebuild the canonical list from [channels]. Call whenever the catalogue changes. */
    fun rebuild(channels: List<Channel>) {
        val enabled = sourcePreferences.enabled()
        val filtered = channels.asSequence()
            .filter { ch -> ch.sources.keys.any { enabled[SourceProvider.forType(it)] != false } }
            .sortedWith(
                compareBy(
                    { it.category?.lowercase() ?: "\uffff" },
                    { it.displayName.lowercase() },
                ),
            )
            .toList()
        _canonical = filtered
        _byId = filtered.associateBy { it.id }
        _byIndex = filtered.mapIndexed { i, ch -> ch.id to i }.toMap()
    }

    val size: Int get() = _canonical.size

    fun indexOf(channelId: String): Int = _byIndex[channelId] ?: -1

    fun getById(channelId: String): Channel? = _byId[channelId]

    fun getAt(index: Int): Channel? = _canonical.getOrNull(index)

    fun nextChannel(channelId: String, wrap: Boolean = false): Channel? {
        val i = _byIndex[channelId] ?: return null
        val next = i + 1
        if (next < _canonical.size) return _canonical[next]
        return if (wrap && _canonical.isNotEmpty()) _canonical[0] else null
    }

    fun prevChannel(channelId: String, wrap: Boolean = false): Channel? {
        val i = _byIndex[channelId] ?: return null
        val prev = i - 1
        if (prev >= 0) return _canonical[prev]
        return if (wrap && _canonical.isNotEmpty()) _canonical.last() else null
    }

    /** Build a playable-only sub-list (sources resolved) for the zap dial. */
    fun buildPlayableDial(playableIds: Set<String>, centerId: String): List<Channel> {
        val center = _byIndex[centerId] ?: return _canonical.filter { it.id in playableIds }
        val sameCategory = _canonical[center].category
        val candidates = mutableListOf<Channel>()
        if (sameCategory != null) {
            candidates.addAll(_canonical.filter { it.category == sameCategory && it.id in playableIds })
        }
        if (candidates.size < 2) {
            candidates.clear()
            candidates.addAll(_canonical.filter { it.id in playableIds })
        }
        return candidates
    }
}
