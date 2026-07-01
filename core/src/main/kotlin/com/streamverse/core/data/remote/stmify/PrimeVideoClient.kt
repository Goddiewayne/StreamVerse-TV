package com.streamverse.core.data.remote.stmify

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.streamverse.core.data.model.StmifyChannel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.util.StreamInfo
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrimeVideoClient @Inject constructor(
    private val gson: Gson,
    private val dispatchers: StreamVerseDispatchers,
    private val okHttpClient: OkHttpClient,
) {
    private val baseUrl = "https://cdn.stmify.com/primevideo"

    @Volatile private var cachedChannels: List<StmifyChannel>? = null
    @Volatile private var cacheTime: Long = 0L
    private val cacheTtlMs = 5 * 60 * 1000L

    suspend fun fetchChannels(): Result<List<StmifyChannel>> =
        withContext(dispatchers.io) {
            runCatching {
                val now = System.currentTimeMillis()
                val cached = cachedChannels
                if (cached != null && now - cacheTime < cacheTtlMs) {
                    cached
                } else {
                    val json = fetchUrl("$baseUrl/template-parts/get-channels.php")
                    val map: Map<String, PrimeVideoChannelEntry> = gson.fromJson(
                        json, object : TypeToken<Map<String, PrimeVideoChannelEntry>>() {}.type
                    )
                    val result = map.map { (key, entry) ->
                        val quality = if (entry.hd) Quality.HD else null
                        StmifyChannel(
                            id = key,
                            name = key.replace("_", " ").lowercase()
                                .replaceFirstChar { it.uppercase() },
                            slug = key.lowercase().replace("_", "-"),
                            logoUrl = entry.logo,
                            quality = quality,
                            description = entry.description,
                            genres = entry.tagline?.split(",")?.map { it.trim() }.orEmpty(),
                        )
                    }
                    cachedChannels = result
                    cacheTime = System.currentTimeMillis()
                    result
                }
            }
        }

    suspend fun resolveDirectStream(channelId: String): Result<StreamInfo> =
        withContext(dispatchers.io) {
            runCatching {
                val streamJson = fetchUrlWithReferer(
                    "$baseUrl/template-parts/get-stream/${channelId.uppercase()}",
                    "$baseUrl/",
                    extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"),
                )
                val root = JsonParser.parseString(streamJson).asJsonObject
                val fileName = root.get("fileName")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: throw RuntimeException("No file name for stream: $channelId")
                val k1 = root.get("k1")?.takeIf { it.isJsonPrimitive }?.asString
                val k2 = root.get("k2")?.takeIf { it.isJsonPrimitive }?.asString
                val url = "$baseUrl/stream/${channelId.uppercase()}/$fileName"

                StreamInfo(
                    url = url,
                    requiresBrowser = false,
                    forceWebView = false,
                    headers = mapOf(
                        "Referer" to "$baseUrl/",
                        "Origin" to "https://cdn.stmify.com",
                    ),
                    drmKeyId = k1,
                    drmKey = k2,
                )
            }
        }

    private fun fetchUrl(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()
        val response = okHttpClient.newCall(request).execute()
        return response.body?.string()
            ?: throw RuntimeException("Empty response from $url")
    }

    private fun fetchUrlWithReferer(
        url: String,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val requestBuilder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .header("Referer", referer)
            .header("DNT", "1")
        extraHeaders.forEach { (key, value) -> requestBuilder.header(key, value) }
        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        return response.body?.string()
            ?: throw RuntimeException("Empty response from $url")
    }
}

private data class PrimeVideoChannelEntry(
    val logo: String?,
    val tagline: String?,
    val hd: Boolean = false,
    val description: String?,
    val country: String?,
)
