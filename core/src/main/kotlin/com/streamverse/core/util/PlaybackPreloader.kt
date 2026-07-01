package com.streamverse.core.util

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-builds and prepares ExoPlayer instances for anticipated channels so that switching
 * to them is instant — the player is already at [Player.STATE_READY] with decoders
 * initialised and the first segment buffered.  Call [preload] after the current channel
 * starts playing, then [take] when the user selects a preloaded channel.
 *
 * Memory cap: [MAX_PRELOADS] players (~60-120 MB total for 3).  LRU eviction.
 * 3 slots cover: {current-1, current+1, current+2} — the typical surf range.
 */
@Singleton
class PlaybackPreloader @Inject constructor(
    private val streamPreResolver: StreamPreResolver,
    private val sourcePreferences: com.streamverse.core.data.SourcePreferences,
) {
    private val pool = object : LinkedHashMap<String, PreloadedPlayer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreloadedPlayer>) =
            size > MAX_PRELOADS
    }

    data class PreloadedPlayer(
        val channelId: String,
        val type: SourceType,
        val player: ExoPlayer,
    )

    /**
     * Build and prepare an ExoPlayer for [channel]'s [type] source, downloading the
     * manifest and initialising decoders in the background.  No-ops if a player for this
     * channel+type already exists in the pool.  Evicts the LRU entry when the pool is full.
     */
    suspend fun preload(
        appContext: Context,
        channel: Channel,
        type: SourceType,
    ) {
        // Skip entirely when data-saver is active — no pre-buffering on metered connections.
        if (sourcePreferences.isDataSaverEnabled()) return
        val key = cacheKey(channel.id, type)
        if (pool.containsKey(key)) return

        val streamInfo = streamPreResolver.getCached(channel.id, type)
            ?.firstOrNull { !it.requiresBrowser && !it.forceWebView }
            ?: return

        withContext(Dispatchers.Main) {
            val dsf = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
            if (streamInfo.headers.isNotEmpty()) {
                dsf.setDefaultRequestProperties(streamInfo.headers)
            }

            val ds = sourcePreferences.isDataSaverEnabled()
            val exo = ExoPlayer.Builder(appContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
                .setLoadControl(StreamLoadControl.build(ds))
                .setTrackSelector(StreamTrackSelector.build(appContext, ds))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    /* handleAudioFocus */ false,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(streamInfo.url)
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(channel.displayName)
                                    .build()
                            ).build()
                    )
                    playWhenReady = false
                    prepare()
                }

            pool[key] = PreloadedPlayer(channel.id, type, exo)
        }
    }

    /**
     * Take (remove) a preloaded player from the pool.  The caller takes ownership of the
     * ExoPlayer lifecycle.  Returns `null` if no preloaded player exists for this key.
     */
    fun take(channelId: String, type: SourceType): PreloadedPlayer? =
        pool.remove(cacheKey(channelId, type))

    /** Release all preloaded players.  Call on ViewModel onCleared or Activity onStop. */
    fun releaseAll() {
        pool.values.forEach { it.player.release() }
        pool.clear()
    }

    private fun cacheKey(channelId: String, type: SourceType) = "$channelId:$type"

    companion object {
        private const val MAX_PRELOADS = 3
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
