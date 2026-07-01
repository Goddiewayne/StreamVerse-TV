package com.streamverse.core.data.source

import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.model.DlhdChannel
import com.streamverse.core.data.model.RadioStation
import com.streamverse.core.data.model.StmifyChannel
import com.streamverse.core.data.remote.broadcaster.BroadcasterClient
import com.streamverse.core.data.remote.dlhd.DlhdClient
import com.streamverse.core.data.remote.free.FreeChannel
import com.streamverse.core.data.remote.free.FreeLiveClient
import com.streamverse.core.data.remote.hosted.HostedIndexClient
import com.streamverse.core.data.remote.iptv.IptvChannel
import com.streamverse.core.data.remote.radio.RadioBrowserClient
import com.streamverse.core.data.remote.stmify.PrimeVideoClient
import com.streamverse.core.data.remote.stmify.StmifyClient
import com.streamverse.core.data.remote.youtube.YouTubeTvChannel
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GlobalIndexProviderAdapter @Inject constructor(
    private val hostedIndexClient: HostedIndexClient,
    private val dispatchers: StreamVerseDispatchers,
) : ProviderAdapter {
    override val providerId = "global_index"
    override val displayName = "Global Channels"
    override val sourceProvider = SourceProvider.GLOBAL_INDEX
    override val primarySourceType = SourceType.GLOBAL_INDEX
    override val capabilities = setOf(ProviderCapability.CHANNEL_DISCOVERY)

    private val _state = MutableStateFlow(ProviderState(providerId = providerId, lifecycle = LifecycleState.REGISTERED))
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    override suspend fun discoverChannels(): List<SourceChannelDTO> = withContext(dispatchers.io) {
        hostedIndexClient.fetchAll().map { ch ->
            SourceChannelDTO(
                providerId = providerId,
                referenceId = ch.id,
                name = ch.name,
                streamUrl = ch.streamUrl,
                logoUrl = ch.logoUrl,
                category = ch.category,
                country = ch.country,
                language = ch.language,
                quality = ch.quality,
                headers = ch.headers ?: emptyMap(),
                drmKeyId = ch.drmKeyId,
                drmKey = ch.drmKey,
            )
        }
    }

    override suspend fun refreshMetadata(channelRefIds: List<String>): Map<String, SourceChannelDTO> = emptyMap()
    override suspend fun refreshStreams(channelRefIds: List<String>): Map<String, String?> = emptyMap()
    override suspend fun validateStream(referenceId: String): StreamValidation = StreamValidation(referenceId, false)
    override suspend fun healthCheck(): ProviderHealth = ProviderHealth(isHealthy = true)
    override suspend fun initialize() { _state.value = _state.value.copy(lifecycle = LifecycleState.ACTIVE) }
    override suspend fun shutdown() { _state.value = _state.value.copy(lifecycle = LifecycleState.SHUTDOWN) }
}

class DlhdProviderAdapter @Inject constructor(
    private val dlhdClient: DlhdClient,
    private val dispatchers: StreamVerseDispatchers,
) : ProviderAdapter {
    override val providerId = "sports_events"
    override val displayName = "Sports & Events"
    override val sourceProvider = SourceProvider.SPORTS_EVENTS
    override val primarySourceType = SourceType.SPORTS_EVENTS
    override val capabilities = setOf(ProviderCapability.CHANNEL_DISCOVERY, ProviderCapability.STREAM_REFRESH)

    private val _state = MutableStateFlow(ProviderState(providerId = providerId, lifecycle = LifecycleState.REGISTERED))
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    override suspend fun discoverChannels(): List<SourceChannelDTO> = withContext(dispatchers.io) {
        dlhdClient.fetchChannelsFromScrape().getOrDefault(emptyList()).map { it.toDTO() }
    }

    override suspend fun refreshMetadata(channelRefIds: List<String>): Map<String, SourceChannelDTO> = emptyMap()
    override suspend fun refreshStreams(channelRefIds: List<String>): Map<String, String?> = emptyMap()
    override suspend fun validateStream(referenceId: String): StreamValidation = StreamValidation(referenceId, false)
    override suspend fun healthCheck(): ProviderHealth = ProviderHealth(isHealthy = true)
    override suspend fun initialize() { _state.value = _state.value.copy(lifecycle = LifecycleState.ACTIVE) }
    override suspend fun shutdown() { _state.value = _state.value.copy(lifecycle = LifecycleState.SHUTDOWN) }
}

class StmifyProviderAdapter @Inject constructor(
    private val stmifyClient: StmifyClient,
    private val primeVideoClient: PrimeVideoClient,
    private val dispatchers: StreamVerseDispatchers,
) : ProviderAdapter {
    override val providerId = "world_tv"
    override val displayName = "World TV"
    override val sourceProvider = SourceProvider.WORLD_TV
    override val primarySourceType = SourceType.WORLD_TV
    override val capabilities = setOf(ProviderCapability.CHANNEL_DISCOVERY, ProviderCapability.SEARCH, ProviderCapability.STREAM_REFRESH)

    private val _state = MutableStateFlow(ProviderState(providerId = providerId, lifecycle = LifecycleState.REGISTERED))
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    override suspend fun discoverChannels(): List<SourceChannelDTO> = withContext(dispatchers.io) {
        val a = stmifyClient.fetchChannels().getOrDefault(emptyList())
        val b = stmifyClient.fetchChannelsFromArchive().getOrDefault(emptyList())
        val c = primeVideoClient.fetchChannels().getOrDefault(emptyList())
        (a + b + c).distinctBy { it.id }.map { it.toDTO() }
    }

    override suspend fun refreshMetadata(channelRefIds: List<String>): Map<String, SourceChannelDTO> = emptyMap()
    override suspend fun refreshStreams(channelRefIds: List<String>): Map<String, String?> = emptyMap()
    override suspend fun validateStream(referenceId: String): StreamValidation = StreamValidation(referenceId, false)
    override suspend fun healthCheck(): ProviderHealth = ProviderHealth(isHealthy = true)
    override suspend fun initialize() { _state.value = _state.value.copy(lifecycle = LifecycleState.ACTIVE) }
    override suspend fun shutdown() { _state.value = _state.value.copy(lifecycle = LifecycleState.SHUTDOWN) }
}

class RadioProviderAdapter @Inject constructor(
    private val radioBrowserClient: RadioBrowserClient,
    private val dispatchers: StreamVerseDispatchers,
) : ProviderAdapter {
    override val providerId = "radio_browser"
    override val displayName = "Radio Browser"
    override val sourceProvider = SourceProvider.RADIO
    override val primarySourceType = SourceType.RADIO
    override val capabilities = setOf(ProviderCapability.CHANNEL_DISCOVERY)

    private val _state = MutableStateFlow(ProviderState(providerId = providerId, lifecycle = LifecycleState.REGISTERED))
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    override suspend fun discoverChannels(): List<SourceChannelDTO> = withContext(dispatchers.io) {
        radioBrowserClient.fetchStations().getOrDefault(emptyList()).map { it.toDTO() }
    }

    override suspend fun refreshMetadata(channelRefIds: List<String>): Map<String, SourceChannelDTO> = emptyMap()
    override suspend fun refreshStreams(channelRefIds: List<String>): Map<String, String?> = emptyMap()
    override suspend fun validateStream(referenceId: String): StreamValidation = StreamValidation(referenceId, false)
    override suspend fun healthCheck(): ProviderHealth = ProviderHealth(isHealthy = true)
    override suspend fun initialize() { _state.value = _state.value.copy(lifecycle = LifecycleState.ACTIVE) }
    override suspend fun shutdown() { _state.value = _state.value.copy(lifecycle = LifecycleState.SHUTDOWN) }
}



class BroadcasterProviderAdapter @Inject constructor(
    private val broadcasterClient: BroadcasterClient,
    private val dispatchers: StreamVerseDispatchers,
) : ProviderAdapter {
    override val providerId = "broadcaster"
    override val displayName = "Official Broadcasters"
    override val sourceProvider = SourceProvider.BROADCASTER
    override val primarySourceType = SourceType.BROADCASTER
    override val capabilities = setOf(ProviderCapability.CHANNEL_DISCOVERY)

    private val _state = MutableStateFlow(ProviderState(providerId = providerId, lifecycle = LifecycleState.REGISTERED))
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    override suspend fun discoverChannels(): List<SourceChannelDTO> = withContext(dispatchers.io) {
        broadcasterClient.fetchChannels().map { it.toDTO(providerId) }
    }

    override suspend fun refreshMetadata(channelRefIds: List<String>): Map<String, SourceChannelDTO> = emptyMap()
    override suspend fun refreshStreams(channelRefIds: List<String>): Map<String, String?> = emptyMap()
    override suspend fun validateStream(referenceId: String): StreamValidation = StreamValidation(referenceId, false)
    override suspend fun healthCheck(): ProviderHealth = ProviderHealth(isHealthy = true)
    override suspend fun initialize() { _state.value = _state.value.copy(lifecycle = LifecycleState.ACTIVE) }
    override suspend fun shutdown() { _state.value = _state.value.copy(lifecycle = LifecycleState.SHUTDOWN) }
}

class FreeChannelProviderAdapter @Inject constructor(
    private val freeLiveClient: FreeLiveClient,
    private val dispatchers: StreamVerseDispatchers,
) : ProviderAdapter {
    override val providerId = "free_channel"
    override val displayName = "Free Streaming Services"
    override val sourceProvider = SourceProvider.FREE_CHANNEL
    override val primarySourceType = SourceType.FREE_CHANNEL
    override val capabilities = setOf(ProviderCapability.CHANNEL_DISCOVERY)

    private val _state = MutableStateFlow(ProviderState(providerId = providerId, lifecycle = LifecycleState.REGISTERED))
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    override suspend fun discoverChannels(): List<SourceChannelDTO> = withContext(dispatchers.io) {
        freeLiveClient.fetchChannels().getOrDefault(emptyList()).map { it.toDTO(providerId) }
    }

    override suspend fun refreshMetadata(channelRefIds: List<String>): Map<String, SourceChannelDTO> = emptyMap()
    override suspend fun refreshStreams(channelRefIds: List<String>): Map<String, String?> = emptyMap()
    override suspend fun validateStream(referenceId: String): StreamValidation = StreamValidation(referenceId, false)
    override suspend fun healthCheck(): ProviderHealth = ProviderHealth(isHealthy = true)
    override suspend fun initialize() { _state.value = _state.value.copy(lifecycle = LifecycleState.ACTIVE) }
    override suspend fun shutdown() { _state.value = _state.value.copy(lifecycle = LifecycleState.SHUTDOWN) }
}

class YouTubeProviderAdapter @Inject constructor(
    private val youtubeTvClient: com.streamverse.core.data.remote.youtube.YouTubeTvClient,
    private val dispatchers: StreamVerseDispatchers,
) : ProviderAdapter {
    override val providerId = "youtube_tv"
    override val displayName = "YouTube TV"
    override val sourceProvider = SourceProvider.YOUTUBE_TV
    override val primarySourceType = SourceType.YOUTUBE_TV
    override val capabilities = setOf(ProviderCapability.CHANNEL_DISCOVERY)

    private val _state = MutableStateFlow(ProviderState(providerId = providerId, lifecycle = LifecycleState.REGISTERED))
    override val state: StateFlow<ProviderState> = _state.asStateFlow()

    override suspend fun discoverChannels(): List<SourceChannelDTO> = withContext(dispatchers.io) {
        youtubeTvClient.discoverChannels().map { it.toDTO() }
    }

    override suspend fun refreshMetadata(channelRefIds: List<String>): Map<String, SourceChannelDTO> = emptyMap()
    override suspend fun refreshStreams(channelRefIds: List<String>): Map<String, String?> = emptyMap()
    override suspend fun validateStream(referenceId: String): StreamValidation = StreamValidation(referenceId, false)
    override suspend fun healthCheck(): ProviderHealth = ProviderHealth(isHealthy = true)
    override suspend fun initialize() { _state.value = _state.value.copy(lifecycle = LifecycleState.ACTIVE) }
    override suspend fun shutdown() { _state.value = _state.value.copy(lifecycle = LifecycleState.SHUTDOWN) }
}

private fun IptvChannel.toDTO(providerId: String) = SourceChannelDTO(
    providerId = providerId,
    referenceId = id,
    name = name,
    streamUrl = streamUrl,
    logoUrl = logoUrl,
    category = category,
    country = country,
    language = language,
    quality = quality,
    headers = headers,
    drmKeyId = drmKeyId,
    drmKey = drmKey,
)

private fun FreeChannel.toDTO(providerId: String) = SourceChannelDTO(
    providerId = providerId,
    referenceId = id,
    name = name,
    streamUrl = streamUrl,
    logoUrl = logoUrl,
    category = category,
    country = country,
    headers = headers,
    drmKeyId = drmKeyId,
    drmKey = drmKey,
)

private fun DlhdChannel.toDTO() = SourceChannelDTO(
    providerId = "sports_events",
    referenceId = id,
    name = name,
    logoUrl = logoUrl,
    category = category,
)

private fun StmifyChannel.toDTO() = SourceChannelDTO(
    providerId = "stmify",
    referenceId = id,
    name = name,
    logoUrl = logoUrl,
    quality = quality?.name,
    description = description,
    category = genres.firstOrNull(),
    aliases = listOfNotNull(slug),
)

private fun RadioStation.toDTO() = SourceChannelDTO(
    providerId = "radio_browser",
    referenceId = id,
    name = name,
    streamUrl = streamUrl,
    logoUrl = logoUrl,
    country = countryCode,
    language = language,
)

private fun YouTubeTvChannel.toDTO() = SourceChannelDTO(
    providerId = "youtube_tv",
    referenceId = referenceId,
    name = displayName,
    streamUrl = liveUrl,
    category = category,
    country = country,
    language = language,
)
