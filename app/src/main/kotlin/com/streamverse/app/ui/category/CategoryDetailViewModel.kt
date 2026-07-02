package com.streamverse.app.ui.category

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.FavoritesRepository
import com.streamverse.core.domain.model.ChannelSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ChannelRepository,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    val type: String = checkNotNull(savedStateHandle["type"])
    val value: String = checkNotNull(savedStateHandle["value"])

    private val _channels = MutableStateFlow<List<ChannelSummary>>(emptyList())
    val channels: StateFlow<List<ChannelSummary>> = _channels.asStateFlow()

    val favoriteIds: StateFlow<Set<String>> = favoritesRepository.getAllFavoriteIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        viewModelScope.launch {
            repository.channelSummaries.collect { liveChs ->
                _channels.value = when (type) {
                    "category" -> liveChs.filter { it.category == value }
                    "az" -> {
                        val firstChar = when (value) {
                            "0–9" -> ('0'..'9').toSet()
                            else -> value.lowercase().singleOrNull()?.let { setOf(it) } ?: emptySet()
                        }
                        liveChs.filter { ch ->
                            val first = ch.displayName.lowercase().firstOrNull()
                            first != null && first in firstChar
                        }
                    }
                    "region" -> {
                        if (value == "All Regions") liveChs
                        else liveChs.filter { it.country == value }
                    }
                    else -> emptyList()
                }
            }
        }
    }

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch {
            if (favoriteIds.value.contains(channelId)) {
                favoritesRepository.removeFavorite(channelId)
            } else {
                val ch = repository.getCachedChannels().find { it.id == channelId }
                if (ch != null) favoritesRepository.addFavorite(ch)
            }
        }
    }
}