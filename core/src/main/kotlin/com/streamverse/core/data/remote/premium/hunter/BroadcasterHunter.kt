package com.streamverse.core.data.remote.premium.hunter

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcasterHunter @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : SourceHunter {
    override val name: String = "broadcasters"

    private val client = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun hunt(config: HunterConfig): List<DiscoveredSource> = withContext(Dispatchers.IO) {
        val results = mutableSetOf<DiscoveredSource>()
        results.addAll(huntRepoPlaylists(config))
        if (results.size < config.maxResults) {
            results.addAll(huntIptvOrgCountries(config))
        }
        if (results.size < config.maxResults) {
            results.addAll(huntOriginCdns(config))
        }
        results.take(config.maxResults).toList()
    }

    private suspend fun huntRepoPlaylists(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        FTA_REPOS.map { (fullName, branch, paths) ->
            async {
                try {
                    val found = mutableListOf<DiscoveredSource>()
                    for (path in paths) {
                        try {
                            val url = "https://raw.githubusercontent.com/$fullName/$branch/$path"
                            if (isValidPlaylist(url)) {
                                val key = fullName.replace('/', '_') + "_" + path
                                    .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                    .lowercase().trim('_')
                                found.add(
                                    DiscoveredSource(
                                        key = key,
                                        url = url,
                                        label = "FTA/$fullName/$path",
                                        hunterName = name,
                                    )
                                )
                            }
                        } catch (_: Exception) { /* skip */ }
                    }
                    found
                } catch (e: Exception) {
                    Log.v(TAG, "FTA repo $fullName failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun huntIptvOrgCountries(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        val treeUrl = "https://api.github.com/repos/iptv-org/iptv/git/trees/master?recursive=1"
        async {
            try {
                val req = Request.Builder().url(treeUrl)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "StreamVerse/1.0")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) return@async emptyList()
                val body = resp.body?.string() ?: return@async emptyList()
                val tree = JSONObject(body).getJSONArray("tree")
                val found = mutableListOf<DiscoveredSource>()
                val seen = mutableSetOf<String>()
                for (i in 0 until tree.length()) {
                    val entry = tree.getJSONObject(i)
                    val path = entry.getString("path")
                    if (path.startsWith("countries/") && (path.endsWith(".m3u") || path.endsWith(".m3u8"))) {
                        val countryCode = path.substringAfter("countries/").substringBefore(".")
                        if (countryCode.length == 2 && countryCode !in seen) {
                            seen.add(countryCode)
                            val jsdelivr = "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/$path"
                            val key = "iptv_org_${countryCode}"
                            found.add(
                                DiscoveredSource(
                                    key = key,
                                    url = jsdelivr,
                                    label = "iptv-org/$countryCode",
                                    hunterName = name,
                                )
                            )
                            if (found.size >= MAX_COUNTRIES) break
                        }
                    }
                }
                found
            } catch (e: Exception) {
                Log.w(TAG, "iptv-org country tree failed: ${e.message}")
                emptyList()
            }
        }.await()
    }

    private suspend fun huntOriginCdns(config: HunterConfig): List<DiscoveredSource> {
        val results = mutableListOf<DiscoveredSource>()
        for ((key, urlTemplate) in ORIGIN_CDNS) {
            try {
                val url = urlTemplate
                if (isValidPlaylist(url)) {
                    results.add(
                        DiscoveredSource(
                            key = key.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase().trim('_'),
                            url = url,
                            label = "Origin/$key",
                            hunterName = name,
                        )
                    )
                }
            } catch (_: Exception) { /* skip */ }
        }
        return results
    }

    private fun isValidPlaylist(url: String): Boolean {
        val req = Request.Builder().url(url)
            .header("User-Agent", "StreamVerse/1.0")
            .header("Range", "bytes=0-2047")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return false
        val data = resp.body?.string() ?: return false
        return data.startsWith("#EXTM3U") || data.startsWith("#EXTINF")
    }

    companion object {
        private const val TAG = "BroadcasterHunter"
        private const val MAX_COUNTRIES = 10

        private val FTA_REPOS = listOf(
            Triple("Free-TV/IPTV", "master", listOf("playlist.m3u8", "streams/africa.m3u", "streams/sports.m3u")),
            Triple("iptv-org/iptv", "master", listOf("categories/sports.m3u", "categories/movies.m3u", "categories/news.m3u")),
        )

        private val ORIGIN_CDNS = listOf(
            "arte" to "https://artelive-lh.akamaized.net/i/artelive_1@319444/index_1_av-p.m3u8",
            "france24_en" to "https://live.france24.com/live/france24_en/france24_en.m3u8",
            "france24_fr" to "https://live.france24.com/live/france24_fr/france24_fr.m3u8",
            "nhk_world" to "https://masterpl.hls.nhkworld.jp/hls/nhkworld.m3u8",
            "cbc_news" to "https://cbcnewshd-f.akamaihd.net/i/cbcnewshd_1@77202/master.m3u8",
            "rt_news" to "https://rt-news.rttv.com/live/rtnews/playlist.m3u8",
            "alarabiya" to "https://live.alarabiya.net/alarabiapublish/alarabiya.smil/playlist.m3u8",
        )
    }
}
