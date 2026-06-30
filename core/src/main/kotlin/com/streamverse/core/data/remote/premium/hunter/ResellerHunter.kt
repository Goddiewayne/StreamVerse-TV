package com.streamverse.core.data.remote.premium.hunter

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResellerHunter @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : SourceHunter {
    override val name: String = "resellers"

    private val client = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun hunt(config: HunterConfig): List<DiscoveredSource> = withContext(Dispatchers.IO) {
        val results = mutableSetOf<DiscoveredSource>()
        results.addAll(probeXtreamPanels(config))
        if (results.size < config.maxResults) {
            results.addAll(probeCustomPanels(config))
        }
        if (results.size < config.maxResults) {
            results.addAll(probeM3uDirect(config))
        }
        results.take(config.maxResults).toList()
    }

    private suspend fun probeXtreamPanels(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        XTREAM_PANELS.map { (domain, port) ->
            async {
                try {
                    val panelUrl = if (port > 0) "http://$domain:$port/" else "http://$domain/"
                    val healthReq = Request.Builder().url(panelUrl)
                        .header("User-Agent", "StreamVerse/1.0")
                        .head()
                        .build()
                    val healthResp = client.newCall(healthReq).execute()
                    healthResp.close()

                    val found = mutableListOf<DiscoveredSource>()
                    for ((user, pass) in TEST_CREDENTIALS) {
                        try {
                            val m3uUrl = "${panelUrl}get.php?username=$user&password=$pass&type=m3u_plus&output=ts"
                            val m3uReq = Request.Builder().url(m3uUrl)
                                .header("User-Agent", "StreamVerse/1.0")
                                .header("Range", "bytes=0-2047")
                                .build()
                            val m3uResp = client.newCall(m3uReq).execute()
                            if (m3uResp.isSuccessful) {
                                val data = m3uResp.body?.string() ?: ""
                                if (data.startsWith("#EXTM3U")) {
                                    val key = "${domain}_${user}_${port}"
                                        .replace(Regex("[^a-zA-Z0-9_-]"), "_").trim('_')
                                    found.add(
                                        DiscoveredSource(
                                            key = key,
                                            url = panelUrl.trimEnd('/'),
                                            label = "Xtream/$domain ($user)",
                                            hunterName = name,
                                        )
                                    )
                                    m3uResp.close()
                                    break
                                }
                            }
                            m3uResp.close()
                        } catch (_: Exception) { /* try next credential */ }
                    }
                    found
                } catch (e: Exception) {
                    Log.v(TAG, "Xtream panel $domain unreachable: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun probeCustomPanels(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        CUSTOM_PANELS.map { domain ->
            async {
                try {
                    val found = mutableListOf<DiscoveredSource>()
                    for (path in CUSTOM_API_PATHS) {
                        try {
                            val url = "http://$domain$path"
                            val req = Request.Builder().url(url)
                                .header("User-Agent", "StreamVerse/1.0")
                                .header("Range", "bytes=0-2047")
                                .build()
                            val resp = client.newCall(req).execute()
                            if (resp.isSuccessful) {
                                val data = resp.body?.string() ?: ""
                                if (data.startsWith("#EXTM3U")) {
                                    val key = domain.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                        .trim('_') + "_custom"
                                    found.add(
                                        DiscoveredSource(
                                            key = key,
                                            url = url,
                                            label = "Custom/$domain$path",
                                            hunterName = name,
                                        )
                                    )
                                    resp.close()
                                    break
                                }
                            }
                            resp.close()
                        } catch (_: Exception) { /* skip */ }
                    }
                    found
                } catch (e: Exception) {
                    Log.v(TAG, "Custom panel $domain failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun probeM3uDirect(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        M3U_DIRECT_SITES.map { domain ->
            async {
                try {
                    val found = mutableListOf<DiscoveredSource>()
                    for (path in M3U_DIRECT_PATHS) {
                        try {
                            val url = "http://$domain$path"
                            val req = Request.Builder().url(url)
                                .header("User-Agent", "StreamVerse/1.0")
                                .header("Range", "bytes=0-2047")
                                .build()
                            val resp = client.newCall(req).execute()
                            if (resp.isSuccessful) {
                                val data = resp.body?.string() ?: ""
                                if (data.startsWith("#EXTM3U")) {
                                    val key = domain.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                        .trim('_') + "_direct"
                                    found.add(
                                        DiscoveredSource(
                                            key = key,
                                            url = url,
                                            label = "M3UDirect/$domain$path",
                                            hunterName = name,
                                        )
                                    )
                                    resp.close()
                                    break
                                }
                            }
                            resp.close()
                        } catch (_: Exception) { /* skip */ }
                    }
                    found
                } catch (e: Exception) {
                    Log.v(TAG, "M3U direct $domain failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    companion object {
        private const val TAG = "ResellerHunter"

        private val XTREAM_PANELS = listOf(
            "iptvglobal.pro" to 0,
            "premium-iptv.pro" to 0,
            "goldeniptv.shop" to 0,
            "dstvstreamz.online" to 0,
            "supersportiptv.xyz" to 0,
            "m3u.tv" to 0,
            "freem3u.xyz" to 0,
            "iptvpremium.store" to 0,
            "xtream-iptv.com" to 0,
            "bestiptv.shop" to 0,
            "iptvsub.net" to 0,
            "iptv2026.com" to 0,
            "premiumiptv.world" to 0,
            "goldiptv.net" to 0,
            "ultraiptv.live" to 0,
            "flixiptv.com" to 0,
            "streamhub.xyz" to 25461,
            "iptvzone.net" to 25461,
            "xtreamhub.net" to 25461,
            "beststreams.live" to 25461,
            "iptvpanel.xyz" to 25461,
            "premiumstreams.net" to 8880,
            "goldstreams.tv" to 8880,
            "m3ulink.com" to 8080,
            "iptvsource.net" to 8080,
        )

        private val TEST_CREDENTIALS = listOf(
            "demo" to "demo",
            "test" to "test",
            "free" to "free",
            "trial" to "trial",
            "demo" to "1234",
            "test" to "1234",
            "free" to "1234",
            "demo" to "12345",
            "test" to "12345",
            "demo" to "admin",
            "test" to "admin",
            "iptv" to "iptv",
            "premium" to "premium",
            "vip" to "vip",
            "0" to "0",
            "1" to "1",
        )

        private val CUSTOM_PANELS = listOf(
            "iptvpanel.pro",
            "custom-iptv.xyz",
            "m3uhub.net",
            "streamlinks.live",
        )

        private val CUSTOM_API_PATHS = listOf(
            "/playlist.m3u", "/playlist.m3u8",
            "/get.m3u", "/channels.m3u",
        )

        private val M3U_DIRECT_SITES = listOf(
            "m3ufree.tv",
            "iptvlistings.com",
            "liveiptv.xyz",
            "playlisthub.com",
            "iptvstreams.net",
        )

        private val M3U_DIRECT_PATHS = listOf(
            "", "/playlist.m3u", "/playlist.m3u8",
        )
    }
}
