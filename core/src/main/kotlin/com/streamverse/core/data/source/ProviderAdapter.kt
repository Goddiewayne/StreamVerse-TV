package com.streamverse.core.data.source

import com.streamverse.core.data.SourceProvider
import com.streamverse.core.domain.model.SourceType
import kotlinx.coroutines.flow.StateFlow

interface ProviderAdapter {
    val providerId: String
    val displayName: String
    val sourceProvider: SourceProvider
    val primarySourceType: SourceType
    val capabilities: Set<ProviderCapability>
    val state: StateFlow<ProviderState>

    suspend fun discoverChannels(): List<SourceChannelDTO>
    suspend fun refreshMetadata(channelRefIds: List<String>): Map<String, SourceChannelDTO>
    suspend fun refreshStreams(channelRefIds: List<String>): Map<String, String?>
    suspend fun validateStream(referenceId: String): StreamValidation
    suspend fun healthCheck(): ProviderHealth
    suspend fun initialize()
    suspend fun shutdown()
}
