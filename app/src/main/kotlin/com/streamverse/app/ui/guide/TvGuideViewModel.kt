package com.streamverse.app.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.ProgrammeRepository
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.EpgEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvGuideUiState(
    val channels: List<Channel> = emptyList(),
    val epgData: Map<String, List<EpgEntry>> = emptyMap(),
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val currentTimeMillis: Long = System.currentTimeMillis(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val hasRealEpg: Set<String> = emptySet(),
)

@HiltViewModel
class TvGuideViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val programmeRepo: ProgrammeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvGuideUiState())
    val uiState: StateFlow<TvGuideUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.channels,
                repository.loadingPhase,
            ) { channels, phase ->
                Pair(channels, phase)
            }.collect { (allChannels, _) ->
                if (allChannels.isEmpty()) return@collect
                loadGuideData(allChannels)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(30_000L)
                _uiState.value = _uiState.value.copy(
                    currentTimeMillis = System.currentTimeMillis(),
                )
            }
        }

        viewModelScope.launch {
            try { repository.load() } catch (_: Exception) {}
        }
    }

    private suspend fun loadGuideData(allChannels: List<Channel>) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            val epg = programmeRepo.getEpgForChannels(
                channels = allChannels,
                skipFallback = true,
            )
            val channelsWithEpg = allChannels.filter { it.id in epg }
            val categories = channelsWithEpg.mapNotNull { it.category }.distinct().sorted()
            _uiState.value = TvGuideUiState(
                channels = channelsWithEpg,
                epgData = epg,
                categories = categories,
                currentTimeMillis = System.currentTimeMillis(),
                isLoading = false,
                hasRealEpg = epg.keys,
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = e.message ?: "Failed to load TV listings",
            )
        }
    }

    fun selectCategory(category: String?) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
    }

    fun refresh() {
        _uiState.value = TvGuideUiState(isLoading = true)
        viewModelScope.launch {
            try {
                repository.load()
                delay(500)
                val allChannels = repository.getCachedChannels()
                if (allChannels.isNotEmpty()) loadGuideData(allChannels)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Refresh failed",
                )
            }
        }
    }
}
