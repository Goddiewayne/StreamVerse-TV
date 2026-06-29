package com.streamverse.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.CacheStats
import com.streamverse.core.data.CacheTier
import com.streamverse.core.data.PlaybackPreferences
import com.streamverse.core.data.SmartCacheManager
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
    private val smartCacheManager: SmartCacheManager,
) : ViewModel() {

    private val _enabledSources = MutableStateFlow(sourcePreferences.enabled())
    val enabledSources: StateFlow<Map<SourceProvider, Boolean>> = _enabledSources.asStateFlow()

    private val _sourceChannelCounts = MutableStateFlow(computeSourceChannelCounts())
    val sourceChannelCounts: StateFlow<Map<SourceProvider, Int>> = _sourceChannelCounts.asStateFlow()

    init {
        viewModelScope.launch {
            repository.channels.collect { _sourceChannelCounts.value = computeSourceChannelCounts() }
        }
    }

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

    private val _cacheStats = MutableStateFlow(refreshCacheStats())
    val cacheStats: StateFlow<List<CacheStats>> = _cacheStats.asStateFlow()

    private val _totalCacheSize = MutableStateFlow(formatBytes(smartCacheManager.totalSizeBytes()))
    val totalCacheSize: StateFlow<String> = _totalCacheSize.asStateFlow()

    fun toggleSource(provider: SourceProvider, enabled: Boolean) {
        sourcePreferences.setEnabled(provider, enabled)
        _enabledSources.value = sourcePreferences.enabled()
        _sourceChannelCounts.value = computeSourceChannelCounts()
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

    fun clearCacheTier(tier: CacheTier) {
        smartCacheManager.evict(tier)
        refreshCache()
    }

    fun clearAllCache() {
        smartCacheManager.evictAll()
        refreshCache()
    }

    fun refreshCache() {
        _cacheStats.value = refreshCacheStats()
        _totalCacheSize.value = formatBytes(smartCacheManager.totalSizeBytes())
    }

    private fun refreshCacheStats(): List<CacheStats> = smartCacheManager.allStats()

    private fun computeSourceChannelCounts(): Map<SourceProvider, Int> {
        val channels = repository.getAllChannels()
        val counts = mutableMapOf<SourceProvider, Int>()
        for (provider in SourceProvider.entries) counts[provider] = 0
        for (ch in channels) {
            for (srcType in ch.sources.keys) {
                val provider = SourceProvider.forType(srcType)
                counts[provider] = (counts[provider] ?: 0) + 1
            }
        }
        return counts
    }

    companion object {
        fun formatChannelCount(count: Int): String = when {
            count >= 10_000 -> "%.1fk".format(count / 1_000.0)
            count >= 1_000 -> "%.1fk".format(count / 1_000.0)
            else -> "$count"
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
