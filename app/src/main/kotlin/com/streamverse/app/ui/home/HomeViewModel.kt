package com.streamverse.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.WatchHistoryPreferences
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.FavoritesRepository
import com.streamverse.core.data.repository.LoadingPhase
import com.streamverse.core.data.repository.ProgrammeRepository
import com.streamverse.core.data.repository.RankingContext
import com.streamverse.core.domain.model.*
import com.streamverse.core.util.CategoryNormalizer
import com.streamverse.core.util.RegionProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val channels: List<Channel> = emptyList(),
    val categories: List<String> = emptyList(),
    val error: String? = null,
    val sortMode: SortMode = SortMode.CATEGORY,
    val featured: List<Channel> = emptyList(),
    val featuredProgrammes: List<ChannelProgramme> = emptyList(),
    val onNow: List<ChannelProgramme> = emptyList(),
    val trending: List<TrendingChannel> = emptyList(),
    val liveEvents: List<LiveEvent> = emptyList(),
    val headlines: List<NewsHeadline> = emptyList(),
    val liveScores: List<LiveScore> = emptyList(),
    val timeOfDay: TimeOfDay = TimeOfDay(DayPeriod.MORNING, "Morning", 0xFFFF8C00),
    val editorialPicks: List<Channel> = emptyList(),
    val popularWorldwide: List<Channel> = emptyList(),
    val topNews: List<ChannelProgramme> = emptyList(),
    val topSports: List<ChannelProgramme> = emptyList(),
    val topEntertainment: List<Channel> = emptyList(),
    val topMovies: List<Channel> = emptyList(),
    val topDocumentaries: List<Channel> = emptyList(),
    val kidsChannels: List<Channel> = emptyList(),
    val musicChannels: List<Channel> = emptyList(),
    val popularInRegion: List<Channel> = emptyList(),
    val regionalChannels: Map<String, List<Channel>> = emptyMap(),
    val recommendations: List<Channel> = emptyList(),
    val recentlyAdded: List<Channel> = emptyList(),
    val categoryProgrammes: Map<String, List<ChannelProgramme>> = emptyMap(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val favouritesRepository: FavoritesRepository,
    private val watchHistory: WatchHistoryPreferences,
    private val healthEngine: ChannelHealthEngine,
    private val programmeRepo: ProgrammeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.CATEGORY)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    val favouriteIds: StateFlow<Set<String>> = favouritesRepository.getAllFavoriteIds()
        .map { it.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val recentlyWatched: StateFlow<List<Channel>> = combine(
        watchHistory.recent,
        repository.channels.map { list -> list.associateBy { it.id } },
    ) { history, channelById ->
        history.mapNotNull { w -> channelById[w.id] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSortMode(mode: SortMode) { _sortMode.value = mode }

    init {
        viewModelScope.launch {
            combine(repository.channels, _sortMode, healthEngine.liveChannelIds) { channels, mode, liveIds ->
                val sorted = sortChannels(channels, mode)
                val categories = buildSectionHeaders(sorted, mode)
                val featured = if (sorted.isNotEmpty()) buildFeatured(sorted, liveIds) else emptyList()
                Triple(sorted, categories, featured to liveIds)
            }.flowOn(Dispatchers.Default).collect { (sorted, categories, pair) ->
                rebuildUi(sorted, categories, pair.first, pair.second)
            }
        }

        viewModelScope.launch {
            repository.loadingPhase.collect { phase ->
                _uiState.value = _uiState.value.copy(
                    isLoading = phase != LoadingPhase.DONE,
                )
            }
        }

        viewModelScope.launch {
            try { repository.load() }
            catch (e: Exception) {
                if (_uiState.value.channels.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, error = e.message ?: "Failed to load channels",
                    )
                }
            }
        }

        viewModelScope.launch {
            programmeRepo.loadSchedule()
        }

        viewModelScope.launch {
            while (true) {
                delay(60_000)
                programmeRepo.refreshTimeOfDay()
                val cached = repository.getCachedChannels()
                _uiState.value = _uiState.value.copy(
                    timeOfDay = programmeRepo.timeOfDay.value,
                    onNow = programmeRepo.getWhatsOnNow(cached),
                )
            }
        }
    }

    private suspend fun rebuildUi(channels: List<Channel>, categories: List<String>, featured: List<Channel>, liveIds: Set<String>) {
        programmeRepo.refreshAllProgrammes()

        val ctx = RankingContext(
            userRegion = RegionProvider.getRegionCode(),
            recentlyWatchedIds = recentlyWatched.value.map { it.id }.toSet(),
            favoriteIds = favouriteIds.value,
        )
        val liveOnly = { list: List<Channel> -> list.filter { it.id in liveIds } }
        val liveChs = liveOnly(channels)

        val featuredProgs = featured.map { programmeRepo.getProgramme(it) }
        val onNow = programmeRepo.getWhatsOnNow(channels).filter { it.channel.id in liveIds }
        programmeRepo.updateTrending(channels)
        programmeRepo.updateLiveEvents(channels)
        programmeRepo.updateHeadlines()
        programmeRepo.updateLiveScores(channels)

        val editorialPicks = programmeRepo.editorialPicks(liveChs, limit = 10, ctx = ctx)
        val popularWorldwide = programmeRepo.getGloballyPopular(liveChs, limit = 12, ctx = ctx)

        val topNews = programmeRepo.topByCategory(channels, CategoryNormalizer.C.NEWS, limit = 10, ctx = ctx)
            .let { liveOnly(it) }.map { programmeRepo.getProgramme(it) }
        val topSports = programmeRepo.topByCategory(channels, CategoryNormalizer.C.SPORTS, limit = 10, ctx = ctx)
            .let { liveOnly(it) }.map { programmeRepo.getProgramme(it) }
        val topEntertainment = programmeRepo.topByCategory(channels, CategoryNormalizer.C.ENTERTAINMENT, limit = 12, ctx = ctx)
            .let { liveOnly(it) }
        val topMovies = programmeRepo.topByCategory(channels, CategoryNormalizer.C.MOVIES, limit = 12, ctx = ctx)
            .let { liveOnly(it) }
        val topDocumentaries = programmeRepo.topByCategory(channels, CategoryNormalizer.C.DOCUMENTARY, limit = 12, ctx = ctx)
            .let { liveOnly(it) }
        val kidsChs = programmeRepo.topByCategory(channels, CategoryNormalizer.C.KIDS, limit = 15, ctx = ctx)
            .let { liveOnly(it) }
        val musicChs = programmeRepo.topByCategory(channels, CategoryNormalizer.C.MUSIC, limit = 15, ctx = ctx)
            .let { liveOnly(it) }
        val popularInRegion = programmeRepo.popularInRegion(liveChs, limit = 12, ctx = ctx)

        val recs = programmeRepo.getRecommendedChannels(
            liveChs, recentlyWatched.value,
            channels.mapNotNull { it.category }.toSet(),
            programmeRepo.timeOfDay.value.period,
        )
        val recentAdded = channels.filter { it.id in liveIds }.asReversed().take(20)

        val catProgrammes = mutableMapOf<String, List<ChannelProgramme>>()
        for (cat in categories.filter { it != CategoryNormalizer.C.RADIO }) {
            catProgrammes[cat] = programmeRepo.topByCategory(channels, cat, limit = 8, ctx = ctx)
                .let { liveOnly(it) }.map { programmeRepo.getProgramme(it) }
        }
        val regional = programmeRepo.getRegionalChannels(liveChs)

        _uiState.value = _uiState.value.copy(
            channels = channels,
            categories = categories.filter { it != CategoryNormalizer.C.RADIO },
            featured = featured,
            featuredProgrammes = featuredProgs,
            onNow = onNow,
            trending = programmeRepo.trending.value,
            liveEvents = programmeRepo.liveEvents.value,
            headlines = programmeRepo.headlines.value,
            liveScores = programmeRepo.liveScores.value,
            timeOfDay = programmeRepo.timeOfDay.value,
            editorialPicks = editorialPicks,
            popularWorldwide = popularWorldwide,
            topNews = topNews,
            topSports = topSports,
            topEntertainment = topEntertainment,
            topMovies = topMovies,
            topDocumentaries = topDocumentaries,
            kidsChannels = kidsChs,
            musicChannels = musicChs,
            popularInRegion = popularInRegion,
            regionalChannels = regional,
            recommendations = recs,
            recentlyAdded = recentAdded,
            categoryProgrammes = catProgrammes,
        )

        healthEngine.verify(featured + onNow.map { it.channel }, deep = true)
        healthEngine.verify(channels.take(120), deep = false)
    }

    fun retry() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try { repository.load() }
            catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun toggleFavourite(channel: Channel) {
        viewModelScope.launch {
            val isFav = favouriteIds.value.contains(channel.id)
            if (isFav) favouritesRepository.removeFavorite(channel.id)
            else favouritesRepository.addFavorite(channel)
        }
    }

    private fun buildSectionHeaders(channels: List<Channel>, mode: SortMode): List<String> = when (mode) {
        SortMode.CATEGORY -> CategoryNormalizer.C.ALL_TV
            .filter { it in channels.mapNotNull { it.category }.toSet() }
        SortMode.ALPHABETICAL -> listOf("All Channels")
        SortMode.REGION -> channels.mapNotNull { it.country }.distinct().sorted()
            .ifEmpty { listOf("All Regions") }
    }

    private fun sortChannels(channels: List<Channel>, mode: SortMode): List<Channel> = when (mode) {
        SortMode.CATEGORY -> channels
        SortMode.ALPHABETICAL -> channels.sortedBy { it.displayName.lowercase() }
        SortMode.REGION -> channels.sortedWith(
            compareBy({ it.country ?: "" }, { it.displayName.lowercase() })
        )
    }

    private fun buildFeatured(channels: List<Channel>, liveIds: Set<String>): List<Channel> {
        val userRegion = RegionProvider.getRegionCode()
        val eligible = channels.filter { ch ->
            ch.sources.keys.none { it == SourceType.RADIO } && ch.logoUrl != null && ch.id in liveIds
        }
        val byQuality = compareByDescending<Channel> { it.quality?.ordinal ?: -1 }
            .thenBy { it.displayName.lowercase() }
        val regional = eligible.filter { it.country != null && it.country.equals(userRegion, ignoreCase = true) }
            .sortedWith(byQuality)
        val global = eligible.filter { it.country == null || !it.country.equals(userRegion, ignoreCase = true) }
            .sortedWith(byQuality)
        return (regional + global).take(10)
    }
}
