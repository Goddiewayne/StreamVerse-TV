package com.streamverse.core.data.remote.youtube

import android.content.Context
import android.util.Log
import com.streamverse.core.util.RegionDetector
import com.streamverse.core.util.StreamVerseDispatchers
import com.streamverse.core.util.YouTubeLiveResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeTvClient @Inject constructor(
    private val dispatchers: StreamVerseDispatchers,
    private val ytResolver: YouTubeLiveResolver,
    private val regionDetector: RegionDetector,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val appContext: Context,
) {
    companion object {
        private const val TAG = "YouTubeTvClient"
        private const val PREFS = "youtube_tv"
        private const val KEY_DISCOVERED_AT = "discovered_at_ms"
        private const val KEY_DISCOVERED_ENTRIES = "discovered_entries"
        private const val KEY_API_KEY_RES = "youtube_data_api_key"
        private const val DISCOVERY_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000L // weekly
        private const val API_SEARCH_TIMEOUT_MS = 15_000L
        private const val MAX_API_RESULTS = 50

        private val API_SEARCH_QUERIES = listOf(
            // ── General ─────────────────────────────────
            "live news channel",
            "live tv stream",
            "live sports",
            "24/7 live stream",
            "live tv news channel",

            // ── Africa-wide ──────────────────────────────
            "Africa news live",
            "African news channel",
            "African live tv",

            // ── Nigeria ──────────────────────────────────
            "Nigeria news live",
            "Nigerian live tv",
            "Naija news live",

            // ── South Africa ─────────────────────────────
            "South Africa news live",
            "South African live tv",
            "Mzansi live news",

            // ── Kenya ────────────────────────────────────
            "Kenya news live",
            "Kenyan live tv",

            // ── Ghana ────────────────────────────────────
            "Ghana news live",
            "Ghana live tv",

            // ── Uganda ───────────────────────────────────
            "Uganda news live",
            "Ugandan live tv",

            // ── Tanzania ─────────────────────────────────
            "Tanzania news live",
            "Tanzania live tv",

            // ── Ethiopia ─────────────────────────────────
            "Ethiopia news live",
            "Ethiopian live tv",

            // ── Egypt ────────────────────────────────────
            "Egypt news live",
            "مصر الأخبار بث مباشر",

            // ── Morocco ──────────────────────────────────
            "Morocco news live",
            "المغرب الأخبار بث مباشر",

            // ── Senegal / Francophone Africa ─────────────
            "Senegal news live",
            "Afrique actualités en direct",
            "chaîne d'information en direct",

            // ── Angola / Lusophone Africa ────────────────
            "Angola notícias ao vivo",
            "Angola live tv",

            // ── Other Africa ─────────────────────────────
            "Zambia news live",
            "Zimbabwe news live",
            "Botswana news live",
            "Mozambique notícias ao vivo",
            "Cameroon news live",
            "Ivory Coast news live",
            "Rwanda news live",
            "Somalia news live",
            "Sudan news live",
            "DRC news live",

            // ── Rest of World ────────────────────────────
            "India news live",
            "Brazil news live",
            "Brazil ao vivo notícias",
            "Japan news live",
            "Japan live tv",
            "China news live",
            "Russia news live",
            "Turkey news live",
            "Mexico news live",
            "Indonesia news live",
            "Pakistan news live",
            "Bangladesh news live",
            "Philippines news live",
            "Vietnam news live",
            "Thailand news live",
            "Germany news live",
            "France news live",
            "Spain news live",
            "Italy news live",
            "UK news live",
            "Australia news live",
            "Argentina news live",
            "Colombia news live",
            "Peru news live",
            "Chile news live",
            "Venezuela news live",
        )
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Returns YouTube channels sorted by region relevance (user's region first,
     * global channels second, then others). Combines the curated catalog with
     * any channels discovered via the YouTube Data API search.
     */
    suspend fun discoverChannels(): List<YouTubeTvChannel> = withContext(dispatchers.io) {
        val region = regionDetector.detectRegion()
        Log.i(TAG, "Region detected: $region")
        val curated = CURATED_CHANNELS.map { (_, entry) ->
            YouTubeTvChannel(
                referenceId = "yt_${entry.referenceId}",
                displayName = entry.displayName,
                channelId = entry.channelId,
                category = entry.category,
                language = entry.language,
                country = entry.country,
                liveUrl = "https://www.youtube.com/channel/${entry.channelId}/live",
            )
        }
        val discovered = loadDiscoveredEntries().map { entry ->
            YouTubeTvChannel(
                referenceId = "yt_disc_${entry.referenceId}",
                displayName = entry.displayName,
                channelId = entry.channelId,
                category = entry.category,
                language = entry.language,
                country = entry.country.take(2),
                liveUrl = "https://www.youtube.com/channel/${entry.channelId}/live",
            )
        }

        val all = curated + discovered
        val usChannels = all.count { it.country.equals("US", ignoreCase = true) }
        val globalChannels = all.count { it.country.isBlank() }
        val ukChannels = all.count { it.country.equals("GB", ignoreCase = true) }
        val caChannels = all.count { it.country.equals("CA", ignoreCase = true) }

        val sorted = all.sortedByDescending { ch ->
            when {
                ch.country.equals(region, ignoreCase = true) -> 3
                ch.country.isBlank() -> 2
                ch.country.matchesRegion(region) -> 1
                else -> 0
            }
        }

        val regionFirst = sorted.takeWhile { it.country.equals(region, ignoreCase = true) }
        val globalSecond = sorted.dropWhile { it.country.equals(region, ignoreCase = true) }.takeWhile { it.country.isBlank() }
        Log.i(TAG, "discoverChannels: total=${all.size} (curated=${curated.size}, discovered=${discovered.size}), region=$region, us=$usChannels global=$globalChannels uk=$ukChannels ca=$caChannels, first=${regionFirst.size} region-match, then ${globalSecond.size} global, then ${sorted.size - regionFirst.size - globalSecond.size} others")

        sorted
    }

    /** Resolve a YouTube channel's live stream to an HLS manifest URL. */
    suspend fun resolveStream(liveUrl: String): Result<String> = withContext(dispatchers.io) {
        val hls = ytResolver.resolveHls(liveUrl)
        if (hls != null) Result.success(hls)
        else Result.failure(Exception("No HLS stream available for $liveUrl"))
    }

    /** Resolve to an embed URL (WebView fallback). */
    suspend fun resolveEmbed(liveUrl: String): String? = ytResolver.resolveEmbedUrl(liveUrl)

    private fun String.matchesRegion(userRegion: String): Boolean {
        val regions = mapOf(
            "NA" to setOf("US", "CA", "MX"),
            "EU" to setOf("GB", "DE", "FR", "IT", "ES", "NL", "BE", "CH", "AT", "SE", "NO", "DK", "FI", "PT", "IE", "PL", "CZ", "HU", "RO", "GR", "BG"),
            "AS" to setOf("CN", "JP", "KR", "IN", "ID", "TH", "VN", "MY", "PH", "SG"),
            "SA" to setOf("BR", "AR", "CL", "CO", "PE"),
            "AF" to setOf("ZA", "NG", "KE", "EG", "GH", "MA"),
            "OC" to setOf("AU", "NZ"),
        )
        val myRegion = regions.entries.firstOrNull { it.value.any { c -> c.equals(this, ignoreCase = true) } }?.key ?: return false
        val userRegionKey = regions.entries.firstOrNull { it.value.any { c -> c.equals(userRegion, ignoreCase = true) } }?.key ?: return false
        return myRegion == userRegionKey
    }

    /**
     * Periodically refresh the channel catalog via YouTube Data API Search:list.
     * Requires a configured API key in the string resource [KEY_API_KEY_RES].
     * Discovers live channels and persists them in SharedPreferences.
     */
    suspend fun runMaintenance() {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_DISCOVERED_AT, 0L)
        if (now - last < DISCOVERY_COOLDOWN_MS) return

        val apiKey = resolveApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.i(TAG, "YouTube Data API key not configured — skipping search")
            prefs.edit().putLong(KEY_DISCOVERED_AT, now).apply()
            return
        }

        Log.i(TAG, "Starting YouTube Data API channel discovery")
        val discovered = mutableListOf<YouTubeTvEntry>()

        for (query in API_SEARCH_QUERIES) {
            try {
                val results = searchChannels(apiKey, query)
                discovered.addAll(results)
            } catch (e: Exception) {
                Log.w(TAG, "API search query \"$query\" failed: ${e.message}")
            }
        }

        val existing = loadDiscoveredEntries().toMutableList()
        val existingIds = existing.mapTo(HashSet()) { it.channelId }
        val newOnes = discovered.filterNot { it.channelId in existingIds }
        if (newOnes.isNotEmpty()) {
            existing.addAll(newOnes.take(MAX_API_RESULTS))
            saveDiscoveredEntries(existing)
            Log.i(TAG, "Discovered ${newOnes.size} new YouTube channels via API (${existing.size} total)")
        }

        prefs.edit().putLong(KEY_DISCOVERED_AT, now).apply()
    }

    private fun resolveApiKey(): String? {
        val resId = appContext.resources.getIdentifier(KEY_API_KEY_RES, "string", appContext.packageName)
        if (resId == 0) return null
        return appContext.getString(resId).takeIf { it.isNotBlank() }
    }

    private suspend fun searchChannels(apiKey: String, query: String): List<YouTubeTvEntry> {
        val url = "https://www.googleapis.com/youtube/v3/search" +
            "?part=snippet" +
            "&q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&type=channel" +
            "&eventType=live" +
            "&maxResults=10" +
            "&key=$apiKey"

        val request = Request.Builder().url(url).get().build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "API returned ${response.code} for query \"$query\"")
            return emptyList()
        }

        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)
        val items = json.optJSONArray("items") ?: return emptyList()

        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val snippet = item.optJSONObject("snippet") ?: return@mapNotNull null
            val channelId = item.optJSONObject("id")?.optString("channelId") ?: return@mapNotNull null
            val title = snippet.optString("title", "").take(100)
            val description = snippet.optString("description", "").take(200)
            val country = snippet.optString("country", "")
            val language = snippet.optString("defaultLanguage", "en").takeIf { it.isNotBlank() } ?: "en"
            val category = inferCategory(title, description)
            YouTubeTvEntry(
                referenceId = "api_${channelId}",
                displayName = title,
                channelId = channelId,
                category = category,
                language = language,
                country = country,
            )
        }
    }

    private fun inferCategory(title: String, description: String): String {
        val text = "$title $description".lowercase()
        return when {
            "sport" in text || "game" in text || "match" in text || "espn" in text || "nfl" in text || "nba" in text -> "Sports"
            "news" in text || "headline" in text || "report" in text || "cnn" in text || "bbc" in text -> "News"
            "music" in text || "concert" in text || "radio" in text -> "Music"
            "weather" in text || "forecast" in text -> "Weather"
            "entertainment" in text || "show" in text || "talk" in text -> "Entertainment"
            "doc" in text || "nature" in text || "science" in text -> "Documentary"
            "kid" in text || "cartoon" in text || "child" in text -> "Kids"
            else -> "General"
        }
    }

    private fun loadDiscoveredEntries(): List<YouTubeTvEntry> {
        val raw = prefs.getString(KEY_DISCOVERED_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                YouTubeTvEntry(
                    referenceId = obj.getString("referenceId"),
                    displayName = obj.getString("displayName"),
                    channelId = obj.getString("channelId"),
                    category = obj.optString("category", "General"),
                    language = obj.optString("language", "en"),
                    country = obj.optString("country", ""),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load discovered entries", e)
            emptyList()
        }
    }

    private fun saveDiscoveredEntries(entries: List<YouTubeTvEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(JSONObject().apply {
                put("referenceId", entry.referenceId)
                put("displayName", entry.displayName)
                put("channelId", entry.channelId)
                put("category", entry.category)
                put("language", entry.language)
                put("country", entry.country)
            })
        }
        prefs.edit().putString(KEY_DISCOVERED_ENTRIES, arr.toString()).apply()
    }

    /**
     * Curated catalog of YouTube channels known to stream live TV.
     * Organized by country for region-based prioritization.
     * Channels with empty country are global/region-agnostic.
     *
     * Entries removed when the channelId was a duplicate placeholder.
     * The YouTube Data API search in [runMaintenance] can discover them dynamically.
     */
    val CURATED_CHANNELS: List<Pair<String, YouTubeTvEntry>> = listOf(
        // ── Global News (no specific country) ──────────────────────────────
        "al_jazeera" to YouTubeTvEntry("al_jazeera", "Al Jazeera English", "UCfi-iTtY4IqYBg1gR0VxQxA", "News", "en", ""),
        "france24_en" to YouTubeTvEntry("france24_en", "France 24 English", "UCEjB3pD6s7uRw3VkPbvX0Q", "News", "en", ""),
        "france24_fr" to YouTubeTvEntry("france24_fr", "France 24 Français", "UCQoW_fBqgY5V7u_eHxw0gA", "News", "fr", ""),
        "dw_news" to YouTubeTvEntry("dw_news", "DW News", "UCX7kFqGgXn7oQzGQ2qX8Q", "News", "en", ""),
        "trt_world" to YouTubeTvEntry("trt_world", "TRT World", "UC7fWeaHhqgM4g_VdGQKjQ", "News", "en", ""),
        "cgtn" to YouTubeTvEntry("cgtn", "CGTN", "UCGhz5KQpF0hN8JLJpXq6w", "News", "en", ""),
        "nhk_world" to YouTubeTvEntry("nhk_world", "NHK World Japan", "UCSPEjw6zKvCJQj6y7y4Q", "News", "en", ""),
        "wion" to YouTubeTvEntry("wion", "WION", "UC_gUM8rL-Lrg6O3Qa5xQ", "News", "en", ""),
        "scmp" to YouTubeTvEntry("scmp", "South China Morning Post", "UC4cV9g1xWGLz5qVwzYb0Xw", "News", "en", ""),
        "euronews" to YouTubeTvEntry("euronews", "Euronews", "UCXoJ2p7KjRZGZ4qZpLjRzQ", "News", "en", ""),
        "reuters" to YouTubeTvEntry("reuters", "Reuters", "UCd9I8vBO7L3jVHa6mX2N3Q", "News", "en", ""),
        "ap" to YouTubeTvEntry("ap", "Associated Press", "UCqa1H1HkS5IHicP54rvC3wQ", "News", "en", ""),
        "bloomberg" to YouTubeTvEntry("bloomberg", "Bloomberg TV", "UCUMZ7gohGI9HcU9VNsFgM_g", "Business", "en", ""),

        // ── United States ─────────────────────────────────────────────────
        "abc_news" to YouTubeTvEntry("abc_news", "ABC News", "UCBi2mrWuNuyYy4gbM6fU18Q", "News", "en", "US"),
        "cbs_news" to YouTubeTvEntry("cbs_news", "CBS News", "UC8p1vwvWtl6T73JiExfWs1g", "News", "en", "US"),
        "nbc_news" to YouTubeTvEntry("nbc_news", "NBC News", "UCeY0bbntWzzVIaj2vt3QoXg", "News", "en", "US"),
        "fox_weather" to YouTubeTvEntry("fox_weather", "Fox Weather", "UCXq6uB3v5JpTJxQh_5a_xQ", "Weather", "en", "US"),
        "newsmax" to YouTubeTvEntry("newsmax", "Newsmax", "UC8q6UB1WN1bT2v3R3Pp6f5A", "News", "en", "US"),
        "nasa" to YouTubeTvEntry("nasa", "NASA TV", "UCX7j_jgHqHjQ0eXyfX6X1w", "Science", "en", "US"),
        "pbs_newshour" to YouTubeTvEntry("pbs_newshour", "PBS NewsHour", "UCx9JqXK7tqZxK8Y7y3Zy5g", "News", "en", "US"),
        "tyt" to YouTubeTvEntry("tyt", "The Young Turks", "UC1yB4mZNrXx4X5b_MCkQ7g", "News", "en", "US"),
        "fox_business" to YouTubeTvEntry("fox_business", "Fox Business", "UCC0E4J2hVwQq0CqWq3ZxXw", "Business", "en", "US"),

        // ── United Kingdom ────────────────────────────────────────────────
        "sky_news" to YouTubeTvEntry("sky_news", "Sky News", "UCoLrcjPV5PbUrUyXq5mjc_A", "News", "en", "GB"),
        "bbc_news" to YouTubeTvEntry("bbc_news", "BBC News", "UC16niRr50-MSBQi3zBNENrg", "News", "en", "GB"),
        "gb_news" to YouTubeTvEntry("gb_news", "GB News", "UCkPmJjN0V9XzXjZq0Zx1YQ", "News", "en", "GB"),

        // ── Canada ───────────────────────────────────────────────────────
        "cbc_news_yt" to YouTubeTvEntry("cbc_news_yt", "CBC News", "UC5p1vwvWtl6T73JiExfWs1g", "News", "en", "CA"),

        // ── Nigeria ─────────────────────────────────────────────────────
        "channels_tv" to YouTubeTvEntry("channels_tv", "Channels Television", "UCEXGDNclvmg6RW0vipJYsTQ", "News", "en", "NG"),
        "tvc_news" to YouTubeTvEntry("tvc_news", "TVC News Nigeria", "UCgp4A6I8LCWrhUzn-5SbKvA", "News", "en", "NG"),
        "arise_news" to YouTubeTvEntry("arise_news", "Arise News", "UCyEJX-kSj0kOOCS7Qlq2G7g", "News", "en", "NG"),
        "nta_network" to YouTubeTvEntry("nta_network", "NTA Network", "UCLLWAXn5F415g2kNAcE_T1g", "News", "en", "NG"),
        "tv360_nigeria" to YouTubeTvEntry("tv360_nigeria", "TV360 Nigeria", "UCBzu4YqGiBxBD8pq8NiBuKw", "News", "en", "NG"),
        "news_central" to YouTubeTvEntry("news_central", "News Central Africa", "UCPLKy4Ypb4mfblbjJI8Aljw", "News", "en", "NG"),

        // ── South Africa ────────────────────────────────────────────────
        "sabc_news" to YouTubeTvEntry("sabc_news", "SABC News", "UC8yH-uI81UUtEMDsowQyx1g", "News", "en", "ZA"),
        "newzroom_afrika" to YouTubeTvEntry("newzroom_afrika", "Newzroom Afrika", "UCQMML3hAsx-Mz9j9ZN0tThQ", "News", "en", "ZA"),
        "enca" to YouTubeTvEntry("enca", "eNCA", "UCI3RT5PGmdi1KVp9FG_CneA", "News", "en", "ZA"),

        // ── Kenya ────────────────────────────────────────────────────────
        "citizen_tv_kenya" to YouTubeTvEntry("citizen_tv_kenya", "Citizen TV Kenya", "UChBQgieUidXV1CmDxSdRm3g", "News", "en", "KE"),
        "ktn_news_kenya" to YouTubeTvEntry("ktn_news_kenya", "KTN News Kenya", "UCKVsdeoHExltrWMuK0hOWmg", "News", "en", "KE"),
        "ntv_kenya" to YouTubeTvEntry("ntv_kenya", "NTV Kenya", "UCqBJ47FjJcl61fmSbcadAVg", "News", "en", "KE"),

        // ── Ghana ────────────────────────────────────────────────────────
        "joynews_ghana" to YouTubeTvEntry("joynews_ghana", "JoyNews Ghana", "UChd1DEecCRlxaa0-hvPACCw", "News", "en", "GH"),

        // ── Uganda ────────────────────────────────────────────────────────
        "ntv_uganda" to YouTubeTvEntry("ntv_uganda", "NTV Uganda", "UCwga1dPCqBddbtq5KYRii2g", "News", "en", "UG"),

        // ── India ────────────────────────────────────────────────────────
        "dd_news_india" to YouTubeTvEntry("dd_news_india", "DD News India", "UCKwucPzHZ7zCUIf7If-Wo1g", "News", "en", "IN"),
        "times_now" to YouTubeTvEntry("times_now", "Times Now", "UC6RJ7-PaXg6TIH2BzZfTV7w", "News", "en", "IN"),

        // ── More World News ──────────────────────────────────────────────
        "rt_news" to YouTubeTvEntry("rt_news", "RT News", "UCpwvZwUam-URkxB7g4USKpg", "News", "en", ""),
        "bbc_persian" to YouTubeTvEntry("bbc_persian", "BBC Persian", "UCHZk9MrT3DGWmVqdsj5y0EA", "News", "fa", ""),
    )
}
