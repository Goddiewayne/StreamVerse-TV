package com.streamverse.app.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.FavoritesRepository
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.ChannelSummary
import com.streamverse.core.domain.model.toSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUiState(
    val channels: List<ChannelSummary> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val favoritesRepository: FavoritesRepository,
    private val healthEngine: ChannelHealthEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    val favoriteIds: StateFlow<Set<String>> = favoritesRepository.getAllFavoriteIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        viewModelScope.launch {
            combine(
                favoritesRepository.getAllFavorites(),
                repository.availableChannelIds,
            ) { favorites, availableIds ->
                val byId = repository.getChannelByIdMap()
                favorites.mapNotNull { fav ->
                    if (fav.channelId in availableIds) byId[fav.channelId] else null
                }
            }.collect { resolved ->
                healthEngine.verify(resolved, deep = true)
                _uiState.value = FavoritesUiState(
                    channels = resolved.map { it.toSummary() },
                    isLoading = false,
                )
            }
        }
    }

    fun removeFavorite(channelId: String) {
        viewModelScope.launch {
            favoritesRepository.removeFavorite(channelId)
        }
    }
}
