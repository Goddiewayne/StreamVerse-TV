package com.streamverse.core.data.repository

import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.epg.EpgManager
import com.streamverse.core.domain.model.*
import com.streamverse.core.util.CategoryNormalizer
import com.streamverse.core.util.RegionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
import kotlin.random.Random

@Singleton
class ProgrammeRepository @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val sourcePreferences: SourcePreferences,
    private val epgManager: EpgManager,
    private val rankingEngine: ChannelRankingEngine,
) {

    private val _trending = MutableStateFlow<List<TrendingChannel>>(emptyList())
    val trending: StateFlow<List<TrendingChannel>> = _trending.asStateFlow()

    private val _liveEvents = MutableStateFlow<List<LiveEvent>>(emptyList())
    val liveEvents: StateFlow<List<LiveEvent>> = _liveEvents.asStateFlow()

    private val _headlines = MutableStateFlow<List<NewsHeadline>>(emptyList())
    val headlines: StateFlow<List<NewsHeadline>> = _headlines.asStateFlow()

    private val _liveScores = MutableStateFlow<List<LiveScore>>(emptyList())
    val liveScores: StateFlow<List<LiveScore>> = _liveScores.asStateFlow()

    private val _timeOfDay = MutableStateFlow(computeTimeOfDay())
    val timeOfDay: StateFlow<TimeOfDay> = _timeOfDay.asStateFlow()

    private val programmeCache = mutableMapOf<String, ChannelProgramme>()
    private var scheduleCache: Map<String, List<ScheduleEvent>> = emptyMap()
    private var scheduleLoaded = false

    suspend fun loadSchedule() {
        // Remote EPG data is unreliable — use fallback (category-based) generation only.
    }

    fun getProgramme(channel: Channel): ChannelProgramme {
        val cached = programmeCache[channel.id]
        if (cached != null) {
            programmeCache.remove(channel.id)
            programmeCache[channel.id] = cached
            return cached
        }
        if (programmeCache.size >= MAX_CACHE_SIZE) {
            val oldest = programmeCache.keys.first()
            programmeCache.remove(oldest)
        }
        return programmeCache.getOrPut(channel.id) {
            val dlhdIds = channel.sources.filterKeys {
                SourceType.canonicalOf(it) == SourceType.SPORTS_EVENTS
            }.values.map { it.referenceId }
            val fromSchedule = dlhdIds.firstNotNullOfOrNull { id ->
                scheduleCache[id]?.let { events ->
                    val now = System.currentTimeMillis()
                    val cal = Calendar.getInstance()
                    val today = String.format(
                        "%04d-%02d-%02d",
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH) + 1,
                        cal.get(Calendar.DAY_OF_MONTH),
                    )
                    val todaysEvents = events.filter { it.date == today }
                    if (todaysEvents.isEmpty()) return@firstNotNullOfOrNull null
                    val current = todaysEvents.firstOrNull { e ->
                        val parts = e.time.split(":")
                        if (parts.size < 2) return@firstOrNull false
                        val evtHour = parts[0].toIntOrNull() ?: return@firstOrNull false
                        val evtMin = parts[1].toIntOrNull() ?: return@firstOrNull false
                        val evtMs = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, evtMin)
                            set(Calendar.MINUTE, evtHour)
                            set(Calendar.SECOND, 0)
                        }.timeInMillis
                        val durMs = 60 * 60 * 1000L
                        now in evtMs..(evtMs + durMs)
                    }
                    val nextIdx = if (current != null) todaysEvents.indexOf(current) + 1 else 1
                    ChannelProgramme(
                        channel = channel,
                        currentProgramme = current?.let { scheduleEventToProgramme(it, true) },
                        nextProgramme = todaysEvents.getOrNull(nextIdx)
                            ?.let { scheduleEventToProgramme(it, false) },
                    )
                }
            }
            fromSchedule ?: ChannelProgramme(
                channel = channel,
                currentProgramme = generateCurrentProgramme(channel),
                nextProgramme = generateNextProgramme(channel),
            )
        }
    }

    private fun scheduleEventToProgramme(event: ScheduleEvent, isLive: Boolean): Programme {
        val parts = event.time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val startMs = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        return Programme(
            title = event.title,
            synopsis = "${event.category} programming",
            category = event.category,
            startTimeMillis = startMs,
            endTimeMillis = startMs + 60 * 60 * 1000L,
            isLive = isLive,
        )
    }

    fun refreshProgramme(channelId: String) {
        programmeCache.remove(channelId)
    }

    fun refreshAllProgrammes() {
        programmeCache.clear()
    }

    fun updateTrending(channels: List<Channel>) {
        val ctx = RankingContext(userRegion = RegionProvider.getRegionCode())
        val ranked = channels.sortedByDescending { rankingEngine.rankChannel(it, ctx) }
            .take(20).mapIndexed { i, ch ->
            TrendingChannel(
                channel = ch,
                rank = i + 1,
                reason = trendingReasons[i % trendingReasons.size],
                programme = programmeCache[ch.id]?.currentProgramme,
                viewCount = (5000 + (20 - i) * 2500) * (if (ch.id.hashCode() and 1 == 0) 1 else 2),
            )
        }
        _trending.value = ranked
    }

    fun updateLiveEvents(channels: List<Channel>) {
        val sportsChs = channels.filter { c ->
            c.category.equals(CategoryNormalizer.C.SPORTS, ignoreCase = true)
        }
        val newsChs = channels.filter { c ->
            c.category.equals(CategoryNormalizer.C.NEWS, ignoreCase = true)
        }
        val events = mutableListOf<LiveEvent>()
        val now = System.currentTimeMillis()
        val types = EventType.entries.toList()
        for (i in 0 until minOf(sportsChs.size, 6)) {
            val ch = sportsChs[i]
            val et = if (i < types.size) types[i] else EventType.GENERAL
            val start = now - Random.nextLong(15 * 60_000, 120 * 60_000)
            val end = start + Random.nextLong(60 * 60_000, 180 * 60_000)
            events.add(
                LiveEvent(
                    id = "event_sport_$i",
                    title = sportEvents[i % sportEvents.size],
                    synopsis = "Live ${et.name.lowercase().replaceFirstChar { it.uppercase() }} action",
                    eventType = et,
                    status = if (end > now) EventStatus.LIVE else if (start > now) EventStatus.UPCOMING else EventStatus.FINISHED,
                    startTimeMillis = start,
                    endTimeMillis = end,
                    broadcasterLogoUrl = ch.logoUrl,
                    channelIds = listOf(ch.id),
                    score = if (i % 2 == 0) "${1 + Random.nextInt(5)} - ${Random.nextInt(3)}" else null,
                    competition = competitions[i % competitions.size],
                )
            )
        }
        for (i in 0 until minOf(newsChs.size, 3)) {
            val ch = newsChs[i]
            val start = now - Random.nextLong(5 * 60_000, 60 * 60_000)
            val end = start + Random.nextLong(30 * 60_000, 120 * 60_000)
            events.add(
                LiveEvent(
                    id = "event_news_$i",
                    title = breakingNews[i % breakingNews.size],
                    synopsis = "Live coverage from ${ch.displayName}",
                    eventType = EventType.BREAKING_NEWS,
                    status = if (end > now) EventStatus.LIVE else EventStatus.FINISHED,
                    startTimeMillis = start,
                    endTimeMillis = end,
                    broadcasterLogoUrl = ch.logoUrl,
                    channelIds = listOf(ch.id),
                )
            )
        }
        _liveEvents.value = events
    }

    fun updateHeadlines() {
        val now = System.currentTimeMillis()
        _headlines.value = breakingNews.take(10).mapIndexed { i, title ->
            NewsHeadline(
                id = "headline_$i",
                title = title,
                summary = "Stay informed with the latest developments${if (i < 3) " — breaking story" else ""}",
                source = newsSources[i % newsSources.size],
                timestamp = now - Random.nextLong(60_000, 3600_000),
                category = headlineCategories[i % headlineCategories.size],
                isBreaking = i < 3,
            )
        }
    }

    fun updateLiveScores(channels: List<Channel>) {
        val sportsChs = channels.filter { c ->
            c.category.equals(CategoryNormalizer.C.SPORTS, ignoreCase = true)
        }
        _liveScores.value = sportsChs.take(12).mapIndexed { i, ch ->
            LiveScore(
                eventId = "score_$i",
                homeTeam = teams[i % teams.size],
                awayTeam = teams[(i + 5) % teams.size],
                homeScore = if (i % 3 != 0) Random.nextInt(5) else null,
                awayScore = if (i % 3 != 0) Random.nextInt(4) else null,
                status = scoreStatuses[i % scoreStatuses.size],
                minute = if (i % 2 == 0) "${Random.nextInt(90)}'" else null,
                competition = competitions[i % competitions.size],
                channelId = ch.id,
            )
        }
    }

    suspend fun getEpgForChannels(
        channels: List<Channel>,
        hours: Int = 6,
        skipFallback: Boolean = false,
    ): Map<String, List<EpgEntry>> {
        if (skipFallback) return emptyMap()
        return getFallbackEpg(channels, hours)
    }

    private fun getFallbackEpg(channels: List<Channel>, hours: Int = 6): Map<String, List<EpgEntry>> {
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, List<EpgEntry>>()
        for (ch in channels) {
            val entries = mutableListOf<EpgEntry>()
            var t = now - Random.nextLong(0, 120 * 60_000)
            for (i in 0 until (hours * 2)) {
                val dur = Random.nextLong(30 * 60_000, 180 * 60_000)
                val prog = Programme(
                    title = generateProgrammeTitle(ch.category),
                    synopsis = "Programme synopsis for ${ch.displayName}",
                    category = ch.category,
                    startTimeMillis = t,
                    endTimeMillis = t + dur,
                    isLive = i == 0,
                )
                val isNow = t <= now && now < t + dur
                entries.add(
                    EpgEntry(
                        programme = prog,
                        channelIds = listOf(ch.id),
                        isNow = isNow,
                        isNext = !isNow && i == 1,
                    )
                )
                t += dur
            }
            result[ch.id] = entries
        }
        return result
    }

    fun getWhatsOnNow(channels: List<Channel>): List<ChannelProgramme> {
        return channels.mapNotNull { ch ->
            val prog = getProgramme(ch)
            prog.currentProgramme?.let { prog }
        }
    }

    fun getTimeBasedChannels(channels: List<Channel>, period: DayPeriod): List<Channel> {
        val prefers = when (period) {
            DayPeriod.MORNING -> setOf(
                CategoryNormalizer.C.NEWS,
                CategoryNormalizer.C.KIDS,
                CategoryNormalizer.C.LIFESTYLE,
            )
            DayPeriod.AFTERNOON -> setOf(
                CategoryNormalizer.C.LIFESTYLE,
                CategoryNormalizer.C.ENTERTAINMENT,
                CategoryNormalizer.C.MUSIC,
            )
            DayPeriod.EVENING -> setOf(
                CategoryNormalizer.C.SPORTS,
                CategoryNormalizer.C.MOVIES,
                CategoryNormalizer.C.DOCUMENTARY,
                CategoryNormalizer.C.ENTERTAINMENT,
            )
            DayPeriod.LATE_NIGHT -> setOf(
                CategoryNormalizer.C.NEWS,
                CategoryNormalizer.C.MUSIC,
                CategoryNormalizer.C.DOCUMENTARY,
                CategoryNormalizer.C.SCIENCE,
            )
        }
        return channels.filter { it.category in prefers }
    }

    fun getRecommendedChannels(
        channels: List<Channel>,
        recentlyWatched: List<Channel>,
        favoriteChannelCategories: Set<String>,
        period: DayPeriod,
    ): List<Channel> {
        val ctx = RankingContext(
            userRegion = RegionProvider.getRegionCode(),
            recentlyWatchedIds = recentlyWatched.map { it.id }.toSet(),
        )
        val picked = mutableSetOf<String>()
        val result = mutableListOf<Channel>()
        for (ch in recentlyWatched.take(6)) {
            if (picked.add(ch.id)) result.add(ch)
        }
        val favCats = channels.filter { it.category in favoriteChannelCategories }
        val rankedFavCats = favCats.sortedByDescending { rankingEngine.rankChannel(it, ctx) }
        for (ch in rankedFavCats.take(4)) {
            if (picked.add(ch.id)) result.add(ch)
        }
        val periodChs = getTimeBasedChannels(channels, period)
        val rankedPeriodChs = periodChs.sortedByDescending { rankingEngine.rankChannel(it, ctx) }
        for (ch in rankedPeriodChs.take(6)) {
            if (picked.add(ch.id)) result.add(ch)
        }
        if (result.size < 10) {
            val rest = channels.sortedByDescending { rankingEngine.rankChannel(it, ctx) }
            for (ch in rest) {
                if (picked.add(ch.id)) result.add(ch)
                if (result.size >= 10) break
            }
        }
        return result
    }

    fun getRegionalChannels(channels: List<Channel>): Map<String, List<Channel>> {
        return channels.filter { it.country != null }
            .groupBy { it.country!! }
            .entries
            .sortedByDescending { it.value.size }
            .take(12)
            .associate { it.toPair() }
    }

    fun getGloballyPopular(channels: List<Channel>, limit: Int = 12, ctx: RankingContext? = null): List<Channel> {
        val context = ctx ?: RankingContext(userRegion = RegionProvider.getRegionCode())
        return rankingEngine.globallyPopular(channels, limit, context)
    }

    fun editorialPicks(channels: List<Channel>, limit: Int = 12, ctx: RankingContext? = null): List<Channel> {
        val context = ctx ?: RankingContext(userRegion = RegionProvider.getRegionCode())
        return rankingEngine.editorialPicks(channels, limit, context)
    }

    fun popularInRegion(channels: List<Channel>, limit: Int = 12, ctx: RankingContext? = null): List<Channel> {
        val context = ctx ?: RankingContext(userRegion = RegionProvider.getRegionCode())
        return rankingEngine.popularInRegion(channels, limit, context)
    }

    fun topByCategory(channels: List<Channel>, category: String, limit: Int = 8, ctx: RankingContext? = null): List<Channel> {
        val context = ctx ?: RankingContext(userRegion = RegionProvider.getRegionCode())
        return rankingEngine.topByCategory(channels, category, limit, context)
    }

    fun rankChannel(channel: Channel, ctx: RankingContext): Float = rankingEngine.rankChannel(channel, ctx)

    fun refreshTimeOfDay() {
        _timeOfDay.value = computeTimeOfDay()
    }

    fun getRegionLabel(country: String): String {
        return regionNames[country.uppercase()] ?: country
    }

    private fun generateCurrentProgramme(channel: Channel): Programme {
        val now = System.currentTimeMillis()
        val cat = channel.category ?: CategoryNormalizer.C.GENERAL
        val start = now - Random.nextLong(5 * 60_000, 90 * 60_000)
        val end = start + Random.nextLong(30 * 60_000, 180 * 60_000)
        val title = generateProgrammeTitle(cat)
        return Programme(
            title = title,
            synopsis = generateSynopsis(title, cat),
            category = cat,
            startTimeMillis = start,
            endTimeMillis = end,
            isLive = true,
            ageRating = if (Random.nextFloat() < 0.3f) listOf("PG", "12", "15", "18").random() else null,
        )
    }

    private fun generateNextProgramme(channel: Channel): Programme {
        val now = System.currentTimeMillis()
        val cat = channel.category ?: CategoryNormalizer.C.GENERAL
        val start = now + Random.nextLong(5 * 60_000, 60 * 60_000)
        val end = start + Random.nextLong(30 * 60_000, 120 * 60_000)
        return Programme(
            title = generateProgrammeTitle(cat),
            synopsis = "Coming up next on ${channel.displayName}",
            category = cat,
            startTimeMillis = start,
            endTimeMillis = end,
            isLive = false,
        )
    }

    private fun generateProgrammeTitle(category: String?): String {
        val cat = category ?: CategoryNormalizer.C.GENERAL
        val titles = programmeTitles[cat] ?: programmeTitles[CategoryNormalizer.C.GENERAL]!!
        return titles[Random.nextInt(titles.size)]
    }

    private fun generateSynopsis(title: String, category: String?): String {
        val synopses = synopsisMap[category] ?: genericSynopses
        val base = synopses[Random.nextInt(synopses.size)]
        return "$title — $base"
    }

    private fun computeTimeOfDay(): TimeOfDay {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> TimeOfDay(DayPeriod.MORNING, "Morning", 0xFFFF8C00)
            in 12..16 -> TimeOfDay(DayPeriod.AFTERNOON, "Afternoon", 0xFFFF6347)
            in 17..22 -> TimeOfDay(DayPeriod.EVENING, "Evening", 0xFF1E90FF)
            else -> TimeOfDay(DayPeriod.LATE_NIGHT, "Late Night", 0xFF4B0082)
        }
    }

    companion object {
        private const val MAX_CACHE_SIZE = 200

        private val trendingReasons = listOf(
            "Trending Now", "Most Watched", "Top Pick", "Popular", "Live Exclusive",
            "Editor's Choice", "Viewers Love This", "Hot", "Must Watch", "Recommended",
        )
        private val sportEvents = listOf(
            "Premier League Live", "NBA Basketball", "UEFA Champions League",
            "Formula 1 Grand Prix", "Grand Slam Tennis", "Cricket World Cup",
            "NFL Sunday Night Football", "Boxing: Championship Fight",
            "UFC Main Event", "WWE Raw",
        )
        private val competitions = listOf(
            "Premier League", "NBA", "Champions League", "Formula 1",
            "Wimbledon", "ICC World Cup", "NFL", "La Liga", "Serie A", "Bundesliga",
        )
        private val teams = listOf(
            "Manchester Utd", "Liverpool", "Arsenal", "Chelsea", "Man City",
            "Barcelona", "Real Madrid", "Bayern Munich", "PSG", "Juventus",
            "Lakers", "Warriors", "Celtics", "Bulls", "Heat",
        )
        private val scoreStatuses = listOf("Live", "Half Time", "Live", "Live", "Full Time")
        private val breakingNews = listOf(
            "Breaking News: Global Summit Underway",
            "Markets Rally on Economic Data",
            "Severe Weather Warning Issued",
            "Technology Breakthrough Announced",
            "Peace Talks Resume",
            "Major Infrastructure Project Approved",
            "Space Agency Reveals New Mission",
            "Healthcare Reform Debate Continues",
            "Climate Conference Concludes",
            "Election Results Coming In",
            "World Leaders Gather for Summit",
            "New Discovery in Medical Research",
        )
        private val newsSources = listOf(
            "BBC News", "CNN", "Sky News", "Al Jazeera", "Reuters",
            "Associated Press", "France 24", "DW News",
        )
        private val headlineCategories = listOf(
            "World", "Business", "Technology", "Politics", "Health",
            "Science", "Environment", "Sports",
        )

        private val programmeTitles = mapOf(
            CategoryNormalizer.C.NEWS to listOf(
                "World News Tonight", "The Global Report", "News at Ten",
                "Morning Briefing", "Prime News", "International Desk",
                "Politics Unpacked", "Business Today", "The Daily Briefing",
            ),
            CategoryNormalizer.C.SPORTS to listOf(
                "Live Sport Coverage", "Sports Centre", "Match of the Day",
                "The Sports Desk", "Game Day Live", "Championship Highlights",
                "Extreme Sports Showcase", "Behind the Game", "Athlete Stories",
            ),
            CategoryNormalizer.C.MOVIES to listOf(
                "Blockbuster Movie", "Cinema Classics", "Action Theatre",
                "Drama Hour", "Thriller Night", "Comedy Gold",
                "Sci-Fi Theatre", "Hollywood Hits", "Indie Spotlight",
            ),
            CategoryNormalizer.C.ENTERTAINMENT to listOf(
                "Tonight's Entertainment", "The Variety Show", "Entertainment Now",
                "Celebrity Spotlight", "Red Carpet Coverage", "The Talk Show",
                "Reality Check", "Talent Showcase", "Behind the Scenes",
            ),
            CategoryNormalizer.C.KIDS to listOf(
                "Cartoon Hour", "Kids Club", "Adventure Time",
                "Educational Fun", "Animated Tales", "Junior Science",
                "Story Time", "Creative Kids", "Animal Adventures",
            ),
            CategoryNormalizer.C.DOCUMENTARY to listOf(
                "Nature's Wonders", "History Revealed", "Science Frontiers",
                "Our Planet", "Ancient Civilisations", "Space Exploration",
                "Wildlife Chronicles", "The Natural World", "Documentary Hour",
            ),
            CategoryNormalizer.C.MUSIC to listOf(
                "Live Concert", "Music Videos Now", "The Countdown Show",
                "Artist Spotlight", "Classical Hour", "Jazz Sessions",
                "Hip Hop Live", "Rock Arena", "Electronic Beats",
            ),
            CategoryNormalizer.C.LIFESTYLE to listOf(
                "Home & Living", "Fashion Forward", "Culinary Journey",
                "Design Masters", "Wellness Hour", "Travel Diaries",
                "Food & Drink", "Garden Life", "Healthy Living",
            ),
            CategoryNormalizer.C.RELIGIOUS to listOf(
                "Faith Today", "Spiritual Hour", "Worship Service",
                "Biblical Teachings", "Interfaith Dialogue", "Sunday Service",
            ),
            CategoryNormalizer.C.SCIENCE to listOf(
                "Science Now", "Tech Revolution", "Future Frontiers",
                "Innovation Lab", "Digital World", "The Lab Report",
            ),
            CategoryNormalizer.C.BUSINESS to listOf(
                "Business Report", "Market Watch", "The Money Show",
                "Entrepreneur Hour", "Global Trade", "Finance Today",
            ),
            CategoryNormalizer.C.COMEDY to listOf(
                "Stand-up Special", "Comedy Club", "Funny Videos",
                "Late Night Comedy", "Sketch Show", "Comedy Roast",
            ),
            CategoryNormalizer.C.GENERAL to listOf(
                "Live Programming", "Today's Selection", "Channel Highlights",
                "Now Showing", "Primetime Lineup", "Special Presentation",
            ),
        )

        private val synopsisMap = mapOf(
            CategoryNormalizer.C.NEWS to listOf(
                "Comprehensive coverage of the day's top stories",
                "In-depth analysis and expert commentary",
                "Live reporting from correspondents worldwide",
                "Breaking news as it happens",
            ),
            CategoryNormalizer.C.SPORTS to listOf(
                "Live action from the world's biggest sporting events",
                "Expert analysis and commentary throughout the match",
                "Highlights and post-match coverage",
                "Exclusive interviews with athletes and managers",
            ),
            CategoryNormalizer.C.MOVIES to listOf(
                "A captivating cinematic experience",
                "Starring award-winning performances",
                "An unforgettable story unfolds",
                "Critically acclaimed masterpiece",
            ),
            CategoryNormalizer.C.DOCUMENTARY to listOf(
                "Exploring the wonders of our world",
                "Journey into the unknown",
                "Fascinating stories from around the globe",
                "Eye-opening perspectives on life",
            ),
        )
        private val genericSynopses = listOf(
            "Tune in for great live television",
            "Don't miss this exciting programme",
            "Award-winning entertainment",
            "Live broadcast from around the world",
            "Premium television at its finest",
        )

        private val regionNames = mapOf(
            "NG" to "Nigeria", "GH" to "Ghana", "KE" to "Kenya", "ZA" to "South Africa",
            "EG" to "Egypt", "MA" to "Morocco", "NG" to "Nigeria",
            "GB" to "United Kingdom", "US" to "United States", "CA" to "Canada",
            "DE" to "Germany", "FR" to "France", "IT" to "Italy", "ES" to "Spain",
            "AE" to "UAE", "SA" to "Saudi Arabia", "QA" to "Qatar",
            "IN" to "India", "JP" to "Japan", "CN" to "China", "KR" to "South Korea",
            "AU" to "Australia", "BR" to "Brazil", "AR" to "Argentina",
        )
    }
}
