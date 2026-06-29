package com.streamverse.core.data.source

import com.streamverse.core.domain.model.SourceType

enum class ProviderCapability {
    CHANNEL_DISCOVERY,
    METADATA_REFRESH,
    STREAM_REFRESH,
    STREAM_VALIDATION,
    EPG_SUPPLY,
    LOGO_SUPPLY,
    SEARCH,
    HEALTH_CHECK,
}

enum class LifecycleState {
    UNREGISTERED,
    REGISTERED,
    INITIALIZING,
    ACTIVE,
    FAILED,
    DISABLED,
    SHUTDOWN,
}

data class ProviderHealth(
    val isHealthy: Boolean = true,
    val responseTimeMs: Long = -1,
    val consecutiveFailures: Int = 0,
    val totalRequests: Long = 0,
    val successfulRequests: Long = 0,
    val lastSuccessMs: Long = 0,
    val lastError: String? = null,
    val lastErrorMs: Long = 0,
) {
    val successRate: Float
        get() = if (totalRequests == 0L) 1f else successfulRequests.toFloat() / totalRequests
}

data class ProviderState(
    val providerId: String,
    val lifecycle: LifecycleState = LifecycleState.UNREGISTERED,
    val health: ProviderHealth = ProviderHealth(),
    val lastSyncMs: Long = 0,
    val totalChannels: Int = 0,
    val enabled: Boolean = true,
)

data class RefreshResult(
    val providerId: String,
    val channelsFound: Int,
    val channelsUpdated: Int,
    val channelsNew: Int,
    val durationMs: Long,
    val success: Boolean,
    val error: String? = null,
)

data class StreamValidation(
    val instanceId: String,
    val isPlayable: Boolean,
    val latencyMs: Long = -1,
    val resolvedUrl: String? = null,
    val error: String? = null,
)

data class SourceChannelDTO(
    val providerId: String,
    val referenceId: String,
    val name: String,
    val streamUrl: String? = null,
    val logoUrl: String? = null,
    val category: String? = null,
    val country: String? = null,
    val language: String? = null,
    val quality: String? = null,
    val description: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drmKeyId: String? = null,
    val drmKey: String? = null,
    val aliases: List<String> = emptyList(),
)
