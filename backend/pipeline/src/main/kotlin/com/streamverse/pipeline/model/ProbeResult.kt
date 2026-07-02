package com.streamverse.pipeline.model

data class ProbeTelemetry(
    val probeId: String,
    val channelId: String,
    val sourceType: SourceType,
    val streamUrl: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val durationMs: Long,
    val success: Boolean,
    val manifestAvailable: Boolean = false,
    val playlistValid: Boolean = false,
    val segmentAccessible: Boolean = false,
    val playbackStartupMs: Long = -1,
    val decoderCompatible: Boolean = false,
    val resolution: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val codec: String? = null,
    val bitrate: Long = -1,
    val encrypted: Boolean = false,
    val requiresAuth: Boolean = false,
    val httpStatus: Int = -1,
    val redirectChain: List<String> = emptyList(),
    val failureReason: String? = null,
    val mimeType: String? = null,
    val workerId: String = "",
)

data class ProbeJob(
    val channelId: String,
    val sourceType: SourceType,
    val streamUrl: String,
    val probeId: String,
    val priority: Int = 0,
    val scheduledAtMs: Long = System.currentTimeMillis(),
)
