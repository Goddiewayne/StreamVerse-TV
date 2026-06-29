package com.streamverse.core.data.source

import android.util.Log
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.domain.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRegistry @Inject constructor() {
    private val providerMap = ConcurrentHashMap<String, ProviderAdapter>()
    private val providerStates = ConcurrentHashMap<String, ProviderState>()
    private val _allProviders = MutableStateFlow<List<ProviderAdapter>>(emptyList())
    private val _allStates = MutableStateFlow<Map<String, ProviderState>>(emptyMap())

    val allProviders: StateFlow<List<ProviderAdapter>> = _allProviders.asStateFlow()
    val allStates: StateFlow<Map<String, ProviderState>> = _allStates.asStateFlow()

    private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun register(provider: ProviderAdapter) {
        val id = provider.providerId
        if (providerMap.containsKey(id)) return
        providerMap[id] = provider
        providerStates[id] = ProviderState(
            providerId = id,
            lifecycle = LifecycleState.REGISTERED,
            enabled = true,
        )
        emitSnapshots()
        Log.d("SourceRegistry", "Registered provider: $id (${provider.displayName})")
    }

    fun unregister(providerId: String) {
        providerMap.remove(providerId)?.let {
            registryScope.launch { it.shutdown() }
        }
        providerStates.remove(providerId)
        emitSnapshots()
        Log.d("SourceRegistry", "Unregistered provider: $providerId")
    }

    fun getProvider(providerId: String): ProviderAdapter? = providerMap[providerId]

    fun getProvidersForSourceType(sourceType: SourceType): List<ProviderAdapter> =
        providerMap.values.filter { it.primarySourceType == sourceType }

    fun getProvidersForSourceProvider(sourceProvider: SourceProvider): List<ProviderAdapter> =
        providerMap.values.filter { it.sourceProvider == sourceProvider }

    fun getProviderState(providerId: String): ProviderState =
        providerStates[providerId] ?: ProviderState(providerId = providerId, lifecycle = LifecycleState.UNREGISTERED, enabled = false)

    suspend fun initializeAll() {
        for (provider in providerMap.values) {
            val id = provider.providerId
            updateState(id) { copy(lifecycle = LifecycleState.INITIALIZING) }
            try {
                provider.initialize()
                updateState(id) { copy(lifecycle = LifecycleState.ACTIVE) }
                Log.d("SourceRegistry", "Initialized: $id")
            } catch (e: Exception) {
                Log.w("SourceRegistry", "Failed to initialize $id: ${e.message}")
                updateState(id) {
                    copy(
                        lifecycle = LifecycleState.FAILED,
                        health = health.copy(
                            isHealthy = false,
                            lastError = e.message,
                            consecutiveFailures = health.consecutiveFailures + 1,
                        ),
                    )
                }
            }
        }
    }

    suspend fun shutdownAll() {
        for (provider in providerMap.values) {
            try {
                provider.shutdown()
                updateState(provider.providerId) { copy(lifecycle = LifecycleState.SHUTDOWN) }
            } catch (_: Exception) { }
        }
    }

    fun enable(providerId: String) {
        updateState(providerId) { copy(enabled = true) }
    }

    fun disable(providerId: String) {
        updateState(providerId) { copy(enabled = false) }
    }

    fun isEnabled(providerId: String): Boolean =
        providerStates[providerId]?.enabled != false

    fun recordSuccess(providerId: String) {
        updateState(providerId) {
            copy(
                health = health.copy(
                    isHealthy = true,
                    consecutiveFailures = 0,
                    totalRequests = health.totalRequests + 1,
                    successfulRequests = health.successfulRequests + 1,
                    lastSuccessMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun recordFailure(providerId: String, error: String?) {
        updateState(providerId) {
            copy(
                health = health.copy(
                    isHealthy = health.consecutiveFailures < 5,
                    consecutiveFailures = health.consecutiveFailures + 1,
                    totalRequests = health.totalRequests + 1,
                    lastError = error,
                    lastErrorMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun recordSyncComplete(providerId: String, channelCount: Int) {
        updateState(providerId) {
            copy(lastSyncMs = System.currentTimeMillis(), totalChannels = channelCount)
        }
    }

    private fun updateState(providerId: String, transform: ProviderState.() -> ProviderState) {
        val current = providerStates[providerId] ?: ProviderState(providerId = providerId)
        providerStates[providerId] = current.transform()
        emitSnapshots()
    }

    private fun emitSnapshots() {
        _allProviders.value = providerMap.values.toList()
        _allStates.value = providerStates.entries.associate { it.key to it.value }
    }
}
