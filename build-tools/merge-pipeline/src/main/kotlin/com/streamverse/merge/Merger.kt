package com.streamverse.merge

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
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

// ── Stmify (WordPress REST API) DTOs ────────────────────────────────────────

private data class StmifyTitle(val rendered: String)
private data class StmifyContent(val rendered: String)
private data class StmifyExcerpt(val rendered: String)
private data class StmifyMediaSize(@SerializedName("source_url") val sourceUrl: String)
private data class StmifyMediaDetails(val sizes: Map<String, StmifyMediaSize>?)
private data class StmifyFeaturedMedia(
    @SerializedName("source_url") val sourceUrl: String?,
    @SerializedName("media_details") val mediaDetails: StmifyMediaDetails?,
)
private data class StmifyTerm(val name: String)
private data class StmifyEmbedded(
    @SerializedName("wp:featuredmedia") val featuredMedia: List<StmifyFeaturedMedia>?,
    @SerializedName("wp:term") val terms: List<List<StmifyTerm>>?,
)
private data class StmifyPostDto(
    val id: Int,
    val slug: String,
    val title: StmifyTitle?,
    val content: StmifyContent?,
    val excerpt: StmifyExcerpt?,
    @SerializedName("_embedded") val embedded: StmifyEmbedded?,
    @SerializedName("class_list") val classList: List<String>? = null,
)

// ── PrimeVideo DTOs ────────────────────────────────────────────────────────

private data class PrimeVideoChannelEntry(
    val logo: String?,
    val tagline: String?,
    val hd: Boolean = false,
    val description: String?,
    val country: String?,
)

// ── Stream DB (pre-resolved stream URLs) ────────────────────────────────────

private data class StreamDbEntry(
    val url: String? = null,
    val k1: String? = null,
    val k2: String? = null,
    val quality: String? = null,
)



// ── WORLD_TV Fetch Functions ───────────────────────────────────────────────

// Use curl as a subprocess because java.net.HttpURLConnection and OkHttp both
// trigger Cloudflare WAF 403 on some networks, while curl does not.
// curl is available on all CI platforms (Windows: curl.exe, Linux/macOS: curl).
private fun curlFetch(url: String, accept: String = "application/json"): String {
    val curlCmd = if (System.getProperty("os.name").lowercase().contains("windows")) "curl.exe" else "curl"
    val pb = ProcessBuilder(
        curlCmd, "-s", "-L", "--max-time", "30",
        "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "-H", "Accept: $accept",
        url
    )
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val output = proc.inputStream.bufferedReader().readText()
    val exitCode = proc.waitFor()
    if (exitCode != 0) throw RuntimeException("curl exit code $exitCode")
    return output
}

/**
 * Fetches the country-specific stream DB (or global if country is empty).
 * Merged into the mutable map passed in [streamDb].
 */
private fun fetchStreamDbForCountry(gson: Gson, streamDb: MutableMap<String, StreamDbEntry>, country: String = "") {
    val url = if (country.isEmpty()) "https://cdn.stmify.com/embed-free/fetch_streams.php"
        else "https://cdn.stmify.com/embed-free/fetch_streams.php?country=$country"
    try {
        val curlCmd = if (System.getProperty("os.name").lowercase().contains("windows")) "curl.exe" else "curl"
        val pb = ProcessBuilder(curlCmd, "-s", "-L", "--max-time", "30",
            "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "-H", "Accept: application/json,text/html",
            "-H", "X-Requested-With: XMLHttpRequest", url)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val body = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) throw RuntimeException("curl exit code $exitCode")
        if (body.startsWith("{\"error\"")) throw RuntimeException(body)
        val root = JsonParser.parseString(body).asJsonObject
        for ((key, value) in root.entrySet()) {
            if (key !in streamDb) {
                val obj = value.asJsonObject
                streamDb[key] = StreamDbEntry(
                    url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString,
                    k1 = obj.get("k1")?.takeIf { it.isJsonPrimitive }?.asString,
                    k2 = obj.get("k2")?.takeIf { it.isJsonPrimitive }?.asString,
                    quality = obj.get("quality")?.takeIf { it.isJsonPrimitive }?.asString,
                )
            }
        }
    } catch (e: Exception) {
        System.err.println("  WARN: Stream DB fetch ($country) failed: ${e.message}")
    }
}

private fun fetchStmifyChannels(gson: Gson, streamDb: MutableMap<String, StreamDbEntry>): List<SourceItem> {
    val baseUrl = "https://stmify.com"
    val apiBase = "$baseUrl/wp-json/wp/v2"
    val allPosts = mutableListOf<StmifyPostDto>()

    // Fetch all posts from the WordPress API (including content field for embed parsing)
    for (page in 1..50) {
        val url = "$apiBase/live-tv?per_page=100&page=$page&_embed=wp:featuredmedia,wp:term"
        val dtos = try {
            val body = curlFetch(url)
            gson.fromJson<List<StmifyPostDto>>(body, object : TypeToken<List<StmifyPostDto>>() {}.type)
        } catch (e: Exception) {
            if (allPosts.isEmpty()) System.err.println("  WARN: Stmify page $page error: ${e.message}")
            break
        }
        if (dtos.isEmpty()) break
        allPosts.addAll(dtos)
        if (dtos.size < 100) break
    }

    if (allPosts.isEmpty()) {
        System.err.println("  WARN: Stmify API returned no data, skipping WORLD_TV")
        return emptyList()
    }

    // Extract unique country names from class_list and map to 2-letter codes
    val countryNames = allPosts.mapNotNull { post ->
        post.classList?.firstOrNull { it.startsWith("live_tv_country-") }?.removePrefix("live_tv_country-")
    }.distinct()

    val nameToCode = mutableMapOf<String, String>()
    val nameToSamplePost = allPosts.groupBy { post ->
        post.classList?.firstOrNull { it.startsWith("live_tv_country-") }?.removePrefix("live_tv_country-")
    }.mapValues { (_, posts) -> posts.first() }

    val unknown = mutableListOf<String>()
    for (cn in countryNames) {
        val code = COUNTRY_NAME_TO_CODE[cn]
        if (code != null) nameToCode[cn] = code else unknown.add(cn)
    }
    for (cn in unknown) {
        val rep = nameToSamplePost[cn] ?: continue
        val html = try { curlFetch("$baseUrl/live-tv/${rep.slug}/", "text/html") } catch (e: Exception) { null }
        if (html != null) {
            val m = EMBED_RE.find(html)
            if (m != null) nameToCode[cn] = m.groupValues[2]
        }
    }

    val codes = nameToCode.values.distinct()
    if (codes.isNotEmpty()) println("  Country codes: $codes")
    for (c in codes) {
        val before = streamDb.size
        fetchStreamDbForCountry(gson, streamDb, c)
        val added = streamDb.size - before
        if (added > 0) println("    $c: +$added stream entries")
    }

    // For channels still unmatched, fetch the HTML page to discover the embed slug
    val embedOverrides = mutableMapOf<String, String>()
    val unresolved = allPosts.filter { streamDb[streamDbKey(it.slug)]?.url == null }
    if (unresolved.isNotEmpty()) {
        println("  Resolving ${unresolved.size} unmatched channels via HTML pages ...")
        for ((i, post) in unresolved.withIndex()) {
            val html = try { curlFetch("$baseUrl/live-tv/${post.slug}/", "text/html") } catch (e: Exception) { null }
            if (html != null) {
                val m = EMBED_RE.find(html)
                if (m != null) {
                    embedOverrides[post.slug] = m.groupValues[1]
                    val cc = m.groupValues[2]
                    if (cc !in codes) {
                        fetchStreamDbForCountry(gson, streamDb, cc)
                    }
                }
            }
            if ((i + 1) % 50 == 0) println("    resolved ${i + 1}/${unresolved.size}")
        }
    }

    // Convert all posts to SourceItems
    return allPosts.map { post ->
        val streamKey = embedOverrides[post.slug]?.let { streamDbKey(it) } ?: streamDbKey(post.slug)
        val entry = streamDb[streamKey]
        val media = post.embedded?.featuredMedia?.firstOrNull()
        val imgUrl = media?.sourceUrl
            ?: media?.mediaDetails?.sizes?.entries
                ?.firstOrNull { (k, _) -> k.contains("medium") || k.contains("large") }
                ?.value?.sourceUrl
        val genres = post.embedded?.terms?.flatten().orEmpty().map { it.name }
        SourceItem(
            id = post.slug,
            name = post.title?.rendered?.trim().orEmpty(),
            streamUrl = entry?.url,
            logoUrl = imgUrl?.let { if (it.startsWith("http")) it else "$baseUrl/$it" },
            category = genres.firstOrNull(),
            country = null,
            language = null,
            quality = entry?.quality,
            tvgId = post.slug,
            drmKeyId = entry?.k1,
            drmKey = entry?.k2,
        )
    }
}

/** Country taxonomy name to 2-letter ISO code used in stream DB URL. */
private val COUNTRY_NAME_TO_CODE = mapOf(
    "united-arab-emirates" to "ae", "saudi-arabia" to "sa", "egypt" to "eg",
    "qatar" to "qa", "jordan" to "jo", "kuwait" to "kw", "bahrain" to "bh",
    "oman" to "om", "lebanon" to "lb", "iraq" to "iq", "syria" to "sy",
    "yemen" to "ye", "morocco" to "ma", "algeria" to "dz", "tunisia" to "tn",
    "libya" to "ly", "palestine" to "ps", "sudan" to "sd", "mauritania" to "mr",
    "united-kingdom" to "uk", "united-states" to "us", "canada" to "ca",
    "australia" to "au", "india" to "in", "pakistan" to "pk", "france" to "fr",
    "germany" to "de", "italy" to "it", "spain" to "es", "netherlands" to "nl",
    "belgium" to "be", "switzerland" to "ch", "austria" to "at", "sweden" to "se",
    "norway" to "no", "denmark" to "dk", "finland" to "fi", "ireland" to "ie",
    "portugal" to "pt", "greece" to "gr", "turkey" to "tr", "mexico" to "mx",
    "brazil" to "br", "argentina" to "ar", "malaysia" to "my", "singapore" to "sg",
    "philippines" to "ph", "indonesia" to "id", "thailand" to "th", "vietnam" to "vn",
    "new-zealand" to "nz", "south-africa" to "za", "nigeria" to "ng", "kenya" to "ke",
    "ghana" to "gh",
)

/** Stream DB key derived from a WordPress slug or embed slug. */
private fun streamDbKey(refId: String): String =
    refId.uppercase().replace("-", "_")

/** Regex matching the Stmify embed iframe URL, capturing embed slug and country. */
private val EMBED_RE = Regex("cdn\\.stmify\\.com/embed-free/v1/(.+?)-([a-z]{2})-[a-z0-9]")

private fun fetchPrimeVideoChannels(gson: Gson, streamDb: Map<String, StreamDbEntry>): List<SourceItem> {
    val url = "https://cdn.stmify.com/primevideo/template-parts/get-channels.php"
    try {
        val body = curlFetch(url)
        val map: Map<String, PrimeVideoChannelEntry> = gson.fromJson(
            body, object : TypeToken<Map<String, PrimeVideoChannelEntry>>() {}.type
        )
        return map.map { (key, entry) ->
            val streamEntry = streamDb[streamDbKey(key)]
            SourceItem(
                id = key,
                name = key.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                streamUrl = streamEntry?.url,
                logoUrl = entry.logo,
                category = entry.tagline?.split(",")?.firstOrNull()?.trim(),
                country = entry.country,
                language = null,
                quality = streamEntry?.quality,
                tvgId = key.lowercase().replace("_", "-"),
                drmKeyId = streamEntry?.k1,
                drmKey = streamEntry?.k2,
            )
        }
    } catch (e: Exception) {
        System.err.println("  WARN: PrimeVideo API error: ${e.message}, skipping")
        return emptyList()
    }
}

// ── Logo Re-hosting ──────────────────────────────────────────────────────────
// Download logos from Cloudflare-protected origins (stmify.com) and serve them
// from the same GitHub Pages site as merged.json, so Android emulators can load
// them without hitting Cloudflare WAF.

private fun rehostLogos(items: MutableList<SourceItem>, baseUrl: String, outDir: String) {
    val logosDir = File(outDir, "logos")
    logosDir.mkdirs()
    val toDownload = items.mapNotNull { it.logoUrl }.distinct()
        .filter { "stmify.com" in it || "stmify" in it }
    if (toDownload.isEmpty()) return
    val urlMap = mutableMapOf<String, String>()
    var ok = 0; var fail = 0
    for (url in toDownload) {
        val ext = url.substringAfterLast('.', "jpg").substringBefore("?")
        val name = url.hashCode().toUInt().toString(16)
        val filename = "logo_$name.$ext"
        val dest = File(logosDir, filename)
        if (dest.exists() && dest.length() > 0L) { urlMap[url] = "$baseUrl/logos/$filename"; ok++; continue }
        try {
            val curlCmd = if (System.getProperty("os.name").lowercase().contains("windows")) "curl.exe" else "curl"
            val pb = ProcessBuilder(curlCmd, "-s", "-L", "--max-time", "15",
                "-o", dest.absolutePath,
                "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "-H", "Accept: image/webp,image/*,*/*", url)
            pb.redirectErrorStream(true); val proc = pb.start()
            if (proc.waitFor() == 0 && dest.length() > 0L) {
                urlMap[url] = "$baseUrl/logos/$filename"; ok++
            } else { dest.delete(); fail++ }
        } catch (e: Exception) { fail++ }
    }
    if (ok > 0 || fail > 0) println("  Logo rehost: $ok OK, $fail failed")
    for (i in items.indices) {
        val old = items[i].logoUrl ?: continue
        val newUrl = urlMap[old] ?: continue
        items[i] = items[i].copy(logoUrl = newUrl)
    }
}

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

    // ── fetch WORLD_TV (Stmify + PrimeVideo) ────────────────────────────────
    // These are not in channels.json, so we fetch them directly from the APIs.
    // The CI runner can access stmify.com (unlike Android emulators behind Cloudflare).
    println("Fetching stream database (pre-resolved stream URLs) ...")
    val streamDb = mutableMapOf<String, StreamDbEntry>()
    fetchStreamDbForCountry(gson, streamDb) // global DB
    println("  Stream DB entries: ${streamDb.size}")

    println("Fetching WORLD_TV sources (Stmify + PrimeVideo) ...")
    val stmifyItems = fetchStmifyChannels(gson, streamDb)
    val primeItems = fetchPrimeVideoChannels(gson, streamDb)
    println("  Stmify: ${stmifyItems.size}, PrimeVideo: ${primeItems.size}")
    if (stmifyItems.isNotEmpty() || primeItems.isNotEmpty()) {
        val worldTvList = byType.getOrPut(SourceType.WORLD_TV) { mutableListOf() }
        worldTvList.addAll(stmifyItems)
        worldTvList.addAll(primeItems)
        rehostLogos(worldTvList, baseUrl, outDir)
    }

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
