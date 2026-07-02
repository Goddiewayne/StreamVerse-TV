package com.streamverse.app.ui.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.source.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceProviderCard(
    val provider: SourceProvider,
    val displayName: String,
    val description: String,
    val isEnabled: Boolean,
    val channelCount: Int,
    val statusLabel: String,
    val isOnline: Boolean,
)

data class SourceManagementUiState(
    val providers: List<SourceProviderCard> = emptyList(),
    val totalChannels: Int = 0,
    val enabledCount: Int = 0,
)

@HiltViewModel
class SourceManagementViewModel @Inject constructor(
    private val sourcePreferences: SourcePreferences,
    private val repository: ChannelRepository,
    private val sourceRegistry: SourceRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SourceManagementUiState())
    val uiState: StateFlow<SourceManagementUiState> = _uiState.asStateFlow()

    init {
        combine(
            sourcePreferences.enabledFlow,
            sourceRegistry.allStates,
            repository.channelRefreshTrigger,
        ) { enabled, states, _ ->
            val channels = repository.getAllChannels()
            val providerCards = SourceProvider.entries.map { provider ->
                val channelCount = channels.count { ch ->
                    ch.sources.keys.any { SourceProvider.forType(it) == provider }
                }
                val adapters = sourceRegistry.getProvidersForSourceProvider(provider)
                val state = adapters.firstOrNull()?.let { states[it.providerId] }
                val isEnabled = enabled[provider] ?: true
                val isOnline = channelCount > 0 || state?.lifecycle == com.streamverse.core.data.source.LifecycleState.ACTIVE
                val statusLabel = when {
                    !isEnabled -> "Disabled"
                    isOnline -> if (channelCount > 0) "Online · $channelCount channels" else "Online"
                    state?.lifecycle == com.streamverse.core.data.source.LifecycleState.INITIALIZING -> "Starting…"
                    state?.lifecycle == com.streamverse.core.data.source.LifecycleState.FAILED -> "Connection issue"
                    else -> "Offline"
                }
                SourceProviderCard(
                    provider = provider,
                    displayName = provider.displayName,
                    description = provider.description,
                    isEnabled = isEnabled,
                    channelCount = channelCount,
                    statusLabel = statusLabel,
                    isOnline = isOnline && isEnabled,
                )
            }
            val totalChannels = channels.size
            val enabledCount = providerCards.count { it.isEnabled }
            _uiState.value = SourceManagementUiState(
                providers = providerCards,
                totalChannels = totalChannels,
                enabledCount = enabledCount,
            )
        }.launchIn(viewModelScope)
    }

    fun toggleProvider(provider: SourceProvider, enabled: Boolean) {
        sourcePreferences.setEnabled(provider, enabled)
        val adapters = sourceRegistry.getProvidersForSourceProvider(provider)
        for (adapter in adapters) {
            if (enabled) sourceRegistry.enable(adapter.providerId)
            else sourceRegistry.disable(adapter.providerId)
        }
    }

    fun refreshCatalogue() {
        viewModelScope.launch {
            repository.load()
        }
    }
}
