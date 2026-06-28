package com.streamverse.core.data.remote.iptv

import android.util.Log
import com.streamverse.core.data.remote.m3u.M3uParser
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class IptvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val language: String?,
    val quality: String?,
    val forceWebView: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val drmKeyId: String? = null,
    val drmKey: String? = null,
)

@Singleton
class IptvClient @Inject constructor(
    private val dispatchers: StreamVerseDispatchers,
    okHttpClient: OkHttpClient,
) {
    // Primary: full global index (~30 000 channels)
    private val mainUrl = "https://iptv-org.github.io/iptv/index.m3u"

    // Supplemental: small focused playlists. These are subsets of the main index but download
    // orders-of-magnitude faster (KB vs MB), ensuring African channels appear quickly even when
    // the full index is still downloading.
    private val supplementalUrls = listOf(
        "https://iptv-org.github.io/iptv/countries/ng.m3u",   // Nigeria
        "https://iptv-org.github.io/iptv/countries/gh.m3u",   // Ghana
        "https://iptv-org.github.io/iptv/countries/za.m3u",   // South Africa
        "https://iptv-org.github.io/iptv/countries/ke.m3u",   // Kenya
        "https://iptv-org.github.io/iptv/countries/eg.m3u",   // Egypt
        "https://iptv-org.github.io/iptv/countries/tz.m3u",   // Tanzania
        "https://iptv-org.github.io/iptv/countries/et.m3u",   // Ethiopia
        "https://iptv-org.github.io/iptv/countries/ug.m3u",   // Uganda
        "https://iptv-org.github.io/iptv/regions/afr.m3u",    // Africa (all 54 countries)
    )

    // Reuse the shared pool + HTTP cache. A total callTimeout bounds the whole request so a slow
    // CDN can never hang the load indefinitely (readTimeout alone only bounds each individual read).
    private val client = okHttpClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private var cachedChannels: List<IptvChannel>? = null
    private var cacheTime: Long = 0

    suspend fun fetchChannels(forceRefresh: Boolean = false): Result<List<IptvChannel>> =
        withContext(dispatchers.io) {
            runCatching {
                val now = System.currentTimeMillis()
                if (!forceRefresh && cachedChannels != null && (now - cacheTime) < 86_400_000L) {
                    return@runCatching cachedChannels!!
                }
                // Fetch ALL sources in PARALLEL (supplementals + main index simultaneously).
                // This eliminates the sequential bottleneck where the 5-10MB main index blocked
                // the smaller supplemental playlists.
                val allById = mutableMapOf<String, IptvChannel>()
                coroutineScope {
                    val urls = supplementalUrls + mainUrl
                    val deferred = urls.map { url ->
                        async {
                            try {
                                val channels = parseM3uStream(url)
                                channels.forEach { ch -> allById.putIfAbsent(ch.id, ch) }
                                Log.d("IptvClient", "fetched: $url (total=${allById.size})")
                            } catch (e: Exception) {
                                Log.w("IptvClient", "failed: $url — ${e.message}")
                            }
                        }
                    }
                    awaitAll(*deferred.toTypedArray())
                }
                val channels = allById.values.toList()
                cachedChannels = channels
                cacheTime = now
                channels
            }
        }

    private fun parseM3uStream(url: String): List<IptvChannel> {
        val entries = M3uParser.parse(url, client)
        return entries.map { entry ->
            IptvChannel(
                id = entry.tvgId ?: "iptv_${entry.name.hashCode().toLong()}",
                name = entry.name,
                streamUrl = entry.streamUrl,
                logoUrl = entry.logoUrl,
                category = entry.category,
                country = M3uParser.inferCountry(entry.tvgId),
                language = null,
                quality = M3uParser.inferQuality(entry.streamUrl, entry.name),
                headers = entry.headers,
                drmKeyId = entry.drmKeyId,
                drmKey = entry.drmKey,
            )
        }
    }
}
