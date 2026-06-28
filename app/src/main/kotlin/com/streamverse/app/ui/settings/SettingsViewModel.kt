package com.streamverse.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.PlaybackPreferences
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.VideoResizeMode
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.repository.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sourcePreferences: SourcePreferences,
    private val playbackPreferences: PlaybackPreferences,
    private val repository: ChannelRepository,
) : ViewModel() {

    private val _enabledSources = MutableStateFlow(sourcePreferences.enabled())
    val enabledSources: StateFlow<Map<SourceProvider, Boolean>> = _enabledSources.asStateFlow()

    private val _reloading = MutableStateFlow(false)
    val reloading: StateFlow<Boolean> = _reloading.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(playbackPreferences.keepScreenOn)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _backgroundPlayback = MutableStateFlow(playbackPreferences.backgroundPlayback)
    val backgroundPlayback: StateFlow<Boolean> = _backgroundPlayback.asStateFlow()

    private val _resizeMode = MutableStateFlow(playbackPreferences.resizeMode)
    val resizeMode: StateFlow<VideoResizeMode> = _resizeMode.asStateFlow()

    private val _staticIntensity = MutableStateFlow(playbackPreferences.staticIntensity)
    val staticIntensity: StateFlow<String> = _staticIntensity.asStateFlow()

    private val _staticAudio = MutableStateFlow(playbackPreferences.staticAudio)
    val staticAudio: StateFlow<Boolean> = _staticAudio.asStateFlow()

    private val _staticChannelBurst = MutableStateFlow(playbackPreferences.staticChannelBurst)
    val staticChannelBurst: StateFlow<Boolean> = _staticChannelBurst.asStateFlow()

    private var reloadJob: Job? = null

    fun toggleSource(provider: SourceProvider, enabled: Boolean) {
        sourcePreferences.setEnabled(provider, enabled)
        _enabledSources.value = sourcePreferences.enabled()
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            delay(900)
            _reloading.value = true
            runCatching { repository.reload() }
            _reloading.value = false
        }
    }

    fun toggleKeepScreenOn(enabled: Boolean) {
        playbackPreferences.keepScreenOn = enabled
        _keepScreenOn.value = enabled
    }

    fun toggleBackgroundPlayback(enabled: Boolean) {
        playbackPreferences.backgroundPlayback = enabled
        _backgroundPlayback.value = enabled
    }

    fun setResizeMode(mode: VideoResizeMode) {
        playbackPreferences.resizeMode = mode
        _resizeMode.value = mode
    }

    fun setStaticIntensity(intensity: String) {
        playbackPreferences.staticIntensity = intensity
        _staticIntensity.value = intensity
    }

    fun toggleStaticAudio(enabled: Boolean) {
        playbackPreferences.staticAudio = enabled
        _staticAudio.value = enabled
    }

    fun toggleStaticChannelBurst(enabled: Boolean) {
        playbackPreferences.staticChannelBurst = enabled
        _staticChannelBurst.value = enabled
    }
}
