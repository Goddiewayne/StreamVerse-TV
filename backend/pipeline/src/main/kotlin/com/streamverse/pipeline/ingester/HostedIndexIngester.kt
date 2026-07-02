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
    val headers: Map<String, String>?,
    @SerializedName("drm_key_id") val drmKeyId: String?,
    @SerializedName("drm_key") val drmKey: String?,
)

data class IndexResponse(
    val version: Int?,
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

    private val sourcePriorities: Map<SourceType, Int> = mapOf(
        SourceType.BROADCASTER to 0,
        SourceType.FREE_CHANNEL to 1,
        SourceType.YOUTUBE_TV to 2,
        SourceType.SPORTS_EVENTS to 3,
        SourceType.WORLD_TV to 4,
        SourceType.GLOBAL_INDEX to 5,
        SourceType.RADIO to 6,
    )

    override fun name() = "HostedIndex"

    override fun ingest(): List<RawChannel> {
        logger.info("HostedIndexIngester", "Fetching hosted index")
        val body = fetchUrl("merged.json")
        val merged = gson.fromJson(body, MergedResponse::class.java) ?: return emptyList()

        logger.info("HostedIndexIngester", "Fetched merged catalogue with ${merged.channels?.size ?: 0} channels")
        val channels = mutableListOf<RawChannel>()

        for (ch in merged.channels.orEmpty()) {
            for ((sourceType, info) in ch.sources.orEmpty()) {
                channels.add(RawChannel(
                    id = ch.id,
                    displayName = ch.displayName,
                    streamUrl = info.streamUrl ?: continue,
                    logoUrl = ch.logoUrl,
                    category = ch.category,
                    country = ch.country,
                    language = ch.language,
                    quality = ch.quality,
                    tvgId = ch.tvgId,
                    source = sourceType,
                    headers = info.headers ?: emptyMap(),
                    drmKeyId = info.drmKeyId,
                    drmKey = info.drmKey,
                ))
            }
        }

        logger.info("HostedIndexIngester", "Extracted ${channels.size} raw channel entries from merged.json")
        return channels
    }

    fun fetchHostedOnly(): List<RawChannel> {
        val body = fetchUrl("channels.json")
        val index = gson.fromJson(body, IndexResponse::class.java) ?: return emptyList()
        val rawList = index.channels.orEmpty()
        val results = mutableListOf<RawChannel>()

        for (ch in rawList) {
            val st = sourceTypeMap[ch.source]
            if (st == null) continue
            results.add(RawChannel(
                id = ch.id,
                displayName = ch.name,
                streamUrl = ch.streamUrl,
                logoUrl = ch.logoUrl,
                category = ch.category,
                country = ch.country,
                language = ch.language,
                quality = ch.quality,
                tvgId = null,
                source = st,
                headers = ch.headers ?: emptyMap(),
                drmKeyId = ch.drmKeyId,
                drmKey = ch.drmKey,
            ))
        }
        return results
    }

    private fun fetchUrl(path: String): String {
        val baseUrl = System.getenv("DATA_BASE_URL") ?: "https://Goddiewayne.github.io/streamverse-data"
        val url = "$baseUrl/$path"
        val req = Request.Builder().url(url).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} fetching $url")
        return resp.body?.string() ?: throw RuntimeException("Empty response from $url")
    }

    data class MergedResponse(
        val version: Int?,
        val generatedAtMs: Long?,
        val channels: List<MergedChannel>?,
    )

    data class MergedChannel(
        val id: String,
        val logicalId: String?,
        val displayName: String,
        val aliases: List<String>?,
        val logoUrl: String?,
        val quality: String?,
        val category: String?,
        val language: String?,
        val country: String?,
        val description: String?,
        val tvgId: String?,
        val sources: Map<SourceType, SourceInfoDto>?,
        val isLive: Boolean?,
    )
    data class SourceInfoDto(
        val type: SourceType?,
        val referenceId: String?,
        val streamUrl: String?,
        val headers: Map<String, String>?,
        val drmKeyId: String?,
        val drmKey: String?,
        val available: Boolean?,
    )
}
