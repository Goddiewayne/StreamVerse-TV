package com.streamverse.core.data.remote.dlhd

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamverse.core.data.model.DlhdChannel
import com.streamverse.core.data.model.DlhdSchedule
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DlhdClient @Inject constructor(
    private val gson: Gson,
    private val dispatchers: StreamVerseDispatchers,
) {
    private val mirrorPageUrl = "https://daddylive.pk/"
    private val fallbackDomains = listOf(
        "https://dlhd.st",
        "https://dlhd.pk",
        "https://dlhd.sx",
        "https://dlhd.com",
        "https://dlhd.to",
    )
    @Volatile private var baseUrl: String = fallbackDomains.first()
    private val apiUrl get() = "$baseUrl/daddyapi.php"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Discover the current working DLHD domain from the official mirror page. */
    private fun discoverDomain(): String {
        // 1. Try the mirror page for authoritative list
        try {
            val mirrorHtml = fetchUrl(mirrorPageUrl)
            val mirrorRegex = Regex("""https?://(?:www\.)?(dlhd\.[a-z]+)(?=/|"|')""")
            val candidates = mirrorRegex.findAll(mirrorHtml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            if (candidates.isNotEmpty()) {
                android.util.Log.d("DlhdClient", "Mirrors from $mirrorPageUrl: $candidates")
                for (domain in candidates) {
                    try {
                        val req = Request.Builder()
                            .url("https://$domain/")
                            .headers(browserHeaders())
                            .head()
                            .build()
                        val resp = client.newCall(req).execute()
                        val alive = resp.code < 400
                        resp.close()
                        if (alive) {
                            val newUrl = "https://$domain"
                            if (newUrl != baseUrl) {
                                android.util.Log.w("DlhdClient", "Domain rotated: $baseUrl -> $newUrl")
                                baseUrl = newUrl
                            }
                            return newUrl
                        }
                    } catch (_: Exception) { continue }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("DlhdClient", "Mirror page unreachable: ${e.message}")
        }

        // 2. Fallback: probe the known domains directly
        for (domain in fallbackDomains) {
            try {
                val req = Request.Builder()
                    .url(domain)
                    .headers(browserHeaders())
                    .head()
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.code < 400) {
                    resp.close()
                    if (domain != baseUrl) {
                        android.util.Log.w("DlhdClient", "Domain rotated (fallback): $baseUrl -> $domain")
                        baseUrl = domain
                    }
                    return domain
                }
                resp.close()
            } catch (_: Exception) { continue }
        }

        return baseUrl // stick with current if all fail
    }

    private val domainInitialized: Unit by lazy { discoverDomain(); Unit }

    private fun ensureDomain() { domainInitialized }

    suspend fun fetchChannelsFromApi(apiKey: String): Result<List<DlhdChannel>> =
        withContext(dispatchers.io) {
            ensureDomain()
            runCatching {
                val json = fetchUrl("$apiUrl?key=$apiKey&endpoint=channels")
                val channels: List<DlhdChannelDto> = gson.fromJson(
                    json, object : TypeToken<List<DlhdChannelDto>>() {}.type
                )
                channels.map { it.toDlhdChannel(baseUrl) }
            }
        }

    suspend fun fetchChannelsFromScrape(): Result<List<DlhdChannel>> =
        withContext(dispatchers.io) {
            ensureDomain()
            runCatching {
                val doc = Jsoup.connect("$baseUrl/24-7-channels.php")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(15000)
                    .get()
                parseChannelCards(doc, baseUrl)
            }
        }

    suspend fun fetchSchedule(apiKey: String): Result<List<DlhdSchedule>> =
        withContext(dispatchers.io) {
            ensureDomain()
            runCatching {
                val json = fetchUrl("$apiUrl?key=$apiKey&endpoint=schedule")
                val scheduleMap: Map<String, Map<String, List<DlhdScheduleDayDto>>> = gson.fromJson(
                    json, object : TypeToken<Map<String, Map<String, List<DlhdScheduleDayDto>>>>() {}.type
                )
                scheduleMap.flatMap { (day, categories) ->
                    categories.flatMap { (category, events) ->
                        events.map { event ->
                            DlhdSchedule(
                                date = day,
                                category = category,
                                time = event.time,
                                event = event.event,
                                channelIds = (event.channels.orEmpty() + event.channels2.orEmpty())
                                    .mapNotNull { it.channelId }
                            )
                        }
                    }
                }
            }
        }

    suspend fun resolveStreamUrl(channelId: String): Result<String> =
        withContext(dispatchers.io) {
            ensureDomain()
            runCatching {
                resolveDirectStream(channelId)
                    ?: "$baseUrl/watch.php?id=$channelId"
            }
        }

    private fun resolveDirectStream(channelId: String): String? {
        return try {
            val streamPageUrl = "$baseUrl/stream/stream-$channelId.php"
            val streamHtml = fetchUrlWithReferer(streamPageUrl, "$baseUrl/24-7-channels.php")
            android.util.Log.d("DlhdClient", "streamPage length=${streamHtml.length} containsAccessBlocked=${streamHtml.contains("Access Blocked")} containsIframe=${streamHtml.contains("<iframe")}")
            val doc = Jsoup.parse(streamHtml)
            val iframeSrc = doc.select("iframe").firstOrNull()?.attr("src")?.takeIf { it.isNotBlank() }
            if (iframeSrc == null) {
                android.util.Log.d("DlhdClient", "no iframe found in stream page")
                return null
            }
            android.util.Log.d("DlhdClient", "iframe src=$iframeSrc")
            val iframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$baseUrl$iframeSrc"
            val iframeHtml = fetchUrlWithReferer(iframeUrl, streamPageUrl)
            android.util.Log.d("DlhdClient", "iframePage length=${iframeHtml.length} containsAtob=${iframeHtml.contains("atob")}")
            val regex = Regex("""atob\s*\(\s*'([^']+)'\s*\)""")
            val match = regex.find(iframeHtml)
            if (match != null) {
                val base64 = match.groupValues[1]
                val decodedBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val hlsUrl = String(decodedBytes, Charsets.UTF_8)
                android.util.Log.d("DlhdClient", "hlsUrl=$hlsUrl")
                if (hlsUrl.isNotBlank() && hlsUrl.startsWith("http")) return hlsUrl
            }
            android.util.Log.d("DlhdClient", "no atob match in iframe page")
            null
        } catch (_: Exception) {
            android.util.Log.d("DlhdClient", "resolveDirectStream exception")
            null
        }
    }

    suspend fun fetchCategoriesFromSchedule(apiKey: String): Result<List<String>> =
        withContext(dispatchers.io) {
            ensureDomain()
            runCatching {
                val doc = Jsoup.connect(baseUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(15000)
                    .get()
                doc.select(".schedule__categories a, .category-filter a")
                    .mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
                    .distinct()
                    .ifEmpty {
                        listOf(
                            "Soccer", "TV Shows", "Cricket", "Tennis", "Boxing",
                            "Golf", "Basketball", "Baseball (MLB)", "MMA", "Rugby",
                            "Motorsport", "Hockey", "American Football", "Darts", "Snooker"
                        )
                    }
            }
        }

    private fun parseChannelCards(doc: Document, baseUrl: String): List<DlhdChannel> {
        return doc.select("a.card, div.card, div.channel-item").mapNotNull { el ->
            val href = el.attr("href")
            val id = href.substringAfter("watch.php?id=").substringBefore("&")
                .takeIf { it.isNotBlank() }
                ?: el.attr("data-id").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val name = el.attr("data-title").takeIf { it.isNotBlank() }
                ?: el.select("h2, h3, .title, .name").text().takeIf { it.isNotBlank() }
                ?: el.text().trim().takeIf { it.length in 3..100 }
                ?: return@mapNotNull null
            DlhdChannel(
                id = id,
                name = name,
                logoUrl = el.select("img").attr("src").takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("http")) it else "$baseUrl/$it" },
                category = el.attr("data-category").takeIf { it.isNotBlank() },
            )
        }.filter { it.id.all { c -> c.isDigit() } }
    }

    private fun browserHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("DNT", "1")
        .add("Connection", "keep-alive")
        .add("Upgrade-Insecure-Requests", "1")
        .build()

    private fun fetchUrl(url: String): String {
        val request = Request.Builder().url(url).headers(browserHeaders()).build()
        return client.newCall(request).execute().body?.string()
            ?: throw RuntimeException("Empty response from $url")
    }

    private fun fetchUrlWithStatus(url: String): Pair<String, Int> {
        val request = Request.Builder().url(url).headers(browserHeaders()).build()
        val response = client.newCall(request).execute()
        return (response.body?.string() ?: "") to response.code
    }

    private fun fetchUrlWithReferer(url: String, referer: String): String {
        val request = Request.Builder().url(url).headers(browserHeaders())
            .header("Referer", referer)
            .build()
        return client.newCall(request).execute().body?.string()
            ?: throw RuntimeException("Empty response from $url")
    }
}

private fun DlhdChannelDto.toDlhdChannel(baseUrl: String) = DlhdChannel(
    id = channelId,
    name = channelName,
    logoUrl = logoUrl?.let {
        if (it.startsWith("http")) it else "$baseUrl/$it"
    },
    category = null,
)
