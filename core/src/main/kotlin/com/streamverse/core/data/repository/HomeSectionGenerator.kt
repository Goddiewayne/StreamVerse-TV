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
        return rankingEngine.rankSections(sections, ctx.period)
    }
}
