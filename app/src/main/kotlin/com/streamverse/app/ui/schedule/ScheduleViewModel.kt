package com.streamverse.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.epg.EpgManager
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.ScheduleDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState
    data class Success(val days: List<ScheduleDay>) : ScheduleUiState
    data class Error(val message: String) : ScheduleUiState
}

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val epgManager: EpgManager,
) : ViewModel() {

    private val _state = MutableStateFlow<ScheduleUiState>(ScheduleUiState.Loading)
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = ScheduleUiState.Loading
        viewModelScope.launch {
            val channels = repository.getCachedChannels()
            val days = epgManager.loadSchedule(channels)
            if (days.isEmpty()) {
                _state.value = ScheduleUiState.Error("No schedule data available")
            } else {
                _state.value = ScheduleUiState.Success(days)
            }
        }
    }

    fun refresh() { load() }
}
