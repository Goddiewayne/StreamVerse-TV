package com.streamverse.core.data.remote.stmify

import com.google.gson.Gson
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StmifyClient @Inject constructor(
    private val gson: Gson,
    private val dispatchers: StreamVerseDispatchers,
) {
    private val baseUrl = "https://stmify.com"
    private val apiBase = "$baseUrl/wp-json/wp/v2"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetchChannels(page: Int = 1, perPage: Int = 100): Result<List<StmifyChannel>> =
        withContext(dispatchers.io) {
            runCatching {
                val json = fetchUrl("$apiBase/live-tv?per_page=$perPage&page=$page&_embed=wp:featuredmedia,wp:term")
                val dtos: List<StmifyChannelDto> = gson.fromJson(
                    json, object : TypeToken<List<StmifyChannelDto>>() {}.type
                )
                dtos.map { it.toStmifyChannel(baseUrl) }
            }
        }

    suspend fun fetchChannelBySlug(slug: String): Result<StmifyChannel> =
        withContext(dispatchers.io) {
            runCatching {
                val json = fetchUrl("$apiBase/live-tv?slug=$slug&_embed=wp:featuredmedia,wp:term")
                val dtos: List<StmifyChannelDto> = gson.fromJson(
                    json, object : TypeToken<List<StmifyChannelDto>>() {}.type
                )
                val dto = dtos.firstOrNull() ?: throw RuntimeException("Channel not found: $slug")
                dto.toStmifyChannel(baseUrl)
            }
        }

    suspend fun fetchChannelsFromArchive(page: Int = 1): Result<List<StmifyChannel>> =
        withContext(dispatchers.io) {
            runCatching {
                val url = if (page == 1) "$baseUrl/live-tv/" else "$baseUrl/live-tv/page/$page/"
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(15000)
                    .get()
                parseArchiveItems(doc, baseUrl)
            }
        }

    suspend fun resolveStreamUrl(slug: String): Result<String> =
        withContext(dispatchers.io) {
            runCatching { "$baseUrl/live-tv/$slug/" }
        }

    suspend fun resolveDirectStream(slug: String): Result<StreamInfo> =
        withContext(dispatchers.io) {
            runCatching {
                android.util.Log.d("StmifyClient", "resolveDirectStream: slug=$slug")
                val pageHtml = fetchUrlWithReferer("$baseUrl/live-tv/$slug/", "https://stmify.com")
                // The embed slug can differ from the page slug (e.g. page "fifa" →
                // embed "fifa-plus-uk-jw"). Capture the embed slug + country generically
                // rather than assuming embed slug == page slug.
                val embedMatch = Regex("cdn\\.stmify\\.com/embed-free/v1/(.+?)-([a-z]{2})-[a-z0-9]")
                    .find(pageHtml)
                    ?: throw RuntimeException("Embed not found for slug: $slug")
                val embedSlug = embedMatch.groupValues[1]   // e.g. "fifa-plus"
                val country = embedMatch.groupValues[2]     // e.g. "uk"
                android.util.Log.d("StmifyClient", "embedSlug=$embedSlug country=$country")
                val streamKey = embedSlug.uppercase().replace("-", "_")
                android.util.Log.d("StmifyClient", "streamKey=$streamKey")
                val streamsJson = fetchUrlWithReferer(
                    "https://cdn.stmify.com/embed-free/fetch_streams.php?country=$country",
                    "$baseUrl/live-tv/$slug/",
                    extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
                )
                android.util.Log.d("StmifyClient", "streamsJson size=${streamsJson.length}")
                // Parse leniently: some entries have nested objects, so a rigid
                // Map<String,Map<String,String>> type fails. Navigate the JSON tree and
                // pull just our stream entry.
                val root = com.google.gson.JsonParser.parseString(streamsJson).asJsonObject
                fun objFor(key: String): com.google.gson.JsonObject? =
                    root.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
                val streamData = objFor(streamKey)
                    ?: objFor(streamKey.replace("_", ""))
                    ?: objFor(slug.uppercase().replace("-", "_"))
                    ?: run {
                        android.util.Log.w("StmifyClient", "Stream keys available: ${root.keySet().joinToString()}")
                        throw RuntimeException("Stream not found: $streamKey")
                    }
                fun str(field: String): String? =
                    streamData.get(field)?.takeIf { it.isJsonPrimitive }?.asString
                val url = str("url") ?: throw RuntimeException("No URL for stream: $streamKey")
                val k1 = str("k1")
                val k2 = str("k2")
                android.util.Log.d("StmifyClient", "direct URL=$url k1=$k1 k2=$k2")
                StreamInfo(url = url, drmKeyId = k1, drmKey = k2)
            }.onFailure { android.util.Log.w("StmifyClient", "resolveDirectStream failed: ${it.message}") }
        }

    suspend fun searchChannels(query: String): Result<List<StmifyChannel>> =
        withContext(dispatchers.io) {
            val apiResult = runCatching {
                val json = fetchUrl("$apiBase/live-tv?search=$query&_embed=wp:featuredmedia,wp:term&per_page=20")
                val dtos: List<StmifyChannelDto> = gson.fromJson(
                    json, object : TypeToken<List<StmifyChannelDto>>() {}.type
                )
                dtos.map { it.toStmifyChannel(baseUrl) }
            }
            if (apiResult.isSuccess && apiResult.getOrNull()!!.isNotEmpty()) {
                return@withContext apiResult
            }
            searchChannelsFromArchive(query)
        }

    suspend fun searchChannelsFromArchive(query: String): Result<List<StmifyChannel>> =
        withContext(dispatchers.io) {
            runCatching {
                val url = "$baseUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}&post_type=live-tv"
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(15000)
                    .get()
                parseArchiveItems(doc, baseUrl)
            }
        }

    suspend fun fetchTotalCount(): Result<Int> = withContext(dispatchers.io) {
        runCatching {
            val response = client.newCall(
                Request.Builder().url("$apiBase/live-tv?per_page=1")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
            ).execute()
            response.header("X-WP-Total")?.toIntOrNull() ?: 0
        }
    }

    private fun parseArchiveItems(doc: Document, baseUrl: String): List<StmifyChannel> {
        return doc.select(".archive-item, .channel-item, article").mapNotNull { el ->
            val link = el.select("a[href]").first() ?: return@mapNotNull null
            val href = link.attr("href")
            val slug = href.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val name = link.text().takeIf { it.isNotBlank() }
                ?: el.select("h2, h3").text().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val img = el.select("img").first()
            val imgUrl = img?.attr("src")?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("http")) it else "$baseUrl/$it" }

            val quality = when {
                el.text().contains("4K") -> Quality._4K
                el.text().contains("HD") -> Quality.HD
                el.text().contains("SD") -> Quality.SD
                else -> null
            }

            StmifyChannel(
                id = slug,
                name = name,
                slug = slug,
                logoUrl = imgUrl,
                quality = quality,
                description = null,
                genres = emptyList(),
            )
        }
    }

    private fun fetchUrl(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code == 403) {
            android.util.Log.w("StmifyClient", "API 403 for: $url")
        }
        return response.body?.string()
            ?: throw RuntimeException("Empty response from $url")
    }

    private fun fetchUrlWithReferer(url: String, referer: String, extraHeaders: Map<String, String> = emptyMap()): String {
        val requestBuilder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Referer", referer)
            .header("DNT", "1")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
        extraHeaders.forEach { (key, value) -> requestBuilder.header(key, value) }
        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code == 403) {
            android.util.Log.w("StmifyClient", "Direct stream 403 for: $url")
        }
        return response.body?.string()
            ?: throw RuntimeException("Empty response from $url")
    }
}

private fun StmifyChannelDto.toStmifyChannel(baseUrl: String): StmifyChannel {
    val media = embedded?.featuredMedia?.firstOrNull()
    val imgUrl = media?.sourceUrl
        ?: media?.mediaDetails?.sizes?.entries
            ?.find { (key, _) -> key.contains("medium") || key.contains("large") }
            ?.value?.sourceUrl

    val genres = embedded?.terms?.flatten().orEmpty().map { it.name }

    val quality = when {
        title?.rendered?.contains("4K") == true -> Quality._4K
        title?.rendered?.contains("HD") == true -> Quality.HD
        else -> null
    }

    return StmifyChannel(
        id = slug,
        name = title?.rendered?.trim().orEmpty(),
        slug = slug,
        logoUrl = imgUrl?.let { if (it.startsWith("http")) it else "$baseUrl/$it" },
        quality = quality,
        description = excerpt?.rendered?.let { Jsoup.parse(it).text() },
        genres = genres,
    )
}
