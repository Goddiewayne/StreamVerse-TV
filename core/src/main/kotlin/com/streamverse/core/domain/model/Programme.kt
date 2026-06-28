package com.streamverse.core.domain.model

data class Programme(
    val title: String,
    val synopsis: String? = null,
    val category: String? = null,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val artworkUrl: String? = null,
    val episodeTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val ageRating: String? = null,
    val isLive: Boolean = false,
    val isPremiere: Boolean = false,
) {
    val durationMillis: Long get() = endTimeMillis - startTimeMillis
    val elapsedMillis: Long get() = (System.currentTimeMillis() - startTimeMillis).coerceAtLeast(0)
    val remainingMillis: Long get() = (endTimeMillis - System.currentTimeMillis()).coerceAtLeast(0)
    val progressFraction: Float get() = if (durationMillis > 0)
        (elapsedMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f
    val isCurrentlyAiring: Boolean get() =
        System.currentTimeMillis() in startTimeMillis until endTimeMillis
}

data class ChannelProgramme(
    val channel: Channel,
    val currentProgramme: Programme? = null,
    val nextProgramme: Programme? = null,
)

data class LiveEvent(
    val id: String,
    val title: String,
    val synopsis: String? = null,
    val eventType: EventType,
    val status: EventStatus,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val artworkUrl: String? = null,
    val broadcasterLogoUrl: String? = null,
    val channelIds: List<String> = emptyList(),
    val score: String? = null,
    val competition: String? = null,
) {
    val isLive: Boolean get() = status == EventStatus.LIVE
    val isUpcoming: Boolean get() = status == EventStatus.UPCOMING
    val timeUntilStartMillis: Long get() = (startTimeMillis - System.currentTimeMillis()).coerceAtLeast(0)
}

enum class EventType {
    FOOTBALL, BASKETBALL, FORMULA1, CRICKET, TENNIS,
    CONCERT, BREAKING_NEWS, AWARDS, POLITICS, RELIGIOUS,
    ESPORTS, GENERAL,
}

enum class EventStatus { LIVE, UPCOMING, FINISHED }

data class EpgEntry(
    val programme: Programme,
    val channelIds: List<String> = emptyList(),
    val isNow: Boolean = false,
    val isNext: Boolean = false,
)

data class TrendingChannel(
    val channel: Channel,
    val rank: Int,
    val reason: String,
    val programme: Programme? = null,
    val viewCount: Int = 0,
)

data class HomeSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val type: SectionType,
    val channels: List<Channel>,
    val events: List<LiveEvent> = emptyList(),
    val programmes: List<ChannelProgramme> = emptyList(),
)

enum class SectionType {
    HERO, CONTINUE_WATCHING, FEATURED_EVENTS, CATEGORY,
    WHATS_ON_NOW, MINI_GUIDE, TRENDING, NEWS_CENTRE,
    SPORTS_CENTRE, REGIONAL_HUB, FAVOURITES, RECOMMENDATIONS,
    KIDS, MUSIC, RECENTLY_ADDED,
}

data class TimeOfDay(
    val period: DayPeriod,
    val label: String,
    val accentColor: Long,
)

enum class DayPeriod { MORNING, AFTERNOON, EVENING, LATE_NIGHT }

data class NewsHeadline(
    val id: String,
    val title: String,
    val summary: String? = null,
    val source: String? = null,
    val timestamp: Long,
    val category: String? = null,
    val channelId: String? = null,
    val isBreaking: Boolean = false,
)

data class LiveScore(
    val eventId: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val status: String,
    val minute: String? = null,
    val competition: String,
    val channelId: String? = null,
)
