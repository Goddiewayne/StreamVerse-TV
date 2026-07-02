package com.streamverse.pipeline.ingester

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.RawChannel
import com.streamverse.pipeline.model.SourceType
import com.streamverse.pipeline.telemetry.StructuredLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class StmifyIngester(
    private val config: PipelineConfig,
    private val client: OkHttpClient,
    private val logger: StructuredLogger,
    private val gson: Gson = Gson(),
) : SourceIngester {

    override fun name() = "Stmify+PrimeVideo"

    override fun ingest(): List<RawChannel> {
        val allItems = mutableListOf<RawChannel>()

        try {
            val streamDb = fetchStreamDb()
            logger.info("StmifyIngester", "Stream DB entries: ${streamDb.size}")

            val stmifyItems = fetchStmifyChannels(streamDb)
            logger.info("StmifyIngester", "Stmify channels: ${stmifyItems.size}")

            val primeItems = fetchPrimeVideoChannels(streamDb)
            logger.info("StmifyIngester", "PrimeVideo channels: ${primeItems.size}")

            allItems.addAll(stmifyItems)
            allItems.addAll(primeItems)
        } catch (e: Exception) {
            logger.error("StmifyIngester", "WORLD_TV ingestion failed: ${e.message}", e)
        }

        return allItems
    }

    private data class StreamDbEntry(
        val url: String? = null,
        val k1: String? = null,
        val k2: String? = null,
        val quality: String? = null,
    )

    private data class StmifyTitle(val rendered: String)
    private data class StmifyEmbedded(
        @SerializedName("wp:featuredmedia") val featuredMedia: List<StmifyFeaturedMedia>?,
        @SerializedName("wp:term") val terms: List<List<StmifyTerm>>?,
    )
    private data class StmifyFeaturedMedia(
        @SerializedName("source_url") val sourceUrl: String?,
        @SerializedName("media_details") val mediaDetails: StmifyMediaDetails?,
    )
    private data class StmifyMediaDetails(val sizes: Map<String, StmifyMediaSize>?)
    private data class StmifyMediaSize(@SerializedName("source_url") val sourceUrl: String)
    private data class StmifyTerm(val name: String)
    private data class StmifyPostDto(
        val id: Int,
        val slug: String,
        val title: StmifyTitle?,
        @SerializedName("_embedded") val embedded: StmifyEmbedded?,
        @SerializedName("class_list") val classList: List<String>?,
    )
    private data class PrimeVideoChannelEntry(
        val logo: String?, val tagline: String?, val hd: Boolean = false,
        val description: String?, val country: String?,
    )

    private data class SourceItem(
        val id: String, val name: String, val streamUrl: String?,
        val logoUrl: String?, val category: String?, val country: String?,
        val language: String?, val quality: String?, val tvgId: String?,
        val headers: Map<String, String>, val drmKeyId: String?, val drmKey: String?,
    )

    private fun curlFetch(url: String, accept: String = "application/json"): String {
        val curlCmd = if (System.getProperty("os.name").lowercase().contains("windows")) "curl.exe" else "curl"
        val pb = ProcessBuilder(
            curlCmd, "-s", "-L", "--max-time", "30",
            "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "-H", "Accept: $accept", url
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) throw RuntimeException("curl exit code $exitCode")
        return output
    }

    private val countryNameToCode: Map<String, String> = mapOf(
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
    private val EMBED_RE = Regex("cdn\\.stmify\\.com/embed-free/v1/(.+?)-([a-z]{2})-[a-z0-9]")

    private fun streamDbKey(refId: String): String = refId.uppercase().replace("-", "_")

    private fun fetchStreamDb(): MutableMap<String, StreamDbEntry> {
        val streamDb = mutableMapOf<String, StreamDbEntry>()
        val url = "https://cdn.stmify.com/embed-free/fetch_streams.php"
        try {
            val body = curlFetch(url)
            val root = JsonParser.parseString(body).asJsonObject
            for ((key, value) in root.entrySet()) {
                val obj = value.asJsonObject
                streamDb[key] = StreamDbEntry(
                    url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString,
                    k1 = obj.get("k1")?.takeIf { it.isJsonPrimitive }?.asString,
                    k2 = obj.get("k2")?.takeIf { it.isJsonPrimitive }?.asString,
                    quality = obj.get("quality")?.takeIf { it.isJsonPrimitive }?.asString,
                )
            }
        } catch (e: Exception) {
            logger.warn("StmifyIngester", "Stream DB fetch failed: ${e.message}")
        }
        return streamDb
    }

    private fun fetchStmifyChannels(streamDb: MutableMap<String, StreamDbEntry>): List<RawChannel> {
        val baseUrl = "https://stmify.com"
        val apiBase = "$baseUrl/wp-json/wp/v2"
        val allPosts = mutableListOf<StmifyPostDto>()

        for (page in 1..50) {
            val url = "$apiBase/live-tv?per_page=100&page=$page&_embed=wp:featuredmedia,wp:term"
            val dtos = try {
                val body = curlFetch(url)
                gson.fromJson<List<StmifyPostDto>>(body, object : TypeToken<List<StmifyPostDto>>() {}.type)
            } catch (e: Exception) {
                if (allPosts.isEmpty()) logger.warn("StmifyIngester", "Page $page error: ${e.message}")
                break
            }
            if (dtos.isEmpty()) break
            allPosts.addAll(dtos)
            if (dtos.size < 100) break
        }

        if (allPosts.isEmpty()) return emptyList()

        val countryNames = allPosts.mapNotNull { post ->
            post.classList?.firstOrNull { it.startsWith("live_tv_country-") }?.removePrefix("live_tv_country-")
        }.distinct()

        val nameToCode = mutableMapOf<String, String>()
        for (cn in countryNames) {
            countryNameToCode[cn]?.let { nameToCode[cn] = it }
        }

        for (cn in countryNames.filter { it !in nameToCode }) {
            val samplePost = allPosts.firstOrNull { post ->
                post.classList?.any { it == "live_tv_country-$cn" } == true
            } ?: continue
            try {
                val html = curlFetch("$baseUrl/live-tv/${samplePost.slug}/", "text/html")
                val m = EMBED_RE.find(html)
                if (m != null) nameToCode[cn] = m.groupValues[2]
            } catch (_: Exception) { }
        }

        val codes = nameToCode.values.distinct()
        if (codes.isNotEmpty()) logger.info("StmifyIngester", "Country codes: $codes")

        for (c in codes) {
            val before = streamDb.size
            try {
                val dbUrl = "https://cdn.stmify.com/embed-free/fetch_streams.php?country=$c"
                val body = curlFetch(dbUrl)
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
            } catch (_: Exception) { }
        }

        return allPosts.mapNotNull { post ->
            val entry = streamDb[streamDbKey(post.slug)]
            if (entry?.url == null) return@mapNotNull null
            val media = post.embedded?.featuredMedia?.firstOrNull()
            val imgUrl = media?.sourceUrl
                ?: media?.mediaDetails?.sizes?.entries
                    ?.firstOrNull { (k, _) -> k.contains("medium") || k.contains("large") }
                    ?.value?.sourceUrl
            RawChannel(
                id = post.slug,
                displayName = post.title?.rendered?.trim().orEmpty(),
                streamUrl = entry.url,
                logoUrl = if (imgUrl?.startsWith("http") == true) imgUrl else null,
                category = post.embedded?.terms?.flatten()?.firstOrNull()?.name,
                country = null,
                language = null,
                quality = entry.quality,
                tvgId = post.slug,
                source = SourceType.WORLD_TV,
                drmKeyId = entry.k1,
                drmKey = entry.k2,
            )
        }
    }

    private fun fetchPrimeVideoChannels(streamDb: Map<String, StreamDbEntry>): List<RawChannel> {
        val url = "https://cdn.stmify.com/primevideo/template-parts/get-channels.php"
        return try {
            val body = curlFetch(url)
            val map: Map<String, PrimeVideoChannelEntry> = gson.fromJson(
                body, object : TypeToken<Map<String, PrimeVideoChannelEntry>>() {}.type
            )
            map.mapNotNull { (key, entry) ->
                val streamEntry = streamDb[streamDbKey(key)] ?: return@mapNotNull null
                RawChannel(
                    id = key,
                    displayName = key.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                    streamUrl = streamEntry.url,
                    logoUrl = entry.logo,
                    category = entry.tagline?.split(",")?.firstOrNull()?.trim(),
                    country = entry.country,
                    language = null,
                    quality = streamEntry.quality,
                    tvgId = key.lowercase().replace("_", "-"),
                    source = SourceType.WORLD_TV,
                    drmKeyId = streamEntry.k1,
                    drmKey = streamEntry.k2,
                )
            }
        } catch (e: Exception) {
            logger.warn("StmifyIngester", "PrimeVideo API error: ${e.message}")
            emptyList()
        }
    }
}
