package com.streamverse.core.data.repository

import com.streamverse.core.domain.model.*
import com.streamverse.core.util.CategoryNormalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeSectionGenerator @Inject constructor(
    private val rankingEngine: HomeRankingEngine,
    private val programmeRepo: ProgrammeRepository,
    private val channelRanking: ChannelRankingEngine,
) {
    fun generate(
        channels: List<Channel>,
        liveIds: Set<String>,
        recentlyWatched: List<Channel>,
        favouriteIds: Set<String>,
        ctx: HomeRankingContext,
        featured: List<FeaturedItem>,
    ): List<HomeSection> {
        val sections = mutableListOf<HomeSection>()
        val liveChs = { list: List<Channel> -> list.filter { it.id in liveIds } }
        val lc = liveChs(channels)

        // Hero banner
        sections.add(
            HomeSection(
                id = "hero_banner",
                title = "Featured",
                type = SectionType.HERO_BANNER,
                channels = featured.map { it.channel },
                programmes = featured.mapNotNull { programmeRepo.getProgramme(it.channel) },
            )
        )

        // Continue Watching
        val watched = recentlyWatched.filter { it.id in liveIds }
        if (watched.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "continue_watching",
                    title = "Continue Watching",
                    type = SectionType.CONTINUE_WATCHING,
                    channels = watched.take(20),
                )
            )
        }

        // Live Events
        val events = programmeRepo.liveEvents.value
        if (events.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "live_events",
                    title = "Live Events",
                    type = SectionType.LIVE_EVENTS,
                    events = events.take(8),
                )
            )
        }

        // Editor's Picks
        val editorialPicks = channelRanking.editorialPicks(lc, 10, ctx.baseContext)
        if (editorialPicks.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "editor_picks",
                    title = "Editor's Picks",
                    type = SectionType.EDITOR_PICKS,
                    channels = editorialPicks,
                )
            )
        }

        // Trending
        programmeRepo.updateTrending(lc)
        val trending = programmeRepo.trending.value
        if (trending.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "trending",
                    title = "Trending Live",
                    type = SectionType.TRENDING,
                    trending = trending.take(15),
                )
            )
        }

        // Popular in Region
        val regionPop = channelRanking.popularInRegion(lc, 12, ctx.baseContext)
        if (regionPop.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "popular_region",
                    title = "Popular in Your Region",
                    type = SectionType.POPULAR_IN_REGION,
                    channels = regionPop,
                )
            )
        }

        // Top News
        val newsChs = channelRanking.topByCategory(channels, CategoryNormalizer.C.NEWS, 10, ctx.baseContext)
            .let { liveChs(it) }
        if (newsChs.isNotEmpty()) {
            programmeRepo.updateHeadlines()
            sections.add(
                HomeSection(
                    id = "top_news",
                    title = "Top News",
                    type = SectionType.TOP_NEWS,
                    programmes = newsChs.map { programmeRepo.getProgramme(it) },
                    headlines = programmeRepo.headlines.value.take(5),
                )
            )
        }

        // Top Sports
        val sportsChs = channelRanking.topByCategory(channels, CategoryNormalizer.C.SPORTS, 10, ctx.baseContext)
            .let { liveChs(it) }
        if (sportsChs.isNotEmpty()) {
            programmeRepo.updateLiveScores(lc)
            sections.add(
                HomeSection(
                    id = "top_sports",
                    title = "Top Sports",
                    type = SectionType.TOP_SPORTS,
                    programmes = sportsChs.map { programmeRepo.getProgramme(it) },
                    scores = programmeRepo.liveScores.value.take(8),
                )
            )
        }

        // Because You Watch (same categories as recently watched)
        val watchedCats = recentlyWatched.mapNotNull { it.category }.toSet()
        if (watchedCats.isNotEmpty()) {
            val becauseWatch = lc.filter { it.category in watchedCats && it.id !in ctx.recentlyWatchedSet }
                .sortedByDescending { channelRanking.rankChannel(it, ctx.baseContext) }
                .take(15)
            if (becauseWatch.isNotEmpty()) {
                sections.add(
                    HomeSection(
                        id = "because_you_watch",
                        title = "Because You Watched",
                        type = SectionType.BECAUSE_YOU_WATCH,
                        channels = becauseWatch,
                    )
                )
            }
        }

        // Top Entertainment
        val entChs = channelRanking.topByCategory(channels, CategoryNormalizer.C.ENTERTAINMENT, 12, ctx.baseContext)
            .let { liveChs(it) }
        if (entChs.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "top_entertainment",
                    title = "Top Entertainment",
                    type = SectionType.TOP_ENTERTAINMENT,
                    channels = entChs,
                )
            )
        }

        // Top Movies
        val movieChs = channelRanking.topByCategory(channels, CategoryNormalizer.C.MOVIES, 12, ctx.baseContext)
            .let { liveChs(it) }
        if (movieChs.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "top_movies",
                    title = "Top Movies",
                    type = SectionType.TOP_MOVIES,
                    channels = movieChs,
                )
            )
        }

        // Top Documentaries
        val docChs = channelRanking.topByCategory(channels, CategoryNormalizer.C.DOCUMENTARY, 12, ctx.baseContext)
            .let { liveChs(it) }
        if (docChs.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "top_documentaries",
                    title = "Top Documentaries",
                    type = SectionType.TOP_DOCUMENTARIES,
                    channels = docChs,
                )
            )
        }

        // Popular Worldwide
        val global = channelRanking.globallyPopular(lc, 12, ctx.baseContext)
        if (global.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "popular_worldwide",
                    title = "Popular Worldwide",
                    type = SectionType.POPULAR_WORLDWIDE,
                    channels = global,
                )
            )
        }

        // Kids
        val kidsChs = channelRanking.topByCategory(channels, CategoryNormalizer.C.KIDS, 15, ctx.baseContext)
            .let { liveChs(it) }
        if (kidsChs.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "kids",
                    title = "Kids",
                    type = SectionType.KIDS,
                    channels = kidsChs.take(15),
                )
            )
        }

        // Music
        val musicChs = channelRanking.topByCategory(channels, CategoryNormalizer.C.MUSIC, 15, ctx.baseContext)
            .let { liveChs(it) }
        if (musicChs.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "music",
                    title = "Music",
                    type = SectionType.MUSIC,
                    channels = musicChs.take(15),
                )
            )
        }

        // Recently Added
        val recentAdded = lc.asReversed().take(20)
        if (recentAdded.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "recently_added",
                    title = "Recently Added",
                    type = SectionType.RECENTLY_ADDED,
                    channels = recentAdded,
                )
            )
        }

        // Favorites
        val favChs = channels.filter { it.id in favouriteIds && it.id in liveIds }
        if (favChs.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "favourites",
                    title = "My Favourites",
                    type = SectionType.FAVOURITES,
                    channels = favChs.take(20),
                )
            )
        }

        // Recommendations
        val recs = programmeRepo.getRecommendedChannels(lc, recentlyWatched, favouriteIds, ctx.period)
        if (recs.isNotEmpty()) {
            sections.add(
                HomeSection(
                    id = "recommendations",
                    title = "Recommended For You",
                    type = SectionType.RECOMMENDATIONS,
                    channels = recs.take(15),
                )
            )
        }

        // Category browsing — no regional TV row here, regional highlights are in Featured
        val deduped = deduplicateSections(sections, channels, ctx)
        return rankingEngine.rankSections(deduped, ctx.period)
    }

    /**
     * Removes duplicate channels across all sections so a channel won't appear in both
     * "Trending Live" and "Popular Worldwide" and "Recommended For You" simultaneously.
     *
     * Strategy:
     * - Exempt sections (Hero, Continue Watching, Live Events, Favourites) keep everything.
     * - Priority sections (Trending, Editor's Picks) keep their top picks first.
     * - Later sections filter out channels already seen in earlier sections.
     * - Category sections backfill from their own category pool to stay diverse.
     * - General sections backfill from the top-ranked fallback pool.
     */
    private fun deduplicateSections(
        sections: List<HomeSection>,
        allChannels: List<Channel>,
        ctx: HomeRankingContext,
    ): List<HomeSection> {
        val usedIds = mutableSetOf<String>()

        // Pre-compute ranked fallback pools per category for intelligent backfill
        val rankedAll = allChannels.sortedByDescending {
            channelRanking.rankChannel(it, ctx.baseContext)
        }
        val categoryPools = mapOf(
            SectionType.TOP_NEWS to rankCategory(allChannels, CategoryNormalizer.C.NEWS, ctx),
            SectionType.TOP_SPORTS to rankCategory(allChannels, CategoryNormalizer.C.SPORTS, ctx),
            SectionType.TOP_ENTERTAINMENT to rankCategory(allChannels, CategoryNormalizer.C.ENTERTAINMENT, ctx),
            SectionType.TOP_MOVIES to rankCategory(allChannels, CategoryNormalizer.C.MOVIES, ctx),
            SectionType.TOP_DOCUMENTARIES to rankCategory(allChannels, CategoryNormalizer.C.DOCUMENTARY, ctx),
            SectionType.KIDS to rankCategory(allChannels, CategoryNormalizer.C.KIDS, ctx),
            SectionType.MUSIC to rankCategory(allChannels, CategoryNormalizer.C.MUSIC, ctx),
        )

        val exemptTypes = setOf(
            SectionType.HERO_BANNER, SectionType.CONTINUE_WATCHING,
            SectionType.LIVE_EVENTS, SectionType.FAVOURITES,
        )

        return sections.map { section ->
            if (section.type in exemptTypes) {
                registerIds(section, usedIds)
                return@map section
            }

            when (section.type) {
                SectionType.TRENDING -> processTrending(section, usedIds, rankedAll)
                SectionType.TOP_NEWS, SectionType.TOP_SPORTS ->
                    processProgrammes(section, usedIds, categoryPools[section.type] ?: rankedAll)
                SectionType.RECENTLY_ADDED ->
                    processChannels(section, usedIds, rankedAll, target = 6)
                else ->
                    processChannels(section, usedIds, categoryPools[section.type] ?: rankedAll, target = 4)
            }
        }
    }

    private fun rankCategory(
        channels: List<Channel>,
        category: String,
        ctx: HomeRankingContext,
    ): List<Channel> = channels
        .filter { it.category == category }
        .sortedByDescending { channelRanking.rankChannel(it, ctx.baseContext) }

    /** Record all channel IDs in a section as used (no filtering). */
    private fun registerIds(section: HomeSection, usedIds: MutableSet<String>) {
        when {
            section.trending.isNotEmpty() -> section.trending.forEach { usedIds.add(it.channel.id) }
            section.programmes.isNotEmpty() -> section.programmes.forEach { usedIds.add(it.channel.id) }
            section.events.isNotEmpty() -> section.events.forEach { e -> e.channelIds.forEach { usedIds.add(it) } }
            else -> section.channels.forEach { usedIds.add(it.id) }
        }
    }

    /** For sections that use the standard [channels] list. */
    private fun processChannels(
        section: HomeSection,
        usedIds: MutableSet<String>,
        backfillPool: List<Channel>,
        target: Int,
    ): HomeSection {
        val keep = section.channels.filter { usedIds.add(it.id) }
        val filled = if (keep.size < target && section.channels.size >= target) {
            val needed = target - keep.size
            keep + backfillPool.filter { usedIds.add(it.id) }.take(needed)
        } else keep
        return section.copy(channels = filled)
    }

    /** For sections that use the [programmes] list (TOP_NEWS, TOP_SPORTS). */
    private fun processProgrammes(
        section: HomeSection,
        usedIds: MutableSet<String>,
        backfillPool: List<Channel>,
    ): HomeSection {
        val keepProgs = section.programmes.filter { usedIds.add(it.channel.id) }
        if (keepProgs.size < 4 && section.programmes.size >= 4) {
            val needed = 4 - keepProgs.size
            val fill = backfillPool.filter { usedIds.add(it.id) }.take(needed)
            val fillProgs = fill.map { programmeRepo.getProgramme(it) }
            return section.copy(programmes = keepProgs + fillProgs)
        }
        return section.copy(programmes = keepProgs)
    }

    /** For the TRENDING section. */
    private fun processTrending(
        section: HomeSection,
        usedIds: MutableSet<String>,
        backfillPool: List<Channel>,
    ): HomeSection {
        val keep = section.trending.filter { usedIds.add(it.channel.id) }
        if (keep.size < 6 && section.trending.size >= 6) {
            val needed = 6 - keep.size
            val nextRank = (keep.maxOfOrNull { it.rank } ?: 0) + 1
            val fill = backfillPool.filter { usedIds.add(it.id) }.take(needed)
            val fillTrending = fill.mapIndexed { i, ch ->
                TrendingChannel(ch, rank = nextRank + i, reason = "Popular", programme = null)
            }
            return section.copy(trending = keep + fillTrending)
        }
        return section.copy(trending = keep)
    }
}
