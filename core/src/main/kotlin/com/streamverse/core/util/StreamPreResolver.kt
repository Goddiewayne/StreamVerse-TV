package com.streamverse.core.util

import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background stream URL pre-resolver — the primary driver of sub-second channel start latency.
 *
 * When a channel card receives D-Pad focus (or is tapped on mobile), the caller invokes
 * [preResolve].  By the time the user presses Select / OK (~200–800 ms later), the HLS URL
 * is already in [getCached] — the playback path skips the network round-trip entirely and
 * goes straight to ExoPlayer preparation.
 *
 * Cache policy
 * ─────────────
 * • 150-entry LRU (covers full visible grid + nearby rows on TV + surf neighbours)
 * • 10-minute TTL — live stream tokens often valid longer; stale check happens on use
 * • Primary + up to 2 backup sources pre-resolved per channel for instant failover
 *
 * Thread safety: all mutations are protected via [Collections.synchronizedMap]; the active-job
 * map uses [ConcurrentHashMap].
 */
@Singleton
class StreamPreResolver @Inject constructor(
    private val streamResolver: StreamResolver,
    private val providerRegistry: ProviderRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private data class Entry(val streams: List<StreamInfo>, val timestamp: Long)

    private val cache: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Entry>) = size > MAX_ENTRIES
        }
    )

    /**
     * Pre-resolve [channel]'s primary playable source (+ up to 1 backup) in the background.
     * Safe to call on the main thread; no-ops if a live cache hit exists or a job is already
     * running for this channel.  Callers should debounce by ~150 ms on fast D-Pad scrolling.
     */
    fun preResolve(channel: Channel) {
        val sources = providerRegistry.prioritySorted()
            .mapNotNull { t -> channel.sources[t]?.let { t to it } }
            .take(2) // primary + 1 backup
        if (sources.isEmpty()) return

        scope.launch {
            sources.map { (type, sourceInfo) ->
                async {
                    val key = cacheKey(channel.id, type)
                    if (cache[key]?.isFresh() == true) return@async
                    if (activeJobs[key]?.isActive == true) return@async
                    activeJobs[key] = kotlinx.coroutines.Job()
                    runCatching { streamResolver.resolveAll(sourceInfo) }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?.also { cache[key] = Entry(it, System.currentTimeMillis()) }
                    activeJobs.remove(key)
                }
            }
        }
    }

    /**
     * Pre-resolve ALL of [channel]'s playable sources in parallel and cache their stream URLs.
     * Instant failover: when the primary source drops, the next source's URLs are already cached
     * so [resolveAndPlay] skips the network round-trip.
     *
     * Already-cached sources are skipped; already-running jobs are not re-launched.
     * Safe to call from the main thread.
     */
    suspend fun preResolveAll(channel: Channel) {
        val sources = providerRegistry.prioritySorted()
            .mapNotNull { t -> channel.sources[t]?.let { t to it } }
            .take(3) // limit to 3 sources max to avoid excessive parallel requests
        if (sources.isEmpty()) return
        coroutineScope {
            sources.map { (type, sourceInfo) ->
                async {
                    val key = cacheKey(channel.id, type)
                    if (cache[key]?.isFresh() == true) return@async
                    runCatching { streamResolver.resolveAll(sourceInfo) }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?.also { cache[key] = Entry(it, System.currentTimeMillis()) }
                }
            }
        }
    }

    /**
     * Returns a fresh cached resolution for [channelId] + [type], or `null` if the cache
     * entry is missing or stale.  Hot path — no coroutine needed.
     */
    fun getCached(channelId: String, type: SourceType): List<StreamInfo>? {
        val entry = cache[cacheKey(channelId, type)] ?: return null
        if (!entry.isFresh()) { cache.remove(cacheKey(channelId, type)); return null }
        return entry.streams
    }

    /**
     * Check if a channel has any cached stream ready (any source).
     * Used to quickly determine if instant playback is possible.
     */
    fun hasAnyCached(channelId: String): Boolean {
        val keys = providerRegistry.prioritySorted()
            .map { cacheKey(channelId, it) }
            .filter { cache.containsKey(it) }
        return keys.any { cache[it]?.isFresh() == true }
    }

    private fun Entry.isFresh() = System.currentTimeMillis() - timestamp < TTL_MS
    private fun cacheKey(channelId: String, type: SourceType) = "$channelId:$type"

    companion object {
        private const val TTL_MS = 10 * 60 * 1_000L
        private const val MAX_ENTRIES = 150
    }
}
