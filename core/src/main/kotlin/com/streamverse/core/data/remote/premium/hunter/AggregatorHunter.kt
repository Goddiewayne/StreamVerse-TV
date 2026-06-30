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
class AggregatorHunter @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : SourceHunter {
    override val name: String = "aggregators"

    private val client = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun hunt(config: HunterConfig): List<DiscoveredSource> = withContext(Dispatchers.IO) {
        val results = mutableSetOf<DiscoveredSource>()
        results.addAll(probeKnownAggregators(config))
        if (results.size < config.maxResults) {
            results.addAll(probeStreamProxies(config))
        }
        if (results.size < config.maxResults) {
            results.addAll(getCommunitySources())
        }
        results.take(config.maxResults).toList()
    }

    private suspend fun probeKnownAggregators(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        KNOWN_AGGREGATORS.map { (key, url) ->
            async {
                try {
                    if (isValidM3u(url)) {
                        listOf(
                            DiscoveredSource(
                                key = key,
                                url = url,
                                label = "Aggregator/$key",
                                hunterName = name,
                            )
                        )
                    } else emptyList()
                } catch (e: Exception) {
                    Log.v(TAG, "Aggregator $key unreachable: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun probeStreamProxies(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        STREAM_PROXY_HOSTS.flatMap { host ->
            STREAM_PROXY_PATHS.mapNotNull { path ->
                async {
                    try {
                        val url = "https://$host$path"
                        if (isValidM3u(url)) {
                            val key = host.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                .trim('_') + "_" + path
                                    .removePrefix("/")
                                    .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                    .trim('_')
                            if (key.isNotBlank()) {
                                DiscoveredSource(
                                    key = key,
                                    url = url,
                                    label = "Proxy/$host$path",
                                    hunterName = name,
                                )
                            } else null
                        } else null
                    } catch (_: Exception) { null }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun getCommunitySources(): List<DiscoveredSource> {
        return COMMUNITY_SOURCES.map { (key, url) ->
            DiscoveredSource(
                key = key.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase().trim('_'),
                url = url,
                label = "Community/$key",
                hunterName = name,
            )
        }
    }

    private fun isValidM3u(url: String): Boolean {
        val req = Request.Builder().url(url)
            .header("User-Agent", "StreamVerse/1.0")
            .header("Range", "bytes=0-2047")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return false
        val data = resp.body?.string() ?: return false
        return data.startsWith("#EXTM3U")
    }

    companion object {
        private const val TAG = "AggregatorHunter"

        private val KNOWN_AGGREGATORS = listOf(
            "freeiptv" to "https://freeiptv.co/playlist",
            "iptvlist" to "https://iptvlist.net/playlist.m3u",
            "iptvcat" to "https://iptvcat.com/playlist.m3u",
            "iptvgratis" to "https://iptvgratis.net/playlist.m3u",
            "tivim8" to "https://tivim8.com/playlist.m3u",
            "iptvfree" to "https://iptvfree.tv/playlist.m3u",
            "dstvplaylist" to "https://dstvplaylist.com/playlist.m3u",
            "supersportm3u" to "https://supersportm3u.xyz/playlist.m3u",
            "saiptv" to "https://sa-iptv.co.za/playlist.m3u",
            "m3usa" to "https://m3usa.co.za/playlist.m3u",
        )

        private val STREAM_PROXY_HOSTS = listOf(
            "pontos.phantemlis.top",
            "kolis.phantemlis.top",
            "fomis.phantemlis.top",
            "hamis.romponalis.st",
        )

        private val STREAM_PROXY_PATHS = listOf(
            "", "/index.m3u8", "/playlist.m3u8", "/premium.m3u8",
        )

        private val COMMUNITY_SOURCES = listOf(
            "free_tv_iptv" to "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
            "iptv_org_africa" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/countries/za.m3u",
            "iptv_org_sports" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/categories/sports.m3u",
            "iptv_org_movies" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/categories/movies.m3u",
            "iptv_org_news" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/categories/news.m3u",
            "iptv_org_documentary" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/categories/documentary.m3u",
            "iptv_org_series" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/categories/series.m3u",
            "iptv_org_us" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/countries/us.m3u",
            "iptv_org_gb" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/countries/gb.m3u",
            "iptv_org_de" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/countries/de.m3u",
            "iptv_org_fr" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/countries/fr.m3u",
            "iptv_org_cn" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/countries/cn.m3u",
            "iptv_org_jp" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/countries/jp.m3u",
            "iptv_org_in" to "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/countries/in.m3u",
        )
    }
}
