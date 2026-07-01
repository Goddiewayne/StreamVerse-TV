package com.streamverse.core.data.epg

import android.content.Context
import android.util.Log
import com.streamverse.core.data.remote.dlhd.DlhdClient
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.EpgEntry
import com.streamverse.core.domain.model.Programme
import com.streamverse.core.domain.model.ScheduleDay
import com.streamverse.core.domain.model.ScheduleEvent
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.StreamVerseDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class EpgSourceResult(
    val source: EpgSource,
    val channels: Map<String, List<EpgEntry>>,
    val scheduleDays: List<ScheduleDay> = emptyList(),
)

enum class EpgSource(val id: String, val label: String) {
    IPTV_ORG("iptv_org", "IPTV.org EPG"),
    DLHD_SCRAPED("dlhd_scraped", "Live Sports & TV"),
    CATEGORY_FALLBACK("category_fallback", "Smart Schedule"),
}

@Singleton
class EpgManager @Inject constructor(
    private val dlhdClient: DlhdClient,
    private val dispatchers: StreamVerseDispatchers,
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "EpgManager"
        private const val EPG_DIR = "epg"
        private const val EPG_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val MAX_EPG_FILES = 10
        private const val EPG_LOAD_TIMEOUT_MS = 30_000L
        private const val MAX_EPG_SOURCES = 3
        private const val MAX_ENTRIES_PER_FILE = 50_000
        private val IPTV_ORG_GZ_URLS = listOf(
            "https://iptv-org.github.io/epg/guide/af.xml.gz",
            "https://iptv-org.github.io/epg/guide/uk.xml.gz",
            "https://iptv-org.github.io/epg/guide/us.xml.gz",
        )
        private val IPTV_ORG_INDEX_URL =
            "https://iptv-org.github.io/epg/guide/index.json"
        private val DLHD_SCHEDULE_URL = "https://dlhd.pk/schedule.php"
    }

    private val epgDir by lazy { java.io.File(context.cacheDir, EPG_DIR).also { it.mkdirs() } }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var cachedEpg: Map<String, List<EpgEntry>> = emptyMap()
    private var cacheTime: Long = 0L
    private var scheduleFromDlhd: List<ScheduleDay> = emptyList()

    suspend fun loadEpg(
        channels: List<Channel>,
        channelIndex: Map<String, Channel> = emptyMap(),
        skipFallback: Boolean = false,
    ): Map<String, List<EpgEntry>> = withContext(dispatchers.io) {
        if (cachedEpg.isNotEmpty() && System.currentTimeMillis() - cacheTime < EPG_MAX_AGE_MS) {
            return@withContext cachedEpg
        }

        val results = mutableMapOf<String, MutableList<EpgEntry>>()
        val tvgIdMap = buildTvgIdMap(channels)

        runCatching {
            withTimeout(EPG_LOAD_TIMEOUT_MS) {
                val xmltvEpg = loadIptvOrgEpg(tvgIdMap)
                if (xmltvEpg.isNotEmpty()) {
                    xmltvEpg.forEach { (chId, entries) ->
                        results.getOrPut(chId) { mutableListOf() }.addAll(entries)
                    }
                    Log.d(TAG, "iptv-org EPG: ${xmltvEpg.size} channels mapped")
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "iptv-org EPG load timed out or failed: ${e.message}")
        }

        runCatching {
            withTimeout(15_000L) {
                if (dlhdScheduleReachable()) {
                    val dlhdSchedule = loadDlhdScrapedSchedule(channels)
                    if (dlhdSchedule.isNotEmpty()) {
                        scheduleFromDlhd = dlhdSchedule
                        val mapped = mapDlhdScheduleToEpg(dlhdSchedule, channels)
                        mapped.forEach { (chId, entries) ->
                            results.getOrPut(chId) { mutableListOf() }.addAll(entries)
                        }
                        Log.d(TAG, "DLHD scraped schedule: ${mapped.size} channels mapped")
                    }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "DLHD schedule load failed: ${e.message}")
        }

        if (!skipFallback) {
            for (ch in channels) {
                if (ch.id !in results || results[ch.id].isNullOrEmpty()) {
                    results[ch.id] = generateCategoryEpg(ch).toMutableList()
                }
            }
        }

        cachedEpg = results
        cacheTime = System.currentTimeMillis()
        results
    }

    suspend fun loadSchedule(
        channels: List<Channel>,
    ): List<ScheduleDay> = withContext(dispatchers.io) {
        if (scheduleFromDlhd.isNotEmpty()) return@withContext scheduleFromDlhd
        val days = runCatching {
            withTimeout(15_000L) {
                if (dlhdScheduleReachable()) loadDlhdScrapedSchedule(channels) else emptyList()
            }
        }.getOrDefault(emptyList())
        scheduleFromDlhd = days
        days
    }

    fun getCachedEpg(): Map<String, List<EpgEntry>> = cachedEpg

    fun invalidate() {
        cachedEpg = emptyMap()
        cacheTime = 0L
        scheduleFromDlhd = emptyList()
    }

    fun clearDiskCache() {
        epgDir.listFiles()?.forEach { it.delete() }
    }

    fun diskCacheSizeBytes(): Long =
        epgDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun buildTvgIdMap(channels: List<Channel>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (ch in channels) {
            val iptvSource = ch.sources[SourceType.GLOBAL_INDEX] ?: ch.sources[SourceType.FREE_CHANNEL]
            val refId = iptvSource?.referenceId ?: continue
            if (refId.contains(".")) map[refId] = ch.id
        }
        return map
    }

    private suspend fun loadIptvOrgEpg(
        tvgIdMap: Map<String, String>,
    ): Map<String, List<EpgEntry>> = withContext(dispatchers.io) {
        val result = mutableMapOf<String, MutableList<EpgEntry>>()
        val urls = discoverEpgUrls()

        for (url in urls) {
            try {
                val file = downloadGzIfNeeded(url) ?: continue
                val entries = parseXmltvGz(file, tvgIdMap)
                entries.forEach { (chId, epg) ->
                    result.getOrPut(chId) { mutableListOf() }.addAll(epg)
                }
                Log.d(TAG, "Parsed ${file.name}: ${entries.size} channels")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load $url: ${e.message}")
            }
        }
        result
    }

    private suspend fun discoverEpgUrls(): List<String> = withContext(dispatchers.io) {
        val discovered = mutableListOf<String>()
        discovered.addAll(IPTV_ORG_GZ_URLS)
        try {
            val request = Request.Builder()
                .url("https://raw.githubusercontent.com/iptv-org/epg/master/sites/README.md")
                .header("User-Agent", "StreamVerseTV/1.0")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val regex = Regex("""https?://iptv-org\.github\.io/epg/guide/\w+\.xml\.gz""")
            val extra = regex.findAll(body).map { it.value }.distinct()
                .filter { it !in IPTV_ORG_GZ_URLS }
                .take(MAX_EPG_SOURCES - IPTV_ORG_GZ_URLS.size)
            discovered.addAll(extra)
            response.close()
        } catch (_: Exception) {}
        discovered.distinct()
    }

    private fun downloadGzIfNeeded(url: String): java.io.File? {
        val filename = url.substringAfterLast('/')
        val file = java.io.File(epgDir, filename)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < EPG_MAX_AGE_MS) {
            return file
        }
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "StreamVerseTV/1.0")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body ?: return null
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
            response.close()
            epgDir.listFiles()
                ?.filter { it.name.endsWith(".gz") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_EPG_FILES)
                ?.forEach { it.delete() }
            file
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for $url: ${e.message}")
            null
        }
    }

    private fun parseXmltvGz(
        file: java.io.File,
        tvgIdMap: Map<String, String>,
    ): Map<String, List<EpgEntry>> {
        val programmesByChannel = mutableMapOf<String, MutableList<Programme>>()
        val channelNames = mutableMapOf<String, String>()
        var totalEntries = 0

        try {
            val fis = FileInputStream(file)
            val gis = if (file.name.endsWith(".gz")) GZIPInputStream(fis) else fis
            val reader = InputStreamReader(gis, "UTF-8")

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(reader)

            var currentChannelId: String? = null
            var currentChannelName: String? = null
            var currentTitle: String? = null
            var currentDesc: String? = null
            var currentCategory: String? = null
            var currentStart: Long = 0L
            var currentEnd: Long = 0L
            var inChannel = false
            var inProgramme = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (totalEntries >= MAX_ENTRIES_PER_FILE) break
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "channel" -> {
                                inChannel = true
                                currentChannelId = parser.getAttributeValue(null, "id")
                            }
                            "display-name" -> if (inChannel && currentChannelName == null) {
                                currentChannelName = parser.nextText()
                            }
                            "programme" -> {
                                inProgramme = true
                                currentChannelId = parser.getAttributeValue(null, "channel")
                                currentStart = parseXmltvTime(parser.getAttributeValue(null, "start"))
                                currentEnd = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            }
                            "title" -> if (inProgramme) currentTitle = parser.nextText()
                            "desc" -> if (inProgramme) currentDesc = parser.nextText()
                            "category" -> if (inProgramme && currentCategory == null) {
                                currentCategory = parser.nextText()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "channel" -> {
                                if (currentChannelId != null && currentChannelName != null) {
                                    channelNames[currentChannelId] = currentChannelName!!
                                }
                                inChannel = false
                                currentChannelId = null
                                currentChannelName = null
                            }
                            "programme" -> {
                                if (currentChannelId != null && currentTitle != null) {
                                    totalEntries++
                                    val prog = Programme(
                                        title = currentTitle!!,
                                        synopsis = currentDesc,
                                        category = currentCategory,
                                        startTimeMillis = currentStart,
                                        endTimeMillis = if (currentEnd > 0L) currentEnd else currentStart + 3600_000L,
                                        isLive = false,
                                    )
                                    programmesByChannel
                                        .getOrPut(currentChannelId!!) { mutableListOf() }
                                        .add(prog)
                                }
                                inProgramme = false
                                currentTitle = null
                                currentDesc = null
                                currentCategory = null
                                currentStart = 0L
                                currentEnd = 0L
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            reader.close()
            gis.close()
            fis.close()
            if (totalEntries >= MAX_ENTRIES_PER_FILE) {
                Log.w(TAG, "${file.name}: capped at $MAX_ENTRIES_PER_FILE entries")
            }
        } catch (e: Exception) {
            Log.w(TAG, "XMLTV parse error for ${file.name}: ${e.message}")
        }

        val result = mutableMapOf<String, MutableList<EpgEntry>>()
        for ((xmlId, progs) in programmesByChannel) {
            val targetId = resolveXmltvChannel(xmlId, tvgIdMap, channelNames) ?: continue
            val sorted = progs.sortedBy { it.startTimeMillis }
            val now = System.currentTimeMillis()
            val entries = sorted.mapIndexed { _, prog ->
                EpgEntry(
                    programme = prog,
                    channelIds = listOf(targetId),
                    isNow = prog.startTimeMillis <= now && now < prog.endTimeMillis,
                    isNext = false,
                )
            }.toMutableList()
            val nowIdx = entries.indexOfFirst { it.isNow }
            if (nowIdx in entries.indices && nowIdx + 1 in entries.indices) {
                entries[nowIdx + 1] = entries[nowIdx + 1].copy(isNext = true)
            }
            result.getOrPut(targetId) { mutableListOf() }.addAll(entries)
        }
        return result
    }

    private fun parseXmltvTime(s: String?): Long {
        if (s.isNullOrBlank() || s.length < 12) return 0L
        return try {
            val year = s.substring(0, 4).toInt()
            val month = s.substring(4, 6).toInt() - 1
            val day = s.substring(6, 8).toInt()
            val hour = s.substring(8, 10).toInt()
            val minute = s.substring(10, 12).toInt()
            Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }.timeInMillis
        } catch (_: Exception) { 0L }
    }

    private fun dlhdScheduleReachable(): Boolean {
        return try {
            val req = Request.Builder().url(DLHD_SCHEDULE_URL)
                .header("User-Agent", "StreamVerseTV/1.0")
                .head()
                .build()
            val resp = httpClient.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (_: Exception) { false }
    }

    private suspend fun loadDlhdScrapedSchedule(
        channels: List<Channel>,
    ): List<ScheduleDay> = withContext(dispatchers.io) {
        try {
            val doc = Jsoup.connect(DLHD_SCHEDULE_URL)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(20000)
                .get()

            val days = mutableListOf<ScheduleDay>()
            val dayElements = doc.select(".schedule__day, .day-schedule, [data-date]")
            val cal = Calendar.getInstance()
            val today = String.format(
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )

            if (dayElements.isNotEmpty()) {
                for (dayEl in dayElements) {
                    val date = dayEl.attr("data-date").takeIf { it.isNotBlank() }
                        ?: dayEl.select(".date, h3, h2").text().takeIf { it.isNotBlank() }
                        ?: today
                    val eventsByCat = mutableMapOf<String, MutableList<ScheduleEvent>>()
                    val eventEls = dayEl.select(".schedule__event, .event, tr")
                    for (evtEl in eventEls) {
                        val time = evtEl.select(".time, .event-time, td:first-child").text().trim()
                        val title = evtEl.select(".title, .event-title, td:nth-child(2)").text().trim()
                        val category = evtEl.select(".category, .event-category, td:nth-child(3)").text().trim()
                            .takeIf { it.isNotBlank() } ?: "Sports"
                        val channelLinks = evtEl.select("a[href*=watch], a[href*=channel]")
                        val channelIds = channelLinks.mapNotNull {
                            it.attr("href").substringAfter("id=").substringBefore("&")
                                .takeIf { id -> id.isNotBlank() && id.all { c -> c.isDigit() } }
                        }
                        if (title.isNotBlank()) {
                            eventsByCat.getOrPut(category) { mutableListOf() }.add(
                                ScheduleEvent(
                                    time = time.ifBlank { "00:00" },
                                    title = title,
                                    category = category,
                                    channelIds = channelIds.map { "dlhd_$it" },
                                    date = date,
                                )
                            )
                        }
                    }
                    if (eventsByCat.isNotEmpty()) {
                        days.add(ScheduleDay(date = date, events = eventsByCat))
                    }
                }
            }

            if (days.isEmpty()) {
                val fallback = dlhdClient.fetchCategoriesFromSchedule("").getOrDefault(emptyList())
                val eventsByCat = mutableMapOf<String, MutableList<ScheduleEvent>>()
                for (cat in fallback) {
                    eventsByCat.getOrPut(cat) { mutableListOf() }.add(
                        ScheduleEvent(
                            time = String.format("%02d:00", (8..22).random()),
                            title = "Live $cat",
                            category = cat,
                            channelIds = emptyList(),
                            date = today,
                        )
                    )
                }
                if (eventsByCat.isNotEmpty()) {
                    days.add(ScheduleDay(date = today, events = eventsByCat))
                }
            }
            days
        } catch (e: Exception) {
            Log.w(TAG, "DLHD schedule scrape failed: ${e.message}")
            emptyList()
        }
    }

    private fun mapDlhdScheduleToEpg(
        days: List<ScheduleDay>,
        channels: List<Channel>,
    ): Map<String, List<EpgEntry>> {
        val result = mutableMapOf<String, MutableList<EpgEntry>>()
        val dlhdChannels = channels.filter { it.sources.keys.any { SourceType.canonicalOf(it) == SourceType.SPORTS_EVENTS } }
        val idToChannel = dlhdChannels.associateBy { it.id }

        for (day in days) {
            for ((category, events) in day.events) {
                for (evt in events) {
                    val prog = Programme(
                        title = evt.title,
                        synopsis = "${evt.category} programming",
                        category = evt.category,
                        startTimeMillis = parseTimeToMillis(evt.time),
                        endTimeMillis = parseTimeToMillis(evt.time) + 3600_000L,
                        isLive = false,
                    )
                    for (chId in evt.channelIds) {
                        val ch = idToChannel[chId]
                        if (ch != null) {
                            val now = System.currentTimeMillis()
                            val isNow = prog.startTimeMillis <= now && now < prog.endTimeMillis
                            result.getOrPut(ch.id) { mutableListOf() }.add(
                                EpgEntry(
                                    programme = prog,
                                    channelIds = listOf(ch.id),
                                    isNow = isNow,
                                    isNext = false,
                                )
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    private fun parseTimeToMillis(time: String): Long {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }.timeInMillis
    }

    fun generateCategoryEpg(channel: Channel, hours: Int = 6): List<EpgEntry> {
        val now = System.currentTimeMillis()
        val entries = mutableListOf<EpgEntry>()
        var t = now - (0L..120 * 60_000L).random()
        for (i in 0 until (hours * 2)) {
            val dur = (30 * 60_000L..180 * 60_000L).random()
            val cat = channel.category ?: "General"
            val titles = categoryProgrammeTitles[cat] ?: categoryProgrammeTitles["General"]!!
            val title = titles[kotlin.random.Random.nextInt(titles.size)]
            val synopsis = categorySynopses[cat] ?: categorySynopses["General"]!!
            val prog = Programme(
                title = title,
                synopsis = synopsis,
                category = cat,
                startTimeMillis = t,
                endTimeMillis = t + dur,
                isLive = i == 0,
            )
            val isNow = t <= now && now < t + dur
            entries.add(
                EpgEntry(
                    programme = prog,
                    channelIds = listOf(channel.id),
                    isNow = isNow,
                    isNext = !isNow && i == 1,
                )
            )
            t += dur
        }
        return entries
    }

    private val categoryProgrammeTitles = mapOf(
        "News" to listOf("World News Tonight", "The Global Report", "News at Ten", "Morning Briefing", "Prime News", "International Desk", "Politics Unpacked", "Business Today"),
        "Sports" to listOf("Live Sport Coverage", "Sports Centre", "Match of the Day", "The Sports Desk", "Game Day Live", "Championship Highlights", "Extreme Sports Showcase"),
        "Movies" to listOf("Blockbuster Movie", "Cinema Classics", "Action Theatre", "Drama Hour", "Thriller Night", "Comedy Gold", "Sci-Fi Theatre"),
        "Entertainment" to listOf("Tonight's Entertainment", "The Variety Show", "Entertainment Now", "Celebrity Spotlight", "Red Carpet Coverage", "The Talk Show"),
        "Kids" to listOf("Cartoon Hour", "Kids Club", "Adventure Time", "Educational Fun", "Animated Tales", "Junior Science"),
        "Documentary" to listOf("Nature's Wonders", "History Revealed", "Science Frontiers", "Our Planet", "Ancient Civilisations", "Space Exploration"),
        "Music" to listOf("Live Concert", "Music Videos Now", "The Countdown Show", "Artist Spotlight", "Classical Hour", "Jazz Sessions"),
        "Lifestyle" to listOf("Home & Living", "Fashion Forward", "Culinary Journey", "Design Masters", "Wellness Hour", "Travel Diaries"),
        "Religious" to listOf("Faith Today", "Spiritual Hour", "Worship Service", "Biblical Teachings", "Interfaith Dialogue"),
        "Science" to listOf("Science Now", "Tech Revolution", "Future Frontiers", "Innovation Lab", "Digital World"),
        "Business" to listOf("Business Report", "Market Watch", "The Money Show", "Entrepreneur Hour", "Global Trade"),
        "Comedy" to listOf("Stand-up Special", "Comedy Club", "Funny Videos", "Late Night Comedy", "Sketch Show"),
        "General" to listOf("Live Programming", "Today's Selection", "Channel Highlights", "Now Showing", "Primetime Lineup"),
    )

    private val categorySynopses = mapOf(
        "News" to "Comprehensive coverage of the day's top stories",
        "Sports" to "Live action from the world's biggest sporting events",
        "Movies" to "A captivating cinematic experience",
        "Documentary" to "Exploring the wonders of our world",
        "General" to "Tune in for great live television",
    )

    private fun resolveXmltvChannel(
        xmlId: String,
        tvgIdMap: Map<String, String>,
        channelNames: Map<String, String>,
    ): String? {
        tvgIdMap[xmlId]?.let { return it }
        val xmlName = channelNames[xmlId]?.lowercase() ?: return null
        for ((_, svId) in tvgIdMap) {
            if (xmlName.contains(svId.lowercase()) || svId.lowercase().contains(xmlName)) {
                return svId
            }
        }
        return null
    }
}
