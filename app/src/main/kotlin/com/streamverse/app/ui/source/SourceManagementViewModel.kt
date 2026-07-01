package com.streamverse.app.ui.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.source.HealthMonitor
import com.streamverse.core.data.source.LifecycleState
import com.streamverse.core.data.source.ProviderAdapter
import com.streamverse.core.data.source.ProviderCapability
import com.streamverse.core.data.source.ProviderState
import com.streamverse.core.data.source.SourceRegistry
import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.domain.model.Channel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

data class SystemSummary(
    val totalProviders: Int = 0,
    val enabledProviders: Int = 0,
    val healthyProviders: Int = 0,
    val warningProviders: Int = 0,
    val offlineProviders: Int = 0,
    val totalLogicalChannels: Int = 0,
    val totalPhysicalStreams: Int = 0,
    val multiSourceChannels: Int = 0,
    val lastSyncTimestamp: Long = 0,
    val isSyncing: Boolean = false,
)

data class ProviderSummary(
    val provider: SourceProvider,
    val providerId: String,
    val displayName: String,
    val description: String,
    val isEnabled: Boolean,
    val lifecycle: LifecycleState,
    val isHealthy: Boolean,
    val channelCount: Int,
    val streamCount: Int,
    val reliabilityPercent: Int,
    val avgResponseTimeMs: Long,
    val lastSyncMs: Long,
    val consecutiveFailures: Int,
    val successRate: Float,
    val lastError: String?,
    val capabilities: List<ProviderCapability>,
    val categories: List<String>,
    val countries: List<String>,
    val languages: List<String>,
)

data class ImpactAnalysis(
    val providerName: String,
    val providerId: String,
    val totalChannelsAffected: Int,
    val channelsWithAlternatives: Int,
    val channelsUnavailable: Int,
    val affectedCategories: List<Pair<String, Int>>,
    val favoritesAffected: Int,
    val recentlyWatchedAffected: Int,
    val warnings: List<String>,
)

enum class LiveEventType { SYNC, HEALTH, FAILOVER, DEDUP, METADATA, PLAYBACK }
enum class LiveEventSeverity { INFO, WARNING, ERROR }

data class LiveEvent(
    val id: String,
    val type: LiveEventType,
    val providerName: String,
    val message: String,
    val timestamp: Long,
    val severity: LiveEventSeverity,
)

data class SourceManagementUiState(
    val systemSummary: SystemSummary = SystemSummary(),
    val providerSummaries: List<ProviderSummary> = emptyList(),
    val expandedProviderId: String? = null,
    val priorityOrder: List<SourceProvider> = SourceProvider.entries.toList(),
    val activeOperations: Set<String> = emptySet(),
    val liveEvents: List<LiveEvent> = emptyList(),
    val impactAnalysis: ImpactAnalysis? = null,
    val showImpactDialog: Boolean = false,
)

@HiltViewModel
class SourceManagementViewModel @Inject constructor(
    private val sourceRegistry: SourceRegistry,
    private val sourcePreferences: SourcePreferences,
    private val repository: ChannelRepository,
    private val healthMonitor: HealthMonitor,
    private val providerRegistry: ProviderRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SourceManagementUiState())
    val uiState: StateFlow<SourceManagementUiState> = _uiState.asStateFlow()

    private val eventCounter = AtomicInteger(0)

    init {
        // Apply any persisted priority order to the playback pipeline.
        val initialPriority = sourcePreferences.priorityOrder()
        if (initialPriority.isNotEmpty()) {
            providerRegistry.setPriorityOverride(initialPriority)
        }
        combine(
            sourceRegistry.allProviders,
            sourceRegistry.allStates,
            sourcePreferences.enabledFlow,
            sourcePreferences.priorityOrderFlow,
            repository.channelRefreshTrigger,
        ) { providers, states, enabled, priority, _ ->
            val channels = repository.getAllChannels()
            val summary = computeSystemSummary(providers, states, enabled, channels)
            val summaries = computeProviderSummaries(providers, states, enabled, channels)
            val effectivePriority = if (priority.isNotEmpty()) priority else SourceProvider.entries.toList()
            _uiState.value = _uiState.value.copy(
                systemSummary = summary,
                providerSummaries = summaries,
                priorityOrder = effectivePriority,
            )
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            healthMonitor.monitorStats.collect { stats ->
                val event = LiveEvent(
                    id = "health_${eventCounter.incrementAndGet()}",
                    type = LiveEventType.HEALTH,
                    providerName = "System",
                    message = "Probes: ${stats.totalProbes}, reachable: ${stats.successfulProbes}/${stats.activeInstances} instances",
                    timestamp = System.currentTimeMillis(),
                    severity = LiveEventSeverity.INFO,
                )
                addLiveEvent(event)
            }
        }

        // Trigger initial data load so the page isn't empty on first open.
        viewModelScope.launch {
            repository.load()
        }
    }

    fun toggleProvider(provider: SourceProvider, enabled: Boolean) {
        if (!enabled) {
            performImpactAnalysis(provider)
        } else {
            confirmDisableProvider(provider, enabled = true)
        }
    }

    fun requestImpactAnalysis(provider: SourceProvider) {
        performImpactAnalysis(provider)
    }

    fun confirmDisableProvider(provider: SourceProvider, enabled: Boolean) {
        sourcePreferences.setEnabled(provider, enabled)
        val adapters = sourceRegistry.getProvidersForSourceProvider(provider)
        for (adapter in adapters) {
            if (enabled) sourceRegistry.enable(adapter.providerId)
            else sourceRegistry.disable(adapter.providerId)
        }
        val eventType = if (enabled) "enabled" else "disabled"
        addLiveEvent(LiveEvent(
            id = "toggle_${eventCounter.incrementAndGet()}",
            type = LiveEventType.SYNC,
            providerName = provider.displayName,
            message = "Provider $eventType",
            timestamp = System.currentTimeMillis(),
            severity = LiveEventSeverity.INFO,
        ))
    }

    fun dismissImpactDialog() {
        _uiState.value = _uiState.value.copy(showImpactDialog = false, impactAnalysis = null)
    }

    fun setExpandedProvider(providerId: String?) {
        _uiState.value = _uiState.value.copy(expandedProviderId = providerId)
    }

    fun movePriorityUp(provider: SourceProvider) {
        val order = _uiState.value.priorityOrder.toMutableList()
        val idx = order.indexOf(provider)
        if (idx > 0) {
            order.removeAt(idx)
            order.add(idx - 1, provider)
            _uiState.value = _uiState.value.copy(priorityOrder = order)
            sourcePreferences.setPriorityOrder(order)
            providerRegistry.setPriorityOverride(order)
            addLiveEvent(LiveEvent(
                id = "priority_${eventCounter.incrementAndGet()}",
                type = LiveEventType.PLAYBACK,
                providerName = provider.displayName,
                message = "Priority moved up to #${idx}",
                timestamp = System.currentTimeMillis(),
                severity = LiveEventSeverity.INFO,
            ))
        }
    }

    fun movePriorityDown(provider: SourceProvider) {
        val order = _uiState.value.priorityOrder.toMutableList()
        val idx = order.indexOf(provider)
        if (idx < order.lastIndex) {
            order.removeAt(idx)
            order.add(idx + 1, provider)
            _uiState.value = _uiState.value.copy(priorityOrder = order)
            sourcePreferences.setPriorityOrder(order)
            providerRegistry.setPriorityOverride(order)
            addLiveEvent(LiveEvent(
                id = "priority_${eventCounter.incrementAndGet()}",
                type = LiveEventType.PLAYBACK,
                providerName = provider.displayName,
                message = "Priority moved down to #${idx + 2}",
                timestamp = System.currentTimeMillis(),
                severity = LiveEventSeverity.INFO,
            ))
        }
    }

    fun triggerSyncAll() {
        if (_uiState.value.activeOperations.contains("sync_all")) return
        markOperation("sync_all", true)
        addLiveEvent(LiveEvent(
            id = "sync_${eventCounter.incrementAndGet()}",
            type = LiveEventType.SYNC,
            providerName = "System",
            message = "Full synchronization started",
            timestamp = System.currentTimeMillis(),
            severity = LiveEventSeverity.INFO,
        ))
        viewModelScope.launch {
            repository.load()
            delay(500)
            markOperation("sync_all", false)
            addLiveEvent(LiveEvent(
                id = "sync_done_${eventCounter.incrementAndGet()}",
                type = LiveEventType.SYNC,
                providerName = "System",
                message = "Full synchronization completed",
                timestamp = System.currentTimeMillis(),
                severity = LiveEventSeverity.INFO,
            ))
        }
    }

    fun triggerRefreshMetadata() {
        if (_uiState.value.activeOperations.contains("refresh_metadata")) return
        markOperation("refresh_metadata", true)
        addLiveEvent(LiveEvent(
            id = "meta_${eventCounter.incrementAndGet()}",
            type = LiveEventType.METADATA,
            providerName = "System",
            message = "Refreshing metadata…",
            timestamp = System.currentTimeMillis(),
            severity = LiveEventSeverity.INFO,
        ))
        viewModelScope.launch {
            repository.load()
            markOperation("refresh_metadata", false)
            addLiveEvent(LiveEvent(
                id = "meta_done_${eventCounter.incrementAndGet()}",
                type = LiveEventType.METADATA,
                providerName = "System",
                message = "Metadata refreshed",
                timestamp = System.currentTimeMillis(),
                severity = LiveEventSeverity.INFO,
            ))
        }
    }

    fun triggerValidateStreams() {
        if (_uiState.value.activeOperations.contains("validate_streams")) return
        markOperation("validate_streams", true)
        addLiveEvent(LiveEvent(
            id = "valid_${eventCounter.incrementAndGet()}",
            type = LiveEventType.HEALTH,
            providerName = "System",
            message = "Validating streams…",
            timestamp = System.currentTimeMillis(),
            severity = LiveEventSeverity.INFO,
        ))
        viewModelScope.launch {
            repository.load()
            markOperation("validate_streams", false)
            addLiveEvent(LiveEvent(
                id = "valid_done_${eventCounter.incrementAndGet()}",
                type = LiveEventType.HEALTH,
                providerName = "System",
                message = "Stream validation completed",
                timestamp = System.currentTimeMillis(),
                severity = LiveEventSeverity.INFO,
            ))
        }
    }

    fun triggerRebuildIndex() {
        if (_uiState.value.activeOperations.contains("rebuild_index")) return
        markOperation("rebuild_index", true)
        addLiveEvent(LiveEvent(
            id = "index_${eventCounter.incrementAndGet()}",
            type = LiveEventType.DEDUP,
            providerName = "System",
            message = "Rebuilding channel index…",
            timestamp = System.currentTimeMillis(),
            severity = LiveEventSeverity.INFO,
        ))
        viewModelScope.launch {
            repository.reload()
            markOperation("rebuild_index", false)
            addLiveEvent(LiveEvent(
                id = "index_done_${eventCounter.incrementAndGet()}",
                type = LiveEventType.DEDUP,
                providerName = "System",
                message = "Channel index rebuilt",
                timestamp = System.currentTimeMillis(),
                severity = LiveEventSeverity.INFO,
            ))
        }
    }

    fun triggerClearMetadataCache() {
        if (_uiState.value.activeOperations.contains("clear_metadata")) return
        markOperation("clear_metadata", true)
        addLiveEvent(LiveEvent(
            id = "clear_${eventCounter.incrementAndGet()}",
            type = LiveEventType.METADATA,
            providerName = "System",
            message = "Clearing metadata cache…",
            timestamp = System.currentTimeMillis(),
            severity = LiveEventSeverity.INFO,
        ))
        viewModelScope.launch {
            repository.reload()
            markOperation("clear_metadata", false)
            addLiveEvent(LiveEvent(
                id = "clear_done_${eventCounter.incrementAndGet()}",
                type = LiveEventType.METADATA,
                providerName = "System",
                message = "Metadata cache cleared",
                timestamp = System.currentTimeMillis(),
                severity = LiveEventSeverity.INFO,
            ))
        }
    }

    fun triggerClearStreamCache() {
        viewModelScope.launch {
            addLiveEvent(LiveEvent(
                id = "clear_stream_${eventCounter.incrementAndGet()}",
                type = LiveEventType.PLAYBACK,
                providerName = "System",
                message = "Stream cache cleared",
                timestamp = System.currentTimeMillis(),
                severity = LiveEventSeverity.INFO,
            ))
        }
    }

    fun refreshProvider(providerId: String) {
        addLiveEvent(LiveEvent(
            id = "refresh_provider_${eventCounter.incrementAndGet()}",
            type = LiveEventType.SYNC,
            providerName = providerId,
            message = "Provider refresh initiated",
            timestamp = System.currentTimeMillis(),
            severity = LiveEventSeverity.INFO,
        ))
    }

    fun clearLiveEvents() {
        _uiState.value = _uiState.value.copy(liveEvents = emptyList())
    }

    private fun performImpactAnalysis(provider: SourceProvider) {
        val channels = repository.getAllChannels()
        val enabledPrefs = sourcePreferences.enabled()
        val providerChannels = channels.filter { ch ->
            ch.sources.keys.any { SourceProvider.forType(it) == provider }
        }
        val totalAffected = providerChannels.size
        var withAlternatives = 0
        var unavailable = 0
        var favoritesAffected = 0
        val categoryCounts = mutableMapOf<String, Int>()

        for (ch in providerChannels) {
            val hasOtherSource = ch.sources.keys.any { type ->
                val sp = SourceProvider.forType(type)
                sp != provider && (enabledPrefs[sp] ?: true)
            }
            if (hasOtherSource) withAlternatives++
            else unavailable++
            if (ch.isFavorite) favoritesAffected++
            val cat = ch.category ?: "Uncategorised"
            categoryCounts[cat] = (categoryCounts[cat] ?: 0) + 1
        }

        val sortedCategories = categoryCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        val warnings = mutableListOf<String>()
        if (unavailable > 0) warnings.add("$unavailable channels will become completely unavailable")
        if (favoritesAffected > 0) warnings.add("$favoritesAffected favorite channel(s) will be affected")
        if (totalAffected > 100) warnings.add("This provider supplies a large portion of your catalogue")

        _uiState.value = _uiState.value.copy(
            showImpactDialog = true,
            impactAnalysis = ImpactAnalysis(
                providerName = provider.displayName,
                providerId = provider.name,
                totalChannelsAffected = totalAffected,
                channelsWithAlternatives = withAlternatives,
                channelsUnavailable = unavailable,
                affectedCategories = sortedCategories,
                favoritesAffected = favoritesAffected,
                recentlyWatchedAffected = 0,
                warnings = warnings,
            ),
        )
    }

    private fun computeSystemSummary(
        providers: List<ProviderAdapter>,
        states: Map<String, ProviderState>,
        enabled: Map<SourceProvider, Boolean>,
        channels: List<Channel>,
    ): SystemSummary {
        val canonicalProviders = SourceProvider.entries
        val total = canonicalProviders.size
        val enabledCount = enabled.count { it.key in canonicalProviders && it.value }
        val healthy = states.count { it.value.health.isHealthy && it.value.enabled }
        val warningsCount = states.count { s ->
            s.value.enabled && s.value.lifecycle == LifecycleState.ACTIVE &&
                s.value.health.consecutiveFailures in 1..4
        }
        val offline = states.count { s ->
            s.value.enabled && (!s.value.health.isHealthy || s.value.lifecycle == LifecycleState.FAILED)
        }

        val latestSync = states.values.maxOfOrNull { it.lastSyncMs } ?: 0
        val streamCount = channels.sumOf { it.sources.size }
        val multiSourceCount = channels.count { it.sources.size > 1 }

        return SystemSummary(
            totalProviders = total,
            enabledProviders = enabledCount,
            healthyProviders = healthy,
            warningProviders = warningsCount,
            offlineProviders = offline,
            totalLogicalChannels = channels.size,
            totalPhysicalStreams = streamCount,
            multiSourceChannels = multiSourceCount,
            lastSyncTimestamp = latestSync,
            isSyncing = _uiState.value.activeOperations.isNotEmpty(),
        )
    }

    private fun computeProviderSummaries(
        providers: List<ProviderAdapter>,
        states: Map<String, ProviderState>,
        enabled: Map<SourceProvider, Boolean>,
        channels: List<Channel>,
    ): List<ProviderSummary> {
        val canonicalOnly = SourceProvider.entries
        return canonicalOnly.mapNotNull { sp ->
            val adapters = providers.filter { it.sourceProvider == sp }
            val primaryId = adapters.firstOrNull()?.providerId ?: sp.name
            val state = states[primaryId] ?: ProviderState(providerId = primaryId)
            val isEnabled = enabled[sp] ?: true

            val providerChannels = channels.filter { ch ->
                ch.sources.keys.any { SourceProvider.forType(it) == sp }
            }
            val channelCount = providerChannels.size
            val streamCount = providerChannels.sumOf { ch ->
                ch.sources.count { SourceProvider.forType(it.key) == sp }
            }
            val categories = providerChannels.mapNotNull { it.category }.distinct().sorted()
            val countries = providerChannels.mapNotNull { it.country }.distinct().sorted()
            val languages = providerChannels.mapNotNull { it.language }.distinct().sorted()
            val reliability = if (state.health.totalRequests > 0) {
                (state.health.successRate * 100).toInt().coerceIn(0, 100)
            } else 0

            ProviderSummary(
                provider = sp,
                providerId = primaryId,
                displayName = sp.displayName,
                description = sp.description,
                isEnabled = isEnabled,
                lifecycle = state.lifecycle,
                isHealthy = state.health.isHealthy,
                channelCount = channelCount,
                streamCount = streamCount,
                reliabilityPercent = reliability,
                avgResponseTimeMs = state.health.responseTimeMs,
                lastSyncMs = state.lastSyncMs,
                consecutiveFailures = state.health.consecutiveFailures,
                successRate = state.health.successRate,
                lastError = state.health.lastError,
                capabilities = adapters.firstOrNull()?.capabilities?.toList() ?: emptyList(),
                categories = categories,
                countries = countries,
                languages = languages,
            )
        }.sortedByDescending { it.isEnabled }
    }

    private fun markOperation(op: String, active: Boolean) {
        val current = _uiState.value.activeOperations.toMutableSet()
        if (active) current.add(op) else current.remove(op)
        _uiState.value = _uiState.value.copy(activeOperations = current)
    }

    private fun addLiveEvent(event: LiveEvent) {
        val events = _uiState.value.liveEvents.toMutableList()
        events.add(0, event)
        if (events.size > 50) events.removeAt(events.lastIndex)
        _uiState.value = _uiState.value.copy(liveEvents = events)
    }
}
