package com.streamverse.core.util

import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
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
 * • 30-entry LRU (covers ~3 visible rows of TV cards)
 * • 3-minute TTL — live stream endpoints sometimes rotate tokens
 * • Only the primary TV-playable source per channel is pre-resolved; secondary sources are
 *   resolved on demand (they are rarely needed)
 *
 * Thread safety: all mutations are protected via [Collections.synchronizedMap]; the active-job
 * map uses [ConcurrentHashMap].
 */
@Singleton
class StreamPreResolver @Inject constructor(
    private val streamResolver: StreamResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private data class Entry(val streams: List<StreamInfo>, val timestamp: Long)

    private val cache: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Entry>) = size > MAX_ENTRIES
        }
    )

    /**
     * Pre-resolve [channel]'s primary playable source in the background.
     * Safe to call on the main thread; no-ops if a live cache hit exists or a job is already
     * running for this channel.  Callers should debounce by ~300 ms on fast D-Pad scrolling.
     */
    fun preResolve(channel: Channel) {
        val (type, sourceInfo) = PRIORITY
            .mapNotNull { t -> channel.sources[t]?.let { t to it } }
            .firstOrNull() ?: return

        val key = cacheKey(channel.id, type)
        if (cache[key]?.isFresh() == true) return
        if (activeJobs[key]?.isActive == true) return

        activeJobs[key] = scope.launch {
            runCatching { streamResolver.resolveAll(sourceInfo) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.also { cache[key] = Entry(it, System.currentTimeMillis()) }
            activeJobs.remove(key)
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

    private fun Entry.isFresh() = System.currentTimeMillis() - timestamp < TTL_MS
    private fun cacheKey(channelId: String, type: SourceType) = "$channelId:$type"

    companion object {
        private const val TTL_MS = 3 * 60 * 1_000L
        private const val MAX_ENTRIES = 30

        // Resolution priority order — matches TVPlaybackActivity source chips, best TV-playable first
        private val PRIORITY = listOf(
            SourceType.INDEPENDENT,
            SourceType.DLHD,
            SourceType.STMIFY_FREE,
            SourceType.IPTV,
            SourceType.FREE_TV,
            SourceType.FAST_TV,
            SourceType.RADIO,
        )
    }
}
