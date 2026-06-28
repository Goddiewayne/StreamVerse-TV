package com.streamverse.app.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.FavoritesRepository
import com.streamverse.core.domain.model.Channel
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
    val channels: List<Channel> = emptyList(),
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
                repository.channels.map { list -> list.associateBy { it.id } },
            ) { favorites, channelById ->
                favorites.mapNotNull { fav -> channelById[fav.channelId] }
            }.collect { resolved ->
                _uiState.value = FavoritesUiState(
                    channels = resolved,
                    isLoading = false,
                )
                // Favourites are prime "likely to watch" — verify them deeply for LIVE badges.
                healthEngine.verify(resolved, deep = true)
            }
        }
    }

    fun removeFavorite(channelId: String) {
        viewModelScope.launch {
            favoritesRepository.removeFavorite(channelId)
        }
    }
}
