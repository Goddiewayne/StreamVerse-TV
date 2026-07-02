package com.streamverse.core.data

import com.streamverse.core.domain.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _availabilityMap = MutableStateFlow<Map<String, ChannelAvailabilityState>>(emptyMap())
    val availabilityMap: StateFlow<Map<String, ChannelAvailabilityState>> = _availabilityMap.asStateFlow()

    fun getAvailability(channelId: String): ChannelAvailabilityState =
        _availabilityMap.value[channelId] ?: ChannelAvailabilityState()

    fun isLive(channelId: String): Boolean = healthEngine.isLive(channelId)
}
