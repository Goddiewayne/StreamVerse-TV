package com.streamverse.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.CacheStats
import com.streamverse.core.data.CacheTier
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.PlaybackPreferences
import com.streamverse.core.data.SmartCacheManager
import com.streamverse.core.data.SourceHealth
import com.streamverse.core.data.SourceHealthState
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.VideoResizeMode
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.ProviderLoadingPhase
import com.streamverse.core.data.source.LifecycleState
import com.streamverse.core.data.source.SourceRegistry
import com.streamverse.core.domain.model.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProviderHealthSummary(
    val totalChannels: Int = 0,
    val healthyChannels: Int = 0,
    val unhealthyChannels: Int = 0,
    val lifecycle: LifecycleState = LifecycleState.UNREGISTERED,
    val reliabilityPercent: Int = 0,
    val avgResponseTimeMs: Long = -1,
    val consecutiveFailures: Int = 0,
    val isEnabled: Boolean = true,
) {
    val overallState: SourceHealthState get() = when {
        !isEnabled -> SourceHealthState.UNKNOWN
        lifecycle != LifecycleState.ACTIVE && lifecycle != LifecycleState.REGISTERED -> SourceHealthState.UNAVAILABLE
        totalChannels == 0 -> SourceHealthState.UNKNOWN
        unhealthyChannels == totalChannels -> SourceHealthState.UNAVAILABLE
        healthyChannels == totalChannels -> SourceHealthState.AVAILABLE
        else -> SourceHealthState.VERIFYING
    }
    val isHealthy: Boolean get() = lifecycle == LifecycleState.ACTIVE && overallState != SourceHealthState.UNAVAILABLE
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sourcePreferences: SourcePreferences,
    private val playbackPreferences: PlaybackPreferences,
    private val repository: ChannelRepository,
    private val smartCacheManager: SmartCacheManager,
    private val healthEngine: ChannelHealthEngine,
    private val sourceRegistry: SourceRegistry,
) : ViewModel() {

    private val _enabledSources = MutableStateFlow(sourcePreferences.enabled())
    val enabledSources: StateFlow<Map<SourceProvider, Boolean>> = _enabledSources.asStateFlow()

    private val _sourceChannelCounts = MutableStateFlow(computeSourceChannelCounts())
    val sourceChannelCounts: StateFlow<Map<SourceProvider, Int>> = _sourceChannelCounts.asStateFlow()

    private val _providerHealthSummaries = MutableStateFlow(emptyMap<SourceProvider, ProviderHealthSummary>())
    val providerHealthSummaries: StateFlow<Map<SourceProvider, ProviderHealthSummary>> = _providerHealthSummaries.asStateFlow()

    val providerLoadingProgress: StateFlow<Map<SourceProvider, ProviderLoadingPhase>> = repository.providerProgress

    init {
        viewModelScope.launch {
            repository.channels.collect { _sourceChannelCounts.value = computeSourceChannelCounts() }
        }
        viewModelScope.launch {
            combine(
                repository.channels,
                healthEngine.sourceHealthUpdates,
                sourceRegistry.allStates,
            ) { channels, health, states ->
                computeProviderHealthSummaries(channels, health, states)
            }.collect { summaries ->
                _providerHealthSummaries.value = summaries
            }
        }
    }

    private val _keepScreenOn = MutableStateFlow(playbackPreferences.keepScreenOn)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _backgroundPlayback = MutableStateFlow(playbackPreferences.backgroundPlayback)
    val backgroundPlayback: StateFlow<Boolean> = _backgroundPlayback.asStateFlow()

    private val _dataSaver = MutableStateFlow(sourcePreferences.isDataSaverEnabled())
    val dataSaver: StateFlow<Boolean> = _dataSaver.asStateFlow()

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

    fun toggleDataSaver(enabled: Boolean) {
        sourcePreferences.setDataSaverEnabled(enabled)
        _dataSaver.value = enabled
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

    private fun computeProviderHealthSummaries(
        channels: List<com.streamverse.core.domain.model.Channel>,
        perChannelHealth: Map<String, Map<SourceType, SourceHealth>>,
        providerStates: Map<String, com.streamverse.core.data.source.ProviderState>,
    ): Map<SourceProvider, ProviderHealthSummary> {
        val providerChannels = mutableMapOf<SourceProvider, MutableList<SourceHealth?>>()
        for (channel in channels) {
            for ((sourceType, _) in channel.sources) {
                val provider = SourceProvider.forType(sourceType)
                val health = perChannelHealth[channel.id]?.get(sourceType)
                providerChannels.getOrPut(provider) { mutableListOf() }.add(health)
            }
        }
        val enabled = _enabledSources.value
        return SourceProvider.entries.associateWith { provider ->
            val healths = providerChannels[provider] ?: emptyList()
            val adapters = sourceRegistry.getProvidersForSourceProvider(provider)
            val state = adapters.firstOrNull()?.let { providerStates[it.providerId] }
            val lifecycle = state?.lifecycle ?: LifecycleState.UNREGISTERED
            val h = state?.health
            val reliability = if (h != null && h.totalRequests > 0) {
                (h.successfulRequests * 100 / h.totalRequests).toInt()
            } else 0
            val respTime = h?.responseTimeMs ?: -1
            val isEnabled = enabled[provider] ?: true
            val failures = h?.consecutiveFailures ?: 0
            if (healths.isEmpty()) ProviderHealthSummary(
                lifecycle = lifecycle, reliabilityPercent = reliability,
                avgResponseTimeMs = respTime, consecutiveFailures = failures, isEnabled = isEnabled,
            )
            else ProviderHealthSummary(
                totalChannels = healths.size,
                healthyChannels = healths.count { it?.state == SourceHealthState.AVAILABLE },
                unhealthyChannels = healths.count { it?.state == SourceHealthState.UNAVAILABLE },
                lifecycle = lifecycle, reliabilityPercent = reliability,
                avgResponseTimeMs = respTime, consecutiveFailures = failures, isEnabled = isEnabled,
            )
        }
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
