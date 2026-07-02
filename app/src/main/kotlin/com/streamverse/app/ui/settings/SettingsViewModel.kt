package com.streamverse.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.CacheStats
import com.streamverse.core.data.CacheTier
import com.streamverse.core.data.PlaybackPreferences
import com.streamverse.core.data.SmartCacheManager
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.VideoResizeMode
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.source.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SettingsSection {
    PLAYBACK,
    CHANNELS_AND_SOURCES,
    NO_SIGNAL,
    STORAGE,
    ADVANCED,
    DIAGNOSTICS,
    ABOUT,
}

data class SourceProviderInfo(
    val provider: SourceProvider,
    val displayName: String,
    val isEnabled: Boolean,
    val channelCount: Int,
    val isOnline: Boolean,
)

data class DiagnosticsInfo(
    val catalogueVersion: String = "",
    val channelCount: Int = 0,
    val enabledSourceCount: Int = 0,
    val totalSourceCount: Int = 0,
    val lastUpdated: String = "",
)

data class SettingsUiState(
    val expandedSections: Set<SettingsSection> = emptySet(),
    val searchQuery: String = "",
    // Playback
    val keepScreenOn: Boolean = true,
    val backgroundAudio: Boolean = true,
    val dataSaver: Boolean = false,
    val videoScaling: VideoResizeMode = VideoResizeMode.FIT,
    // No-signal
    val staticIntensity: String = "medium",
    val staticChannelBurst: Boolean = true,
    val staticSound: Boolean = false,
    // Sources
    val sourceProviders: List<SourceProviderInfo> = emptyList(),
    val totalChannelCount: Int = 0,
    // Storage
    val cacheStats: List<CacheStats> = emptyList(),
    val totalCacheSize: String = "0 B",
    // Diagnostics
    val diagnostics: DiagnosticsInfo = DiagnosticsInfo(),
    // Actions
    val isRefreshingCatalogue: Boolean = false,
    val activeOperations: Set<String> = emptySet(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sourcePreferences: SourcePreferences,
    private val playbackPreferences: PlaybackPreferences,
    private val channelRepository: ChannelRepository,
    private val smartCacheManager: SmartCacheManager,
    private val sourceRegistry: SourceRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                channelRepository.channels,
                sourcePreferences.enabledFlow,
                sourceRegistry.allStates,
            ) { channels, enabled, states ->
                val providers = SourceProvider.entries.map { provider ->
                    val channelCount = channels.count { ch ->
                        ch.sources.keys.any { SourceProvider.forType(it) == provider }
                    }
                    val adapters = sourceRegistry.getProvidersForSourceProvider(provider)
                    val state = adapters.firstOrNull()?.let { states[it.providerId] }
                    SourceProviderInfo(
                        provider = provider,
                        displayName = provider.displayName,
                        isEnabled = enabled[provider] ?: true,
                        channelCount = channelCount,
                        isOnline = state?.lifecycle == com.streamverse.core.data.source.LifecycleState.ACTIVE,
                    )
                }
                _state.value = _state.value.copy(
                    sourceProviders = providers,
                    totalChannelCount = channels.size,
                    diagnostics = _state.value.diagnostics.copy(
                        channelCount = channels.size,
                        enabledSourceCount = providers.count { it.isEnabled },
                        totalSourceCount = providers.size,
                    ),
                )
            }.launchIn(viewModelScope)
        }
        loadStorageInfo()
        loadDiagnostics()
    }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                cacheStats = smartCacheManager.allStats(),
                totalCacheSize = formatBytes(smartCacheManager.totalSizeBytes()),
            )
        }
    }

    private fun loadDiagnostics() {
        viewModelScope.launch {
            val channels = channelRepository.getAllChannels()
            val providers = SourceProvider.entries.map { provider ->
                val adapters = sourceRegistry.getProvidersForSourceProvider(provider)
                val state = adapters.firstOrNull()?.let { sourceRegistry.allStates.value[it.providerId] }
                SourceProviderInfo(
                    provider = provider,
                    displayName = provider.displayName,
                    isEnabled = sourcePreferences.isEnabled(provider),
                    channelCount = channels.count { ch ->
                        ch.sources.keys.any { SourceProvider.forType(it) == provider }
                    },
                    isOnline = state?.lifecycle == com.streamverse.core.data.source.LifecycleState.ACTIVE,
                )
            }
            val lastSync = sourceRegistry.allStates.value.values.maxOfOrNull { it.lastSyncMs }
            _state.value = _state.value.copy(
                diagnostics = DiagnosticsInfo(
                    catalogueVersion = "1.1.0",
                    channelCount = channels.size,
                    enabledSourceCount = providers.count { it.isEnabled },
                    totalSourceCount = providers.size,
                    lastUpdated = if (lastSync != null && lastSync > 0) formatTimestamp(lastSync) else "--",
                ),
            )
        }
    }

    fun toggleSection(section: SettingsSection) {
        val current = _state.value.expandedSections.toMutableSet()
        if (current.contains(section)) current.remove(section) else current.add(section)
        _state.value = _state.value.copy(expandedSections = current)
    }

    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    // Playback
    fun setKeepScreenOn(enabled: Boolean) {
        playbackPreferences.keepScreenOn = enabled
        _state.value = _state.value.copy(keepScreenOn = enabled)
    }

    fun setBackgroundAudio(enabled: Boolean) {
        playbackPreferences.backgroundPlayback = enabled
        _state.value = _state.value.copy(backgroundAudio = enabled)
    }

    fun setDataSaver(enabled: Boolean) {
        sourcePreferences.setDataSaverEnabled(enabled)
        _state.value = _state.value.copy(dataSaver = enabled)
    }

    fun setVideoScaling(mode: VideoResizeMode) {
        playbackPreferences.resizeMode = mode
        _state.value = _state.value.copy(videoScaling = mode)
    }

    // No-signal
    fun setStaticIntensity(intensity: String) {
        playbackPreferences.staticIntensity = intensity
        _state.value = _state.value.copy(staticIntensity = intensity)
    }

    fun setStaticChannelBurst(enabled: Boolean) {
        playbackPreferences.staticChannelBurst = enabled
        _state.value = _state.value.copy(staticChannelBurst = enabled)
    }

    fun setStaticSound(enabled: Boolean) {
        playbackPreferences.staticAudio = enabled
        _state.value = _state.value.copy(staticSound = enabled)
    }

    // Storage
    fun clearCacheTier(tier: CacheTier) {
        smartCacheManager.evict(tier)
        refreshCache()
    }

    fun clearAllCache() {
        smartCacheManager.evictAll()
        refreshCache()
    }

    fun refreshCache() {
        loadStorageInfo()
    }

    // Actions
    fun refreshCatalogue() {
        if (_state.value.isRefreshingCatalogue) return
        _state.value = _state.value.copy(isRefreshingCatalogue = true)
        viewModelScope.launch {
            channelRepository.load()
            _state.value = _state.value.copy(isRefreshingCatalogue = false)
            loadStorageInfo()
            loadDiagnostics()
        }
    }

    fun rebuildSearchIndex() {
        markOperation("rebuild_index")
        viewModelScope.launch {
            channelRepository.reload()
            clearOperation("rebuild_index")
        }
    }

    fun refreshMetadata() {
        markOperation("refresh_metadata")
        viewModelScope.launch {
            channelRepository.load()
            clearOperation("refresh_metadata")
        }
    }

    fun validateStreams() {
        markOperation("validate_streams")
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            clearOperation("validate_streams")
        }
    }

    // Source management
    fun toggleSourceProvider(provider: SourceProvider, enabled: Boolean) {
        sourcePreferences.setEnabled(provider, enabled)
        val adapters = sourceRegistry.getProvidersForSourceProvider(provider)
        for (adapter in adapters) {
            if (enabled) sourceRegistry.enable(adapter.providerId)
            else sourceRegistry.disable(adapter.providerId)
        }
    }

    private fun markOperation(op: String) {
        val current = _state.value.activeOperations.toMutableSet()
        current.add(op)
        _state.value = _state.value.copy(activeOperations = current)
    }

    private fun clearOperation(op: String) {
        val current = _state.value.activeOperations.toMutableSet()
        current.remove(op)
        _state.value = _state.value.copy(activeOperations = current)
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatTimestamp(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ms))
    }
}
