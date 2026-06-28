package com.streamverse.core.data.remote.iptv

import com.streamverse.core.data.remote.m3u.M3uParser
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FreeTvClient @Inject constructor(
    private val dispatchers: StreamVerseDispatchers,
    okHttpClient: OkHttpClient,
) {
    private val m3uUrl = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

    // Reuse the shared pool + HTTP cache.
    private val client = okHttpClient

    private var cachedChannels: List<IptvChannel>? = null
    private var cacheTime: Long = 0

    suspend fun fetchChannels(forceRefresh: Boolean = false): Result<List<IptvChannel>> =
        withContext(dispatchers.io) {
            runCatching {
                val now = System.currentTimeMillis()
                if (!forceRefresh && cachedChannels != null && (now - cacheTime) < 86_400_000L) {
                    return@runCatching cachedChannels!!
                }
                val channels = parseM3uStream(m3uUrl)
                cachedChannels = channels
                cacheTime = now
                channels
            }
        }

    private fun parseM3uStream(url: String): List<IptvChannel> {
        val entries = M3uParser.parse(url, client)
        return entries.map { entry ->
            IptvChannel(
                id = entry.tvgId ?: "freetv_${entry.name.hashCode().toLong()}",
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
