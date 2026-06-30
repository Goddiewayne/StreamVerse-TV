package com.streamverse.core.data.repository

import com.streamverse.core.domain.model.*
import com.streamverse.core.util.CategoryNormalizer
import javax.inject.Inject
import javax.inject.Singleton

data class FeaturedItem(
    val channel: Channel,
    val description: String? = null,
    val badge: String? = null,
)

@Singleton
class FeaturedSelector @Inject constructor(
    private val rankingEngine: HomeRankingEngine,
) {
    private val rotationHistory = mutableListOf<String>()

    fun select(
        channels: List<Channel>,
        liveIds: Set<String>,
        ctx: HomeRankingContext,
        limit: Int = 10,
        liveEvents: List<LiveEvent> = emptyList(),
    ): List<FeaturedItem> {
        val result = mutableListOf<FeaturedItem>()
        val usedIds = mutableSetOf<String>()
        val slotsPerCategory = maxOf(1, limit / 5)
        val recentFeatured = rotationHistory.takeLast(20).toSet()

        // 1. Editorial picks (2-3 slots)
        val editorial = rankingEngine.selectFeatured(
            channels, liveIds, ctx,
            limit = slotsPerCategory,
            recentFeaturedIds = recentFeatured,
        )
        for (ch in editorial) {
            if (result.size >= limit) break
            if (usedIds.add(ch.id)) {
                result.add(FeaturedItem(ch, description = ch.description, badge = "Editor's Pick"))
            }
        }

        // 2. Regional channels (2-3 slots) — replaces old "Regional TV" row
        val userRegion = com.streamverse.core.util.RegionProvider.getRegionCode()
        val regional = channels.filter {
            it.id in liveIds && it.logoUrl != null &&
            it.country.equals(userRegion, ignoreCase = true) &&
            it.id !in usedIds && it.id !in recentFeatured
        }
        for (ch in regional.take(slotsPerCategory)) {
            if (result.size >= limit) break
            if (usedIds.add(ch.id)) {
                result.add(FeaturedItem(ch, badge = "Regional"))
            }
        }

        // 3. Trending/live events (1-2 slots)
        val liveEventChs = liveEvents.take(4).mapNotNull { e ->
            e.channelIds.firstOrNull()?.let { cid ->
                channels.firstOrNull { it.id == cid }
            }
        }.filter { it != null && it.id in liveIds && it.logoUrl != null && it.id !in usedIds && it.id !in recentFeatured }
        for (ch in liveEventChs) {
            if (result.size >= limit) break
            if (usedIds.add(ch.id)) {
                result.add(FeaturedItem(ch, badge = "Live Event"))
            }
        }

        // 4. Personalized (recently watched + favorites, 1-2 slots)
        val personalized = channels.filter {
            it.id in liveIds && it.logoUrl != null &&
            (it.id in ctx.recentlyWatchedSet || it.id in ctx.favouriteIds) &&
            it.id !in usedIds && it.id !in recentFeatured
        }.sortedByDescending { rankingEngine.rankForHome(it, ctx) }
        for (ch in personalized.take(slotsPerCategory)) {
            if (result.size >= limit) break
            if (usedIds.add(ch.id)) {
                result.add(FeaturedItem(ch, badge = if (ch.id in ctx.favouriteIds) "Favorite" else "Watch Again"))
            }
        }

        // 5. Top ranked remaining filler
        val remaining = channels.filter {
            it.id in liveIds && it.logoUrl != null && it.id !in usedIds &&
            it.sources.keys.none { s -> s == SourceType.RADIO }
        }
        val rankedRemaining = remaining.sortedByDescending {
            rankingEngine.rankForHome(it, ctx.copy(rotationExcludeIds = ctx.rotationExcludeIds + recentFeatured))
        }
        for (ch in rankedRemaining) {
            if (result.size >= limit) break
            if (usedIds.add(ch.id)) {
                result.add(FeaturedItem(ch))
            }
        }

        rotationHistory.addAll(result.map { it.channel.id })
        while (rotationHistory.size > 50) {
            rotationHistory.removeAt(0)
        }

        return result
    }

    fun clearRotation() {
        rotationHistory.clear()
    }
}
