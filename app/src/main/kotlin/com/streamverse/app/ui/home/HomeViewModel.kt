package com.streamverse.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.WatchHistoryPreferences
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.FavoritesRepository
import com.streamverse.core.data.repository.FeaturedItem
import com.streamverse.core.data.repository.FeaturedSelector
import com.streamverse.core.data.repository.HomeRankingEngine
import com.streamverse.core.data.repository.HomeSectionGenerator
import com.streamverse.core.data.repository.LoadingPhase
import com.streamverse.core.data.repository.ProgrammeRepository
import com.streamverse.core.data.repository.RankingContext
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.ChannelSummary
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
    val channels: List<ChannelSummary> = emptyList(),
    val categories: List<String> = emptyList(),
    val error: String? = null,
    val sortMode: SortMode = SortMode.CATEGORY,
    val featured: List<Channel> = emptyList(),
    val featuredProgrammes: List<ChannelProgramme> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    val timeOfDay: TimeOfDay = TimeOfDay(DayPeriod.MORNING, "Morning", 0xFFFF8C00),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val favouritesRepository: FavoritesRepository,
    private val watchHistory: WatchHistoryPreferences,
    private val healthEngine: ChannelHealthEngine,
    private val programmeRepo: ProgrammeRepository,
    private val homeRanking: HomeRankingEngine,
    private val featuredSelector: FeaturedSelector,
    private val sectionGenerator: HomeSectionGenerator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.CATEGORY)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    val favouriteIds: StateFlow<Set<String>> = favouritesRepository.getAllFavoriteIds()
        .map { it.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val recentlyWatched: StateFlow<List<Channel>> = combine(
        watchHistory.recent,
        repository.availableChannelIds,
    ) { history, availableIds ->
        val byId = repository.getChannelByIdMap()
        history.mapNotNull { w -> if (w.id in availableIds) byId[w.id] else null }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSortMode(mode: SortMode) { _sortMode.value = mode }

    init {
        viewModelScope.launch {
            combine(repository.channelSummaries, _sortMode, healthEngine.liveChannelIds, favouriteIds) { channels, mode, liveIds, favIds ->
                val sorted = sortChannels(channels, mode)
                val categories = buildSectionHeaders(sorted, mode)
                val ctx = homeRanking.buildHomeContext()
                val ctxWithFavs = ctx.copy(
                    baseContext = ctx.baseContext.copy(favoriteIds = favIds),
                    favouriteIds = favIds,
                )
                val fullChs = repository.getCachedChannels()
                val featuredItems = featuredSelector.select(fullChs, liveIds, ctxWithFavs, limit = 10)
                val sections = sectionGenerator.generate(
                    channels = fullChs,
                    liveIds = liveIds,
                    recentlyWatched = recentlyWatched.value,
                    favouriteIds = favIds,
                    ctx = ctxWithFavs,
                    featured = featuredItems,
                )
                HomeUiState(
                    isLoading = _uiState.value.isLoading,
                    channels = sorted,
                    categories = categories.filter { it != CategoryNormalizer.C.RADIO },
                    sortMode = mode,
                    featured = featuredItems.map { it.channel },
                    featuredProgrammes = featuredItems.mapNotNull { programmeRepo.getProgramme(it.channel) },
                    sections = sections,
                    timeOfDay = programmeRepo.timeOfDay.value,
                )
            }.flowOn(Dispatchers.Default).collect { state ->
                if (state.channels.isNotEmpty()) {
                    // verify handled by backend pipeline
                }
                _uiState.value = state
            }
        }

        viewModelScope.launch {
            repository.loadingPhase.collect { phase ->
                _uiState.value = _uiState.value.copy(isLoading = phase != LoadingPhase.DONE)
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
            repository.loadingPhase.first { it == LoadingPhase.DONE }
            val allChs = repository.getCachedChannels()
            if (allChs.isNotEmpty()) { /* verify handled by backend pipeline */ }
        }

        viewModelScope.launch {
            programmeRepo.loadSchedule()
        }

        viewModelScope.launch {
            while (true) {
                delay(60_000)
                programmeRepo.refreshTimeOfDay()
                _uiState.value = _uiState.value.copy(
                    timeOfDay = programmeRepo.timeOfDay.value,
                )
            }
        }
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

    fun toggleFavourite(channelId: String) {
        viewModelScope.launch {
            if (favouriteIds.value.contains(channelId)) favouritesRepository.removeFavorite(channelId)
            else {
                val ch = repository.getCachedChannels().find { it.id == channelId }
                if (ch != null) favouritesRepository.addFavorite(ch)
            }
        }
    }

    private fun buildSectionHeaders(channels: List<ChannelSummary>, mode: SortMode): List<String> = when (mode) {
        SortMode.CATEGORY -> CategoryNormalizer.C.ALL_TV
            .filter { it in channels.mapNotNull { it.category }.toSet() }
        SortMode.ALPHABETICAL -> listOf("All Channels")
        SortMode.REGION -> channels.mapNotNull { it.country }.distinct().sorted()
            .ifEmpty { listOf("All Regions") }
    }

    private fun sortChannels(channels: List<ChannelSummary>, mode: SortMode): List<ChannelSummary> = when (mode) {
        SortMode.CATEGORY -> channels.sortedWith(
            compareByDescending<ChannelSummary> { it.isVerified }
                .thenBy { it.category ?: "" }
                .thenBy { it.displayName.lowercase() }
        )
        SortMode.ALPHABETICAL -> channels.sortedWith(
            compareByDescending<ChannelSummary> { it.isVerified }
                .thenBy { it.displayName.lowercase() }
        )
        SortMode.REGION -> channels.sortedWith(
            compareByDescending<ChannelSummary> { it.isVerified }
                .thenBy { it.country ?: "" }
                .thenBy { it.displayName.lowercase() }
        )
    }
}
