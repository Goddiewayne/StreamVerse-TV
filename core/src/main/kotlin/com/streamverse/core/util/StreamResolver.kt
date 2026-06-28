package com.streamverse.core.util

import com.streamverse.core.data.remote.dlhd.DlhdClient
import com.streamverse.core.data.remote.stmify.StmifyClient
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.domain.model.Quality
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamResolver @Inject constructor(
    private val dlhdClient: DlhdClient,
    private val stmifyClient: StmifyClient,
    private val dispatchers: StreamVerseDispatchers,
    private val youTubeLiveResolver: YouTubeLiveResolver,
) {
    private companion object {
        // Dev-only restreaming proxy for direct HLS/TS feeds whose CDN fingerprint-blocks the
        // Android TLS stack (403 even with correct headers). The proxy re-fetches upstream with
        // a non-blocked client + the playlist's Referer and serves cleartext to the player.
        // 10.0.2.2 is the emulator's alias for the host loopback. Empty string = disabled.
        // Disabled: it requires a host-side proxy process and breaks official direct HLS feeds
        // (and all WebView channels) when not running. Only enable for local fingerprint testing.
        const val STREAM_PROXY = ""
    }

    /** Wrap a direct stream URL through [STREAM_PROXY] when enabled, folding the Referer in. */
    private fun maybeProxy(url: String, headers: Map<String, String>): Pair<String, Map<String, String>> {
        if (STREAM_PROXY.isEmpty() || url.startsWith(STREAM_PROXY)) return url to headers
        fun enc(s: String) = android.util.Base64.encodeToString(
            s.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        val referer = headers["Referer"] ?: ""
        // The proxy URL must carry an extension ExoPlayer's inferContentType() recognises,
        // else it treats the stream as progressive and can't demux the playlist.
        val path = url.substringBefore('?').substringAfterLast('/').lowercase()
        val suffix = when {
            path.endsWith(".ts") -> ".ts"
            Regex("\\.(m4s|mp4|m4v)$").containsMatchIn(path) -> ".mp4"
            else -> ".m3u8" // entry points are HLS playlists
        }
        // Proxy injects the upstream headers itself, so the player needs none.
        return "$STREAM_PROXY/s$suffix?u=${enc(url)}&r=${enc(referer)}" to emptyMap()
    }

    suspend fun resolve(sourceInfo: SourceInfo): Result<StreamInfo> {
        return when (sourceInfo.type) {
            SourceType.IPTV, SourceType.FREE_TV, SourceType.RADIO, SourceType.FAST_TV, SourceType.INDEPENDENT, SourceType.PREMIUM -> {
                val url = sourceInfo.streamUrl
                if (url != null) {
                    val isWebPage = isYouTube(url) || url.contains("channelstv.com/live")
                    if (isWebPage) {
                        // YouTube live: resolve the real HLS manifest via the Innertube API (no
                        // WebView) so it plays in ExoPlayer with our own chrome — plain TV, not
                        // YouTube. If that fails, go straight to the WebView fallback (the hidden
                        // HTML extractor is unreliable for YouTube and only adds latency). Never
                        // wrap a page through the HLS restreaming proxy.
                        val hls = if (isYouTube(url)) youTubeLiveResolver.resolveHls(url) else null
                        if (hls != null) {
                            Result.success(StreamInfo(url = hls, headers = youTubeLiveResolver.playbackHeaders))
                        } else if (isYouTube(url)) {
                            // No direct manifest — fall back to the clean embed player (just the
                            // video) when we can resolve the live video id, else the channel page.
                            val embed = youTubeLiveResolver.resolveEmbedUrl(url) ?: url
                            Result.success(StreamInfo(url = embed, forceWebView = true))
                        } else {
                            Result.success(StreamInfo(url = url, requiresBrowser = true))
                        }
                    } else {
                        val (finalUrl, finalHeaders) = maybeProxy(url, sourceInfo.headers)
                        Result.success(
                            StreamInfo(
                                url = finalUrl,
                                requiresBrowser = false,
                                forceWebView = false,
                                headers = finalHeaders,
                                drmKeyId = sourceInfo.drmKeyId,
                                drmKey = sourceInfo.drmKey,
                            )
                        )
                    }
                } else Result.failure(Exception("No stream URL for ${sourceInfo.type} source"))
            }
            SourceType.DLHD -> dlhdClient.resolveStreamUrl(sourceInfo.referenceId).map {
                StreamInfo(it, requiresBrowser = it.contains("dlhd.pk/"))
            }
            SourceType.STMIFY_FREE -> {
                val direct = stmifyClient.resolveDirectStream(sourceInfo.referenceId)
                if (direct.isSuccess) direct
                else stmifyClient.resolveStreamUrl(sourceInfo.referenceId).map { StreamInfo(it, requiresBrowser = true) }
            }
            SourceType.STMIFY_PREMIUM ->
                stmifyClient.resolveStreamUrl(sourceInfo.referenceId).map { StreamInfo(it, requiresBrowser = true) }
        }
    }

    suspend fun resolveAll(sourceInfo: SourceInfo): List<StreamInfo> {
        val urls = mutableListOf<StreamInfo>()
        when (sourceInfo.type) {
            SourceType.STMIFY_FREE -> {
                val direct = stmifyClient.resolveDirectStream(sourceInfo.referenceId).getOrNull()
                if (direct != null) urls.add(direct)
            }
            else -> {
                val resolved = resolve(sourceInfo).getOrNull()
                if (resolved != null) urls.add(resolved)
                // YouTube live: keep a WebView fallback behind the direct manifest so the channel
                // still plays if the API result is null or the manifest expires mid-session.
                val pageUrl = sourceInfo.streamUrl
                if (pageUrl != null && isYouTube(pageUrl) && resolved?.forceWebView != true) {
                    urls.add(StreamInfo(url = pageUrl, forceWebView = true))
                }
            }
        }
        return urls.distinct()
    }

    private fun isYouTube(url: String): Boolean =
        url.contains("youtube.com") || url.contains("youtu.be")

    suspend fun resolveBestUrl(sources: Map<SourceType, SourceInfo>, preferredQuality: Quality? = null): Result<String> {
        val order = listOf(
            SourceType.DLHD,
            SourceType.STMIFY_FREE,
            SourceType.STMIFY_PREMIUM,
            SourceType.IPTV,
            SourceType.FREE_TV,
            SourceType.FAST_TV,
            SourceType.PREMIUM,
            SourceType.INDEPENDENT,
            SourceType.RADIO,
        )
        for (type in order) {
            val source = sources[type] ?: continue
            val result = resolve(source)
            if (result.isSuccess) return result.map { it.url }
        }
        val fallback = sources.values.firstOrNull()
        return if (fallback != null) resolve(fallback).map { it.url }
        else Result.failure(Exception("No stream sources available"))
    }
}
