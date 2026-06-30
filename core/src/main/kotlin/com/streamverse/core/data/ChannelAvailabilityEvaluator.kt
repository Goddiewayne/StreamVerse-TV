package com.streamverse.core.data

import com.streamverse.core.domain.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ChannelAvailability {
    UNKNOWN,
    LIVE,
    UNAVAILABLE,
}

data class ChannelAvailabilityState(
    val availability: ChannelAvailability = ChannelAvailability.UNKNOWN,
    val availableSources: Set<SourceType> = emptySet(),
    val unavailableSources: Set<SourceType> = emptySet(),
    val verifyingSources: Set<SourceType> = emptySet(),
)

@Singleton
class ChannelAvailabilityEvaluator @Inject constructor(
    private val healthEngine: ChannelHealthEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _availabilityMap = MutableStateFlow<Map<String, ChannelAvailabilityState>>(emptyMap())
    val availabilityMap: StateFlow<Map<String, ChannelAvailabilityState>> = _availabilityMap.asStateFlow()

    init {
        scope.launch {
            healthEngine.sourceHealthUpdates
                .collectLatest { sourceHealthMap ->
                    val newMap = mutableMapOf<String, ChannelAvailabilityState>()

                    for ((channelId, perSource) in sourceHealthMap) {
                        val avail = mutableSetOf<SourceType>()
                        val unavail = mutableSetOf<SourceType>()
                        val verifying = mutableSetOf<SourceType>()

                        for ((type, health) in perSource) {
                            when (health.state) {
                                SourceHealthState.AVAILABLE -> avail.add(type)
                                SourceHealthState.UNAVAILABLE -> unavail.add(type)
                                SourceHealthState.VERIFYING -> verifying.add(type)
                                SourceHealthState.UNKNOWN -> {}
                            }
                        }

                        val availability = when {
                            avail.isNotEmpty() -> ChannelAvailability.LIVE
                            unavail.size == perSource.size && perSource.isNotEmpty() -> ChannelAvailability.UNAVAILABLE
                            else -> ChannelAvailability.UNKNOWN
                        }

                        newMap[channelId] = ChannelAvailabilityState(
                            availability = availability,
                            availableSources = avail,
                            unavailableSources = unavail,
                            verifyingSources = verifying,
                        )
                    }

                    _availabilityMap.value = newMap
                }
        }
    }

    fun getAvailability(channelId: String): ChannelAvailabilityState =
        _availabilityMap.value[channelId] ?: ChannelAvailabilityState()

    fun isLive(channelId: String): Boolean = healthEngine.isLive(channelId)
}
