package com.streamverse.core.data.repository

import com.streamverse.core.data.WatchHistoryPreferences
import com.streamverse.core.domain.model.*
import com.streamverse.core.util.CategoryNormalizer
import kotlinx.coroutines.flow.first
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.pow

data class HomeRankingContext(
    val baseContext: RankingContext,
    val period: DayPeriod,
    val recentlyWatchedSet: Set<String> = emptySet(),
    val favouriteIds: Set<String> = emptySet(),
    val editorialBoostIds: Set<String> = emptySet(),
    val rotationExcludeIds: Set<String> = emptySet(),
    val recentlyWatchedTimestamps: Map<String, Long> = emptyMap(),
    val isWeekend: Boolean = false,
    val season: String = "none",
)

@Singleton
class HomeRankingEngine @Inject constructor(
    private val rankingEngine: ChannelRankingEngine,
    private val watchHistory: WatchHistoryPreferences,
) {
    fun rankForHome(channel: Channel, ctx: HomeRankingContext): Float {
        val baseScore = rankingEngine.rankChannel(channel, ctx.baseContext)
        var s = baseScore
        s += timeOfDayScore(channel, ctx.period)
        s += dayOfWeekScore(channel, ctx.isWeekend)
        s += seasonalScore(channel, ctx.season)
        s += engagementDecayScore(channel, ctx.recentlyWatchedTimestamps)
        s += editorialBoostScore(channel, ctx.editorialBoostIds)
        s += rotationPenalty(channel, ctx.rotationExcludeIds)
        return s
    }

    fun rankSections(sections: List<HomeSection>, timeOfDay: DayPeriod): List<HomeSection> {
        val priority = when (timeOfDay) {
            DayPeriod.MORNING -> listOf(
                SectionType.CONTINUE_WATCHING, SectionType.LIVE_EVENTS,
                SectionType.TOP_NEWS, SectionType.EDITOR_PICKS,
                SectionType.TRENDING, SectionType.POPULAR_IN_REGION,
                SectionType.BECAUSE_YOU_WATCH, SectionType.RECOMMENDATIONS,
                SectionType.TOP_ENTERTAINMENT, SectionType.TOP_SPORTS,
                SectionType.FAVOURITES, SectionType.POPULAR_WORLDWIDE,
                SectionType.TOP_MOVIES, SectionType.TOP_DOCUMENTARIES,
                SectionType.KIDS, SectionType.MUSIC,
                SectionType.RECENTLY_ADDED, SectionType.CATEGORY_BROWSING,
            )
            DayPeriod.AFTERNOON -> listOf(
                SectionType.CONTINUE_WATCHING, SectionType.LIVE_EVENTS,
                SectionType.TOP_ENTERTAINMENT, SectionType.TOP_SPORTS,
                SectionType.TOP_MOVIES, SectionType.POPULAR_IN_REGION,
                SectionType.EDITOR_PICKS, SectionType.TRENDING,
                SectionType.BECAUSE_YOU_WATCH, SectionType.POPULAR_WORLDWIDE,
                SectionType.TOP_NEWS, SectionType.FAVOURITES,
                SectionType.TOP_DOCUMENTARIES, SectionType.MUSIC,
                SectionType.KIDS, SectionType.RECENTLY_ADDED,
                SectionType.RECOMMENDATIONS, SectionType.CATEGORY_BROWSING,
            )
            DayPeriod.EVENING -> listOf(
                SectionType.CONTINUE_WATCHING, SectionType.LIVE_EVENTS,
                SectionType.TRENDING, SectionType.EDITOR_PICKS,
                SectionType.TOP_MOVIES, SectionType.TOP_SPORTS,
                SectionType.TOP_ENTERTAINMENT, SectionType.POPULAR_IN_REGION,
                SectionType.TOP_NEWS, SectionType.BECAUSE_YOU_WATCH,
                SectionType.POPULAR_WORLDWIDE, SectionType.FAVOURITES,
                SectionType.TOP_DOCUMENTARIES, SectionType.KIDS,
                SectionType.MUSIC, SectionType.RECENTLY_ADDED,
                SectionType.RECOMMENDATIONS, SectionType.CATEGORY_BROWSING,
            )
            DayPeriod.LATE_NIGHT -> listOf(
                SectionType.CONTINUE_WATCHING, SectionType.TOP_NEWS,
                SectionType.TOP_MOVIES, SectionType.LIVE_EVENTS,
                SectionType.TOP_DOCUMENTARIES, SectionType.MUSIC,
                SectionType.TRENDING, SectionType.EDITOR_PICKS,
                SectionType.POPULAR_IN_REGION, SectionType.POPULAR_WORLDWIDE,
                SectionType.FAVOURITES, SectionType.RECOMMENDATIONS,
                SectionType.BECAUSE_YOU_WATCH, SectionType.TOP_SPORTS,
                SectionType.TOP_ENTERTAINMENT, SectionType.RECENTLY_ADDED,
                SectionType.KIDS, SectionType.CATEGORY_BROWSING,
            )
        }
        return sections.sortedBy { s -> priority.indexOf(s.type).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    fun selectFeatured(
        channels: List<Channel>,
        liveIds: Set<String>,
        ctx: HomeRankingContext,
        limit: Int = 10,
        recentFeaturedIds: Set<String> = emptySet(),
    ): List<Channel> {
        val eligible = channels.filter { ch ->
            ch.sources.keys.none { it == SourceType.RADIO } && ch.logoUrl != null && ch.id in liveIds
        }
        val ctxWithRotation = ctx.copy(rotationExcludeIds = ctx.rotationExcludeIds + recentFeaturedIds)
        val ranked = eligible.sortedByDescending { rankForHome(it, ctxWithRotation) }
        val picks = mutableListOf<Channel>()
        val seenCountries = mutableSetOf<String?>()
        for (ch in ranked) {
            if (picks.size >= limit) break
            if (ch.country in seenCountries && picks.size >= 4) continue
            picks.add(ch)
            seenCountries.add(ch.country)
        }
        return picks
    }

    suspend fun buildHomeContext(): HomeRankingContext {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
        val period = when (hour) {
            in 5..11 -> DayPeriod.MORNING
            in 12..16 -> DayPeriod.AFTERNOON
            in 17..22 -> DayPeriod.EVENING
            else -> DayPeriod.LATE_NIGHT
        }
        val month = now.get(Calendar.MONTH)
        val season = when (month) {
            in 0..1 -> "winter_sports"
            in 2..3 -> "spring"
            in 4..5 -> "summer"
            in 6..7 -> "summer_sports"
            in 8..9 -> "autumn"
            in 10..11 -> "winter"
            else -> "none"
        }
        val history = watchHistory.recent.first().map { it.id to System.currentTimeMillis() }.toMap()
        return HomeRankingContext(
            baseContext = RankingContext(
                userRegion = com.streamverse.core.util.RegionProvider.getRegionCode(),
                recentlyWatchedIds = history.keys,
                favoriteIds = emptySet(),
            ),
            period = period,
            recentlyWatchedSet = history.keys,
            recentlyWatchedTimestamps = history,
            isWeekend = isWeekend,
            season = season,
        )
    }

    private fun timeOfDayScore(channel: Channel, period: DayPeriod): Float {
        val cat = channel.category ?: return 0f
        return when (period) {
            DayPeriod.MORNING -> when {
                cat == CategoryNormalizer.C.NEWS -> MAX_TIME_OF_DAY
                cat == CategoryNormalizer.C.KIDS -> MAX_TIME_OF_DAY * 0.8f
                cat == CategoryNormalizer.C.LIFESTYLE -> MAX_TIME_OF_DAY * 0.6f
                cat == CategoryNormalizer.C.MUSIC -> MAX_TIME_OF_DAY * 0.3f
                else -> 0f
            }
            DayPeriod.AFTERNOON -> when {
                cat == CategoryNormalizer.C.ENTERTAINMENT -> MAX_TIME_OF_DAY * 0.8f
                cat == CategoryNormalizer.C.MUSIC -> MAX_TIME_OF_DAY * 0.7f
                cat == CategoryNormalizer.C.LIFESTYLE -> MAX_TIME_OF_DAY * 0.6f
                cat == CategoryNormalizer.C.MOVIES -> MAX_TIME_OF_DAY * 0.4f
                cat == CategoryNormalizer.C.SPORTS -> MAX_TIME_OF_DAY * 0.4f
                else -> 0f
            }
            DayPeriod.EVENING -> when {
                cat == CategoryNormalizer.C.SPORTS -> MAX_TIME_OF_DAY
                cat == CategoryNormalizer.C.MOVIES -> MAX_TIME_OF_DAY * 0.9f
                cat == CategoryNormalizer.C.ENTERTAINMENT -> MAX_TIME_OF_DAY * 0.8f
                cat == CategoryNormalizer.C.NEWS -> MAX_TIME_OF_DAY * 0.6f
                cat == CategoryNormalizer.C.DOCUMENTARY -> MAX_TIME_OF_DAY * 0.5f
                else -> 0f
            }
            DayPeriod.LATE_NIGHT -> when {
                cat == CategoryNormalizer.C.NEWS -> MAX_TIME_OF_DAY * 0.7f
                cat == CategoryNormalizer.C.DOCUMENTARY -> MAX_TIME_OF_DAY * 0.8f
                cat == CategoryNormalizer.C.MUSIC -> MAX_TIME_OF_DAY * 0.7f
                cat == CategoryNormalizer.C.MOVIES -> MAX_TIME_OF_DAY * 0.6f
                cat == CategoryNormalizer.C.SCIENCE -> MAX_TIME_OF_DAY * 0.6f
                else -> 0f
            }
        }
    }

    private fun dayOfWeekScore(channel: Channel, isWeekend: Boolean): Float {
        val cat = channel.category ?: return 0f
        if (!isWeekend) return 0f
        return when {
            cat == CategoryNormalizer.C.SPORTS -> MAX_DAY_OF_WEEK
            cat == CategoryNormalizer.C.MOVIES -> MAX_DAY_OF_WEEK * 0.8f
            cat == CategoryNormalizer.C.ENTERTAINMENT -> MAX_DAY_OF_WEEK * 0.7f
            cat == CategoryNormalizer.C.KIDS -> MAX_DAY_OF_WEEK * 0.6f
            else -> MAX_DAY_OF_WEEK * 0.2f
        }
    }

    private fun seasonalScore(channel: Channel, season: String): Float {
        val cat = channel.category ?: return 0f
        return when (season) {
            "winter_sports" -> if (cat == CategoryNormalizer.C.SPORTS) MAX_SEASONAL else 0f
            "summer_sports" -> if (cat == CategoryNormalizer.C.SPORTS) MAX_SEASONAL * 0.8f else 0f
            "summer" -> when {
                cat == CategoryNormalizer.C.MOVIES -> MAX_SEASONAL * 0.5f
                cat == CategoryNormalizer.C.ENTERTAINMENT -> MAX_SEASONAL * 0.4f
                cat == CategoryNormalizer.C.KIDS -> MAX_SEASONAL * 0.6f
                else -> 0f
            }
            "winter" -> when {
                cat == CategoryNormalizer.C.MOVIES -> MAX_SEASONAL * 0.6f
                cat == CategoryNormalizer.C.DOCUMENTARY -> MAX_SEASONAL * 0.4f
                else -> 0f
            }
            else -> 0f
        }
    }

    private fun engagementDecayScore(channel: Channel, timestamps: Map<String, Long>): Float {
        val ts = timestamps[channel.id] ?: return 0f
        val hoursAgo = (System.currentTimeMillis() - ts) / 3600_000f
        if (hoursAgo < 0) return MAX_ENGAGEMENT_DECAY
        val decay = MAX_ENGAGEMENT_DECAY * (1f / (1f + (hoursAgo / 24f).pow(1.5f)))
        return max(0f, decay)
    }

    private fun editorialBoostScore(channel: Channel, boostIds: Set<String>): Float {
        return if (channel.id in boostIds) MAX_EDITORIAL else 0f
    }

    private fun rotationPenalty(channel: Channel, excludeIds: Set<String>): Float {
        return if (channel.id in excludeIds) -MAX_ROTATION_PENALTY else 0f
    }

    companion object {
        const val MAX_TIME_OF_DAY = 15f
        const val MAX_DAY_OF_WEEK = 10f
        const val MAX_SEASONAL = 10f
        const val MAX_ENGAGEMENT_DECAY = 8f
        const val MAX_EDITORIAL = 20f
        const val MAX_ROTATION_PENALTY = 25f
    }
}
