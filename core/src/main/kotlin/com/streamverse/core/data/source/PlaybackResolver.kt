package com.streamverse.core.data.source

import android.util.Log
import com.streamverse.core.data.SourceHealthPreferences
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.SourceResolutionEngine
import com.streamverse.core.util.StreamInfo
import com.streamverse.core.util.StreamResolver
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedPlayback(
    val streamInfo: StreamInfo,
    val sourceType: SourceType,
    val sourceInfo: SourceInfo,
    val instanceId: String,
    val fromHealthCache: Boolean = false,
    val isFallback: Boolean = false,
)

data class FailoverResult(
    val playback: ResolvedPlayback,
    val attempts: List<ResolvedPlayback>,
    val allSourcesExhausted: Boolean,
)

@Singleton
class PlaybackResolver @Inject constructor(
    private val streamResolver: StreamResolver,
    private val sourceResolutionEngine: SourceResolutionEngine,
    private val sourceHealthPreferences: SourceHealthPreferences,
    private val sourceRegistry: SourceRegistry,
    private val dispatchers: StreamVerseDispatchers,
) {
    companion object {
        private const val PER_SOURCE_TIMEOUT_MS = 15_000L
        private const val MAX_RETRIES_PER_SOURCE = 2
    }

    data class ResolveAttempt(
        val sourceType: SourceType,
        val sourceInfo: SourceInfo,
        val result: Result<StreamInfo>? = null,
        val latencyMs: Long = -1,
    )

    suspend fun resolveBest(
        channel: Channel,
        preferredQuality: Quality? = null,
        skipSources: Set<SourceType> = emptySet(),
        maxAttempts: Int = 5,
    ): Result<FailoverResult> = coroutineScope {
        val attempts = mutableListOf<ResolveAttempt>()
        val ranked = sourceResolutionEngine.rankSources(channel)
            .filter { it !in skipSources }

        for (type in ranked) {
            val info = channel.sources[type] ?: continue
            if (info.streamUrl == null && type in STREAM_NEEDING_RESOLVE) continue

            for (retry in 0 until MAX_RETRIES_PER_SOURCE) {
                val startMs = System.currentTimeMillis()
                val result = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                    streamResolver.resolve(info)
                }
                val latency = System.currentTimeMillis() - startMs

                val resolved = result ?: Result.failure(Exception("Timeout after ${PER_SOURCE_TIMEOUT_MS}ms"))

                attempts.add(ResolveAttempt(type, info, resolved, latency))

                if (resolved.isSuccess) {
                    sourceRegistry.recordSuccess(type.name)
                    sourceHealthPreferences.recordSuccess(channel.id, type)
                    val playback = ResolvedPlayback(
                        streamInfo = resolved.getOrThrow(),
                        sourceType = type,
                        sourceInfo = info,
                        instanceId = "${channel.id}:${type.name}",
                        isFallback = attempts.size > 1,
                    )
                    return@coroutineScope Result.success(
                        FailoverResult(playback, attempts.map { it.toPlayback(channel) }, false)
                    )
                }

                Log.w("PlaybackResolver", "Source $type failed for ${channel.id}: ${resolved.exceptionOrNull()?.message}")
                sourceRegistry.recordFailure(type.name, resolved.exceptionOrNull()?.message)

                if (retry < MAX_RETRIES_PER_SOURCE - 1) {
                    Log.d("PlaybackResolver", "Retrying $type for ${channel.id} (attempt ${retry + 2})")
                }
            }
        }

        val allExhausted = true
        val lastAttempt = attempts.lastOrNull()
        val failover = FailoverResult(
            playback = lastAttempt?.let {
                ResolvedPlayback(
                    streamInfo = StreamInfo(url = "", requiresBrowser = false),
                    sourceType = it.sourceType,
                    sourceInfo = it.sourceInfo,
                    instanceId = "${channel.id}:${it.sourceType.name}",
                    isFallback = true,
                )
            } ?: ResolvedPlayback(
                streamInfo = StreamInfo(url = "", requiresBrowser = false),
                sourceType = SourceType.GLOBAL_INDEX,
                sourceInfo = channel.sources.values.firstOrNull()
                    ?: SourceInfo(type = SourceType.GLOBAL_INDEX, referenceId = ""),
                instanceId = "${channel.id}:none",
                isFallback = true,
            ),
            attempts = attempts.map { it.toPlayback(channel) },
            allSourcesExhausted = allExhausted,
        )
        Result.failure(PlaybackException(failover))
    }

    class PlaybackException(val failoverResult: FailoverResult) : Exception("All sources exhausted")

    suspend fun resolveSource(
        channel: Channel,
        sourceType: SourceType,
    ): Result<ResolvedPlayback> {
        val info = channel.sources[sourceType] ?: return Result.failure(
            Exception("Source $sourceType not available for ${channel.id}")
        )
        return streamResolver.resolve(info).map { streamInfo ->
            ResolvedPlayback(
                streamInfo = streamInfo,
                sourceType = sourceType,
                sourceInfo = info,
                instanceId = "${channel.id}:${sourceType.name}",
            )
        }
    }

    private fun ResolveAttempt.toPlayback(channel: Channel): ResolvedPlayback {
        val streamInfo = result?.getOrNull() ?: StreamInfo(url = "", requiresBrowser = false)
        return ResolvedPlayback(
            streamInfo = streamInfo,
            sourceType = sourceType,
            sourceInfo = sourceInfo,
            instanceId = "${channel.id}:${sourceType.name}",
            isFallback = true,
        )
    }

    private val STREAM_NEEDING_RESOLVE = setOf(
        SourceType.SPORTS_EVENTS,
        SourceType.WORLD_TV,
    )
}
