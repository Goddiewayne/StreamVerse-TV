package com.streamverse.pipeline.ingester

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.streamverse.pipeline.model.RawChannel
import com.streamverse.pipeline.model.SourceType
import com.streamverse.pipeline.telemetry.StructuredLogger
import okhttp3.OkHttpClient
import okhttp3.Request

data class HostedChannel(
    val id: String,
    val name: String,
    val streamUrl: String?,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val language: String?,
    val quality: String?,
    val source: String?,
    val tvgId: String? = null,
    val headers: Map<String, String>?,
    @SerializedName("drm_key_id") val drmKeyId: String?,
    @SerializedName("drm_key") val drmKey: String?,
)

data class IndexResponse(
    val version: Int?,
    val source: String?,
    val total: Int?,
    val channels: List<HostedChannel>?,
)

class HostedIndexIngester(
    private val client: OkHttpClient,
    private val logger: StructuredLogger,
    private val gson: Gson = Gson(),
) : SourceIngester {

    private val sourceTypeMap: Map<String, SourceType> = mapOf(
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

    private val indexFiles: Map<String, String> = mapOf(
        "iptv_index.json" to "GLOBAL_INDEX",
        "free_tv_index.json" to "GLOBAL_INDEX",
        "fast_tv_index.json" to "GLOBAL_INDEX",
        "premium_index.json" to "GLOBAL_INDEX",
        "free_live_index.json" to "FREE_CHANNEL",
        "radio_index.json" to "RADIO",
        "stmify_index.json" to "WORLD_TV",
        "dlhd_index.json" to "SPORTS_EVENTS",
        "youtube_tv_index.json" to "YOUTUBE_TV",
        "independent_index.json" to "BROADCASTER",
        "broadcaster_index.json" to "BROADCASTER",
    )

    override fun name() = "HostedIndex"

    override fun ingest(): List<RawChannel> {
        logger.info("HostedIndexIngester", "Fetching hosted index files")
        val channels = mutableListOf<RawChannel>()

        for ((file, defaultSource) in indexFiles) {
            runCatching {
                val body = fetchUrl(file)
                val response = gson.fromJson(body, IndexResponse::class.java)
                val rawList = response?.channels.orEmpty()
                if (rawList.isEmpty()) {
                    logger.warn("HostedIndexIngester", "$file: 0 channels")
                    return@runCatching
                }
                for (ch in rawList) {
                    val st = sourceTypeMap[ch.source ?: defaultSource]
                    if (st == null) continue
                    channels.add(RawChannel(
                        id = ch.id,
                        displayName = ch.name,
                        streamUrl = ch.streamUrl ?: continue,
                        logoUrl = ch.logoUrl,
                        category = ch.category,
                        country = ch.country,
                        language = ch.language,
                        quality = ch.quality,
                        tvgId = ch.tvgId,
                        source = st,
                        headers = ch.headers ?: emptyMap(),
                        drmKeyId = ch.drmKeyId,
                        drmKey = ch.drmKey,
                    ))
                }
                logger.info("HostedIndexIngester", "$file: ${rawList.size} channels → ${rawList.size}")
            }.onFailure { logger.warn("HostedIndexIngester", "$file fetch failed: ${it.message}") }
        }

        logger.info("HostedIndexIngester", "Total raw channel entries: ${channels.size}")
        return channels
    }

    private fun fetchUrl(path: String): String {
        val baseUrl = System.getenv("DATA_BASE_URL") ?: "https://Goddiewayne.github.io/streamverse-data"
        val url = "$baseUrl/$path"
        val req = Request.Builder().url(url).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} fetching $url")
        return resp.body?.string() ?: throw RuntimeException("Empty response from $url")
    }
}
