package com.streamverse.merge

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit

// ── Domain Models ──────────────────────────────────────────────────────────

enum class SourceType {
    BROADCASTER, FREE_CHANNEL, YOUTUBE_TV, SPORTS_EVENTS, WORLD_TV, GLOBAL_INDEX, RADIO,
}

enum class Quality { SD, HD, FHD, _4K }

data class SourceInfo(
    val type: SourceType,
    val referenceId: String,
    val streamUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drmKeyId: String? = null,
    val drmKey: String? = null,
    val latencyMs: Long = -1,
    val available: Boolean = true,
    val lastCheckedMs: Long = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
)

data class Channel(
    val id: String,
    val logicalId: String = id,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val logoUrl: String? = null,
    val quality: Quality? = null,
    val category: String? = null,
    val language: String? = null,
    val country: String? = null,
    val description: String? = null,
    val sources: Map<SourceType, SourceInfo>,
    val isFavorite: Boolean = false,
    val tvgId: String? = null,
)

// ── Hosted Index API Types ─────────────────────────────────────────────────

data class HostedChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val language: String?,
    val quality: String?,
    val source: String,
    val headers: Map<String, String>?,
    val drmKeyId: String?,
    val drmKey: String?,
)

data class IndexResponse(
    val version: Int,
    val channels: List<HostedChannel>,
)

data class MergedOutput(
    val version: Int = 1,
    val generatedAtMs: Long = System.currentTimeMillis(),
    val channels: List<Channel>,
)

// ── Source Type Mapping (matches ChannelRepository.HOSTED_SOURCE_TYPE) ──────

private val HOSTED_SOURCE_TYPE: Map<String, SourceType> = mapOf(
    "GLOBAL_INDEX" to SourceType.GLOBAL_INDEX,
    "IPTV" to SourceType.GLOBAL_INDEX,
    "FREE_TV" to SourceType.GLOBAL_INDEX,
    "FAST_TV" to SourceType.GLOBAL_INDEX,
    "PREMIUM" to SourceType.GLOBAL_INDEX,
    "FREE_CHANNEL" to SourceType.FREE_CHANNEL,
    "FREE_LIVE" to SourceType.FREE_CHANNEL,
    "RADIO" to SourceType.RADIO,
    "WORLD_TV" to SourceType.WORLD_TV,
    "STMIFY" to SourceType.WORLD_TV,
    "SPORTS_EVENTS" to SourceType.SPORTS_EVENTS,
    "DLHD" to SourceType.SPORTS_EVENTS,
    "YOUTUBE_TV" to SourceType.YOUTUBE_TV,
    "BROADCASTER" to SourceType.BROADCASTER,
    "VERIFIED" to SourceType.BROADCASTER,
    "INDEPENDENT" to SourceType.BROADCASTER,
)

// ── Source Priority (matches ProviderRegistry.buildPriority) ───────────────

private val SOURCE_PRIORITY: Map<SourceType, Int> = mapOf(
    SourceType.BROADCASTER to 0,
    SourceType.FREE_CHANNEL to 1,
    SourceType.YOUTUBE_TV to 2,
    SourceType.SPORTS_EVENTS to 3,
    SourceType.WORLD_TV to 4,
    SourceType.GLOBAL_INDEX to 5,
    SourceType.RADIO to 6,
)

// ── Alias Dictionary (matches :core AliasDictionary) ───────────────────────

private class AliasDict {
    private val aliasToCanonical = mutableMapOf<String, String>()

    init {
        add("National Geographic", "nat geo", "national geographic channel", "nat geo tv", "ngc")
        add("Al Jazeera English", "al jazeera", "aj english", "al jazeera international")
        add("Al Jazeera Arabic", "aj arabic", "الجزيرة")
        add("France 24", "france24", "france 24 english", "france 24 français", "f24")
        add("CGTN", "cgtn news", "china global television network", "cgtn english")
        add("BBC World News", "bbc world", "bbc world news tv", "bbcwn")
        add("BBC News", "bbc news 24", "bbc news channel")
        add("Sky News", "sky news uk", "sky news live")
        add("CNN International", "cnni", "cnn world", "cnn intl")
        add("EuroNews", "euronews", "euro news")
        add("RT News", "russia today", "rt", "rt international")
        add("Deutsche Welle", "dw", "dw news", "deutsche welle english", "dw english")
        add("NHK World", "nhk world japan", "nhk world tv", "nhk world premium")
        add("Arirang TV", "arirang", "arirang world")
        add("TRT World", "trt", "trt international")
        add("Fox News", "fox news", "fox news channel", "fnc")
        add("Fox Sports", "fox sports", "fs1", "fox sports 1")
        add("ESPN", "espn us", "espn america", "espn usa")
        add("Discovery Channel", "discovery", "discovery tv")
        add("Discovery Science", "discovery sci", "science channel")
        add("History Channel", "history", "history tv", "history")
        add("National Geographic Wild", "nat geo wild", "ngc wild", "nat geo wild")
        add("Cartoon Network", "cartoon network tv", "cn", "boomerang")
        add("Nickelodeon", "nick", "nick tv", "nickelodeon tv")
        add("MTV", "mtv tv", "music television")
        add("BBC One", "bbc1", "bbc 1")
        add("BBC Two", "bbc2", "bbc 2")
        add("BBC Three", "bbc3", "bbc 3")
        add("BBC Four", "bbc4", "bbc 4")
        add("ITV", "itv 1", "itv1", "itv network")
        add("Channel 4", "ch4", "c4", "channel four")
        add("Channel 5", "ch5", "c5", "five")
        add("ABC America", "abc us", "abc network", "abc tv")
        add("CBS", "cbs news", "cbs network", "cbs tv")
        add("NBC", "nbc tv", "nbc network", "national broadcasting company")
        add("PBS", "pbs tv", "public broadcasting service")
        add("CNN US", "cnn america", "cnn united states")
        add("MSNBC", "ms nbc", "msnbc tv")
        add("CNBC", "cnbc tv", "cnbc world")
        add("Bloomberg TV", "bloomberg", "bloomberg television", "bloomberg news")
        add("Al Arabiya", "al arabiya news", "العربية", "al arabiya tv")
        add("Sky Sports", "sky sports uk", "sky sports 1", "sky sports main event")
        add("beIN Sports", "bein", "bein sports", "bein sports")
        add("Eurosport", "eurosport 1", "eurosport international")
        add("TNT", "tnt tv", "turner network television", "tnt channel")
        add("TBS", "tbs tv", "turner broadcasting system", "superstation")
        add("USA Network", "usa tv", "usa network tv")
        add("Syfy", "sci fi", "sci-fi channel", "syfy channel")
        add("Comedy Central", "comedy central tv", "cc")
        add("HBO", "hbo tv", "home box office", "hbo channel")
        add("Disney Channel", "disney", "disney tv", "the disney channel")
        add("Disney Junior", "disney jr", "disney junior tv")
        add("Disney XD", "disney xd tv")
        add("Nick Jr", "nick jr tv", "nick junior")
        add("PBS Kids", "pbs kids tv", "pbs kids")
        add("CBeebies", "cbeebies tv", "bbc cbeebies")
        add("CBBC", "cbbc channel", "bbc cbbc")
        add("TV5Monde", "tv5 monde", "tv5", "tv5 monde info")
        add("Rai 1", "rai uno", "rai 1", "rai 1 hd")
        add("Rai 2", "rai due", "rai 2", "rai 2 hd")
        add("Rai 3", "rai tre", "rai 3", "rai 3 hd")
        add("TF1", "tf1 hd", "tf1 tv")
        add("France 2", "france 2 hd", "f2")
        add("France 3", "france 3 hd", "f3")
        add("France 5", "france 5 hd", "f5")
        add("M6", "m6 hd", "m6 tv", "metropole 6")
        add("Arte", "arte tv", "arte", "arte hd")
        add("ZDF", "zdf hd", "zdf tv", "zweites deutsches fernsehen")
        add("Das Erste", "ard", "ard tv", "erste", "das erste hd")
        add("RTL", "rtl tv", "rtl television", "rtl hd")
        add("ProSieben", "pro 7", "pro7", "prosieben hd")
        add("SAT.1", "sat 1", "sat1", "sat.1 hd")
        add("RTL 2", "rtl ii", "rtl2", "rtl 2 hd")
        add("VOX", "vox hd", "vox tv")
        add("Kabel Eins", "kabel 1", "kabel1", "kabel eins hd")
        add("N24", "welt", "welt tv", "n24 doku")
        add("n-tv", "n tv", "ntv", "n-tv hd")
        add("Tele 5", "tele5", "tele 5 hd")
        add("Super RTL", "toggo", "superrtl", "super rtl hd")
        add("ORF 1", "orf1", "orf 1 hd")
        add("ORF 2", "orf2", "orf 2 hd")
        add("SRF 1", "srf1", "sf 1", "srf 1 hd")
        add("SRF 2", "srf2", "sf 2", "srf 2 hd")
        add("RTS 1", "rts un", "rts 1 hd")
        add("RTS 2", "rts deux", "rts 2 hd")
        add("RSI LA 1", "la 1", "rsi la 1", "rsi la 1 hd")
        add("RSI LA 2", "la 2", "rsi la 2", "rsi la 2 hd")
    }

    private fun add(canonical: String, vararg aliases: String) {
        val canonLower = canonical.lowercase().trim()
        for (alias in aliases) {
            aliasToCanonical[alias.lowercase().trim()] = canonLower
        }
    }

    fun resolve(name: String): String? = aliasToCanonical[name.lowercase().trim()]
}

// ── Canonicalizer (matches :core ChannelCanonicalizer hashKey) ─────────────

private object Canonicalizer {
    private val RE_DIACRITICS = Regex("\\p{Mn}+")
    private val RE_RES_TAG = Regex("""[\(\[\{]\s*(?:\d{3,4}[pi]|4k|fhd|uhd|hdr|hd|sd|hevc|avc|h\.264|h\.265)\s*[\)\]\}]""", RegexOption.IGNORE_CASE)
    private val RE_BRACKETED = Regex("""\s*\[.*?\]\s*""")
    private val RE_PAREN = Regex("""\s*\(.*?\)\s*""")
    private val RE_PUNCTUATION = Regex("""[\s\-–—_/&|,.:;!'"()\[\]{}«»„"“”‘’]+""")
    private val RE_MULTI_SPACE = Regex("\\s{2,}")
    private val RE_NON_ALNUM = Regex("[^a-z0-9]")
    private val RE_BRANDING = listOf(
        Regex("""\bhd\b""", RegexOption.IGNORE_CASE),
        Regex("""\btv\b""", RegexOption.IGNORE_CASE),
        Regex("""\bworld\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnetwork\b""", RegexOption.IGNORE_CASE),
        Regex("""\bchannel\b""", RegexOption.IGNORE_CASE),
        Regex("""\blive\b""", RegexOption.IGNORE_CASE),
        Regex("""\b24[\/]?7\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnews\b""", RegexOption.IGNORE_CASE),
    )
    private val RE_QUALITY_SUFFIX = Regex("""(?:\s*[-–—]\s*)?(?:fhd|uhd|hdr|hd|sd|4k|2160p|1080p|720p|480p|360p|hevc|avc)$""", RegexOption.IGNORE_CASE)
    private val RE_RES_PARENS = Regex("""\s*\(?\s*(?:\d{3,4}[pi]|4k|fhd|uhd|hdr|hd|sd)\s*\)?\s*$""", RegexOption.IGNORE_CASE)

    fun hashKey(name: String, aliasDict: AliasDict): String {
        var s = name.trim()
        if (s.isEmpty()) return ""
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = s.replace(RE_DIACRITICS, "")
        s = s.lowercase().trim()
        s = s.replace(RE_RES_TAG, " ")
        s = s.replace(RE_BRACKETED, " ")
        s = s.replace(RE_PAREN, " ")
        s = s.replace(RE_PUNCTUATION, " ")
        aliasDict.resolve(s)?.let { s = it }
        for (re in RE_BRANDING) s = re.replace(s, " ")
        s = s.replace(RE_MULTI_SPACE, " ").trim()
        s = s.replace(RE_QUALITY_SUFFIX, "")
        s = s.replace(RE_RES_PARENS, "")
        s = s.replace(RE_MULTI_SPACE, " ").trim()
        return s.replace(RE_NON_ALNUM, "")
    }
}

// ── Category Normalizer (matches :core CategoryNormalizer) ─────────────────

private object CatNorm {
    const val GENERAL = "General"
    const val RADIO = "Radio"

    private val CAT_ALIASES = mapOf(
        "news" to "News", "sports" to "Sports", "movie" to "Movies", "movies" to "Movies",
        "film" to "Movies", "kids" to "Kids", "children" to "Kids", "music" to "Music",
        "documentary" to "Documentary", "docu" to "Documentary", "religious" to "Religious",
        "religion" to "Religious", "lifestyle" to "Lifestyle", "comedy" to "Comedy",
        "science" to "Science", "entertainment" to "Entertainment", "business" to "Business",
        "education" to "Education", "travel" to "Travel", "nature" to "Nature",
        "weather" to "Weather", "shopping" to "Shopping", "animation" to "Animation",
        "anime" to "Animation", "series" to "Entertainment", "drama" to "Entertainment",
        "reality" to "Entertainment", "talk" to "Entertainment", "variety" to "Entertainment",
        "general" to "General", "international" to "International", "local" to "Local",
        "public" to "General",
    )

    fun normalize(raw: String?): String? {
        if (raw == null || raw.isBlank()) return null
        val lower = raw.lowercase().trim()
        return CAT_ALIASES[lower] ?: raw.trim().replaceFirstChar { it.uppercaseChar() }
    }
}

// ── Merge Engine (matches IncrementalMergeState) ───────────────────────────

private class MergeEngine {
    private val byHashKey = mutableMapOf<String, MutableSet<String>>()
    private val byExactName = mutableMapOf<String, String>()
    private val byTvgId = mutableMapOf<String, String>()
    private val byId = linkedMapOf<String, Channel>()
    private val aliasDict = AliasDict()
    private var addedCount = 0
    private var updatedCount = 0

    fun processItems(items: List<SourceItem>, sourceType: SourceType) {
        var localAdded = 0
        var localUpdated = 0
        for (item in items) {
            val norm = item.name.trim().lowercase()
            val hk = Canonicalizer.hashKey(item.name, aliasDict)
            val existing = byExactName[norm]?.let { byId[it] }
                ?: byHashKey[hk]?.firstOrNull()?.let { byId[it] }
                ?: item.tvgId?.trim()?.lowercase()?.let { tv -> byTvgId[tv]?.let { byId[it] } }

            val info = SourceInfo(
                type = sourceType, referenceId = item.id,
                streamUrl = item.streamUrl, headers = item.headers,
                drmKeyId = item.drmKeyId, drmKey = item.drmKey,
            )

            if (existing != null) {
                if (sourceType !in existing.sources) {
                    val updated = existing.copy(
                        sources = existing.sources + (sourceType to info),
                        country = existing.country ?: item.country,
                        language = existing.language ?: item.language,
                        logoUrl = existing.logoUrl ?: item.logoUrl,
                        category = if (existing.category == null || existing.category == CatNorm.GENERAL)
                            CatNorm.normalize(item.category)
                        else existing.category,
                    )
                    byId[updated.id] = updated
                    updateIndexes(updated)
                    localUpdated++
                }
            } else {
                val displayName = stripResolution(item.name)
                val chId = "${sourceType.name.lowercase().substringBefore("_")}_${item.id}"
                val newCh = Channel(
                    id = chId,
                    displayName = displayName,
                    logoUrl = item.logoUrl,
                    quality = qualityFrom(item.quality),
                    category = CatNorm.normalize(item.category),
                    language = item.language,
                    country = item.country,
                    sources = mapOf(sourceType to info),
                )
                addToIndexes(newCh)
                localAdded++
            }
        }
        addedCount += localAdded
        updatedCount += localUpdated
    }

    fun result(): List<Channel> = byId.values.toList()

    fun stats(): String = "added=$addedCount updated=$updatedCount total=${byId.size}"

    private fun addToIndexes(ch: Channel) {
        byId[ch.id] = ch
        val hk = Canonicalizer.hashKey(ch.displayName, aliasDict)
        byHashKey.getOrPut(hk) { mutableSetOf() }.add(ch.id)
        byExactName[ch.displayName.trim().lowercase()] = ch.id
        if (!ch.tvgId.isNullOrBlank()) byTvgId[ch.tvgId.trim().lowercase()] = ch.id
    }

    private fun updateIndexes(ch: Channel) {
        val old = byId[ch.id] ?: return
        val oldHk = Canonicalizer.hashKey(old.displayName, aliasDict)
        byHashKey[oldHk]?.remove(ch.id)
        byExactName.remove(old.displayName.trim().lowercase())
        if (!old.tvgId.isNullOrBlank()) byTvgId.remove(old.tvgId.trim().lowercase())
        addToIndexes(ch)
    }

    private fun stripResolution(raw: String): String {
        if (raw.isBlank()) return raw
        val RES_PAREN = Regex("""\s*[\(\[]\s*(?:\d{3,4}[piPI]|4[Kk]|FHD|UHD|HDR|HD|SD)\s*[\)\]]\s*""")
        val DECORATIVE = Regex("""[\p{So}️]+""")
        val STATUS = Regex("""\s*[\(\[]\s*(?:not\s*24[/\- ]?7|geo[\- ]?blocked)\s*[\)\]]\s*""", RegexOption.IGNORE_CASE)
        return raw
            .replace(STATUS, " ")
            .replace(RES_PAREN, " ")
            .replace(DECORATIVE, " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun qualityFrom(q: String?) = when (q) {
        "4K" -> Quality._4K; "FHD" -> Quality.FHD; "HD" -> Quality.HD; "SD" -> Quality.SD; else -> null
    }
}

// ── Data Source Item ───────────────────────────────────────────────────────

private data class SourceItem(
    val id: String,
    val name: String,
    val streamUrl: String?,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val language: String?,
    val quality: String?,
    val tvgId: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drmKeyId: String? = null,
    val drmKey: String? = null,
)

// ── Main ───────────────────────────────────────────────────────────────────

fun main(args: Array<String>) {
    val gson = Gson()
    val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    val baseUrl = System.getenv("DATA_BASE_URL") ?: "https://Goddiewayne.github.io/streamverse-data"
    val outDir = args.getOrElse(0) { System.getenv("OUTPUT_DIR") ?: "." }

    println("Merging channels from $baseUrl ...")

    // ── fetch ───────────────────────────────────────────────────────────────
    val channelsUrl = "$baseUrl/channels.json"
    val req = Request.Builder().url(channelsUrl).get().build()
    val resp = client.newCall(req).execute()
    if (!resp.isSuccessful) {
        System.err.println("ERROR: HTTP ${resp.code} fetching $channelsUrl")
        kotlin.system.exitProcess(1)
    }
    val body = resp.body?.string() ?: run {
        System.err.println("ERROR: empty response body")
        kotlin.system.exitProcess(1)
    }
    val index = gson.fromJson(body, IndexResponse::class.java)
    println("Fetched ${index.channels.size} raw channels")

    // ── group by source type ────────────────────────────────────────────────
    val byType = mutableMapOf<SourceType, MutableList<SourceItem>>()
    var skipped = 0
    for (ch in index.channels) {
        val st = HOSTED_SOURCE_TYPE[ch.source]
        if (st == null) { skipped++; continue }
        val list = byType.getOrPut(st) { mutableListOf() }
        list.add(SourceItem(
            id = ch.id, name = ch.name, streamUrl = ch.streamUrl,
            logoUrl = ch.logoUrl, category = ch.category,
            country = ch.country, language = ch.language,
            quality = ch.quality, headers = ch.headers ?: emptyMap(),
            drmKeyId = ch.drmKeyId, drmKey = ch.drmKey,
        ))
    }
    println("Grouped into ${byType.size} source types ($skipped unknown source tags)")

    // ── merge in priority order ─────────────────────────────────────────────
    val engine = MergeEngine()
    val sortedTypes = SOURCE_PRIORITY.entries.sortedBy { it.value }.map { it.key }
    for (st in sortedTypes) {
        val items = byType[st] ?: continue
        System.err.println("  ${st.name}: ${items.size} channels")
        engine.processItems(items, st)
    }

    val channels = engine.result()
    println("Merge complete: ${engine.stats()}")

    // ── sort by source rank ─────────────────────────────────────────────────
    val ranked = channels.sortedBy { ch ->
        ch.sources.keys.minOfOrNull { SOURCE_PRIORITY[it] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
    }
    println("Sorted: ${ranked.size} channels")

    // ── write merged.json ───────────────────────────────────────────────────
    val output = MergedOutput(channels = ranked)
    val json = gson.toJson(output)
    val outFile = File(outDir, "merged.json")
    outFile.writeText(json)
    println("Wrote ${outFile.absolutePath} (${json.length} bytes, ${ranked.size} channels)")
}
