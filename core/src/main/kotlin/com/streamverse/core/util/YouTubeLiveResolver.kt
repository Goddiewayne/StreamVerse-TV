package com.streamverse.core.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a YouTube **live** page (e.g. `youtube.com/channel/<id>/live`) to a direct HLS master
 * playlist URL, with no WebView — so the channel plays in ExoPlayer with the app's own chrome
 * instead of showing YouTube's page.
 *
 * Primary method uses **NewPipeExtractor** (v0.26.3) which handles:
 *  • YouTube consent pages and cookie acceptance
 *  • PoToken generation and validation for integrity checks
 *  • Signature deciphering (cipher / n-parameter deobfuscation)
 *  • Automatic client rotation (WEB, ANDROID, iOS) for resilience
 *  • Resilient stream URL extraction from player responses
 *
 * Falls back to the raw Innertube API if NewPipeExtractor cannot handle a particular URL pattern
 * (e.g. channel/live URLs that bypass the stream link handler) or if the extraction fails.
 */
@Singleton
class YouTubeLiveResolver @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloader = NewPipeOkHttpDownloader(client)
    private var newPipeInitialized = false

    private fun ensureNewPipe() {
        if (!newPipeInitialized) {
            NewPipe.init(downloader)
            newPipeInitialized = true
        }
    }

    /**
     * A direct HLS manifest URL for the channel's current live, or null.
     * Uses NewPipeExtractor as the primary engine with a legacy Innertube fallback.
     */
    suspend fun resolveHls(pageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            ensureNewPipe()
            val normalized = pageUrl.replace("://m.youtube.com", "://www.youtube.com")

            // Path 1: Direct video URL → NewPipeExtractor StreamExtractor
            val streamFactory = ServiceList.YouTube.streamLHFactory
            if (streamFactory.acceptUrl(normalized)) {
                val hls = extractHlsFromStream(normalized)
                if (hls != null) {
                    android.util.Log.d(TAG, "resolveHls: NewPipe direct success url=${normalized.take(80)}")
                    return@withContext hls
                }
            }

            // Path 2: Channel/live URL → follow redirect via NewPipe's Downloader (handles consent),
            //         then extract HLS from the resolved video URL.
            val resolved = followRedirect(normalized)
            if (resolved != null && resolved != normalized && streamFactory.acceptUrl(resolved)) {
                val hls = extractHlsFromStream(resolved)
                if (hls != null) {
                    android.util.Log.d(TAG, "resolveHls: NewPipe redirected success url=${resolved.take(80)}")
                    return@withContext hls
                }
            }

            android.util.Log.d(TAG, "resolveHls: NewPipe path failed, falling back to Innertube")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "resolveHls: NewPipe error (${e.message}), falling back")
        }

        legacyResolveHls(pageUrl)
    }

    /**
     * A clean embed-player URL for the channel's current live, or null.
     */
    suspend fun resolveEmbedUrl(pageUrl: String): String? = withContext(Dispatchers.IO) {
        val videoId = runCatching {
            ensureNewPipe()
            val normalized = pageUrl.replace("://m.youtube.com", "://www.youtube.com")
            val streamFactory = ServiceList.YouTube.streamLHFactory
            if (streamFactory.acceptUrl(normalized)) {
                streamFactory.fromUrl(normalized).id
            } else {
                val resolved = followRedirect(normalized)
                if (resolved != null && streamFactory.acceptUrl(resolved)) {
                    streamFactory.fromUrl(resolved).id
                } else null
            }
        }.getOrNull() ?: return@withContext null

        "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&mute=0&modestbranding=1&rel=0&fs=1"
    }

    /** The User-Agent the resolved manifest should be played with. */
    val playbackHeaders: Map<String, String> = mapOf("User-Agent" to ANDROID_UA)

    // ── NewPipeExtractor stream extraction ─────────────────────────────────────

    private fun extractHlsFromStream(url: String): String? {
        val linkHandler = ServiceList.YouTube.streamLHFactory.fromUrl(url)
        val extractor = ServiceList.YouTube.getStreamExtractor(linkHandler)
        extractor.fetchPage()
        val streamType = extractor.streamType
        return when (streamType) {
            StreamType.LIVE_STREAM, StreamType.POST_LIVE_STREAM -> extractor.hlsUrl
            else -> null
        }
    }

    private fun followRedirect(url: String): String? {
        return try {
            val resp = downloader.get(url)
            resp.latestUrl()
        } catch (e: Exception) {
            null
        }
    }

    // ── Legacy Innertube fallback (HTML scrape + player API) ───────────────────

    private fun legacyResolveHls(pageUrl: String): String? {
        return runCatching {
            val videoId = fetchLiveVideoId(pageUrl)
            android.util.Log.d(TAG, "legacyResolveHls: page=$pageUrl videoId=$videoId")
            if (videoId == null) return@runCatching null
            val hls = fetchHlsManifest(videoId)
            android.util.Log.d(TAG, "legacyResolveHls: hls=${hls?.take(80)}")
            hls
        }.onFailure { android.util.Log.w(TAG, "legacyResolveHls failed: ${it.message}") }.getOrNull()
    }

    private fun fetchLiveVideoId(pageUrl: String): String? {
        val desktopUrl = pageUrl.replace("://m.youtube.com", "://www.youtube.com")
        val req = Request.Builder()
            .url(desktopUrl)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "CONSENT=YES+1; SOCS=CAISNewABA")
            .get()
            .build()
        val (code, html) = client.newCall(req).execute().use { resp ->
            resp.code to resp.body?.string()
        }
        android.util.Log.d(
            TAG,
            "fetchLiveVideoId: code=$code len=${html?.length} hasCanonical=${html?.contains("rel=\"canonical\"")} " +
                "hasVideoId=${html?.contains("videoId")} consent=${html?.contains("consent.youtube")}",
        )
        if (html == null) return null
        Regex("""rel="canonical"\s+href="https://www\.youtube\.com/watch\?v=([\w-]{11})"""")
            .find(html)?.groupValues?.get(1)?.let { return it }
        return Regex("""[\\"']videoId[\\"']\s*:\s*[\\"']([\w-]{11})[\\"']""")
            .find(html)?.groupValues?.get(1)
    }

    private fun fetchHlsManifest(videoId: String): String? {
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", ANDROID_CLIENT_VERSION)
                    put("androidSdkVersion", 34)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }.toString()
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$INNERTUBE_KEY")
            .header("User-Agent", ANDROID_UA)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val json = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null
        val streaming = JSONObject(json).optJSONObject("streamingData") ?: return null
        return streaming.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "YouTubeLiveResolver"
        const val INNERTUBE_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
        const val ANDROID_CLIENT_VERSION = "19.09.37"
        const val ANDROID_UA =
            "com.google.android.youtube/19.09.37 (Linux; U; Android 12; GB) gzip"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
