package com.streamverse.core.data.catalogue

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamverse.core.data.ChannelCacheManager
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class CatalogueManifest(
    val version: Int = 0,
    val updatedAt: String = "",
    val checksum: String = "",
    val channelCount: Int = 0,
)

private data class CatalogueSource(
    val url: String,
    val label: String,
    val priority: Int,
)

@Singleton
class CatalogueClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val okHttpClient: OkHttpClient,
    private val cacheManager: ChannelCacheManager,
) {
    private val manifestFile = File(context.cacheDir, "catalogue_manifest.json")
    private val tag = "CatalogueClient"

    private val catalogueSources = listOf(
        CatalogueSource(
            url = "https://Goddiewayne.github.io/streamverse-data/merged.json",
            label = "merged",
            priority = 0,
        ),
        CatalogueSource(
            url = "https://Goddiewayne.github.io/streamverse-data/channels.json",
            label = "hosted",
            priority = 1,
        ),
    )

    data class CatalogueResult(
        val channels: List<Channel>,
        val manifest: CatalogueManifest,
        val fromCache: Boolean,
    )

    suspend fun load(): CatalogueResult = withContext(Dispatchers.IO) {
        val cachedManifest = readManifest()

        val result = fetchCatalogue(cachedManifest)
        if (result != null) return@withContext result

        val cached = cacheManager.load()
        if (cached != null && cached.isNotEmpty()) {
            Log.d(tag, "Loaded ${cached.size} channels from cache (network unavailable)")
            return@withContext CatalogueResult(cached, cachedManifest ?: CatalogueManifest(), fromCache = true)
        }

        CatalogueResult(emptyList(), CatalogueManifest(), fromCache = true)
    }

    private suspend fun fetchCatalogue(cachedManifest: CatalogueManifest?): CatalogueResult? {
        return try {
            val allChannels = fetchAllSources()

            if (allChannels.isEmpty()) return null

            val merged = mergeById(allChannels)
            val checksum = computeChecksum(merged)
            val version = (cachedManifest?.version ?: 0) + 1

            val manifest = CatalogueManifest(
                version = version,
                updatedAt = "",
                checksum = checksum,
                channelCount = merged.size,
            )

            saveManifest(manifest)
            cacheManager.save(merged)

            Log.d(tag, "Fetched ${merged.size} channels (v$version) from ${catalogueSources.size} sources")
            CatalogueResult(merged, manifest, fromCache = false)
        } catch (e: Exception) {
            Log.w(tag, "Fetch failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchAllSources(): List<List<Channel>> = coroutineScope {
        catalogueSources.map { src ->
            async {
                val channels = fetchSingleSource(src)
                Log.d(tag, "Source ${src.label}: ${channels.size} channels")
                channels
            }
        }.awaitAll()
    }

    private fun fetchSingleSource(src: CatalogueSource): List<Channel> {
        return try {
            val request = Request.Builder().url(src.url)
                .header("Accept", "application/json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()

            when (src.label) {
                "hosted" -> parseHostedIndex(body)
                else -> parseMergedJson(body)
            }
        } catch (e: Exception) {
            Log.w(tag, "Source ${src.label} failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseMergedJson(body: String): List<Channel> {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val raw: Map<String, Any> = gson.fromJson(body, type)
        val channelsRaw = raw["channels"] ?: return emptyList()
        val channelsJson = gson.toJson(channelsRaw)
        val channelType = object : TypeToken<List<Channel>>() {}.type
        return gson.fromJson(channelsJson, channelType)
    }

    private data class HostedChannel(
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

    private fun parseHostedIndex(body: String): List<Channel> {
        val responseType = object : TypeToken<Map<String, Any>>() {}.type
        val raw: Map<String, Any> = gson.fromJson(body, responseType)
        val channelsRaw = raw["channels"] ?: return emptyList()
        val channelsJson = gson.toJson(channelsRaw)
        val listType = object : TypeToken<List<HostedChannel>>() {}.type
        val hosted: List<HostedChannel> = gson.fromJson(channelsJson, listType)
        return hosted.mapNotNull { hc -> hostedToChannel(hc) }
    }

    private fun hostedToChannel(hc: HostedChannel): Channel? {
        val sourceType = sourceTypeFromString(hc.source) ?: return null
        val q = when (hc.quality?.lowercase()) {
            "4k", "2160" -> Quality._4K
            "fhd", "1080" -> Quality.FHD
            "hd", "720" -> Quality.HD
            "sd", "480" -> Quality.SD
            else -> null
        }
        return Channel(
            id = hc.id,
            displayName = hc.name,
            logoUrl = hc.logoUrl,
            quality = q,
            category = hc.category,
            language = hc.language,
            country = hc.country,
            description = null,
            sources = mapOf(
                sourceType to SourceInfo(
                    type = sourceType,
                    referenceId = hc.id,
                    streamUrl = hc.streamUrl,
                    headers = hc.headers ?: emptyMap(),
                    drmKeyId = hc.drmKeyId,
                    drmKey = hc.drmKey,
                ),
            ),
        )
    }

    private fun sourceTypeFromString(s: String): SourceType? = when (s.uppercase()) {
        "GLOBAL_INDEX", "IPTV", "FREE_TV", "FAST_TV" -> SourceType.GLOBAL_INDEX
        "FREE_CHANNEL", "FREE_LIVE" -> SourceType.FREE_CHANNEL
        "SPORTS_EVENTS", "DLHD" -> SourceType.SPORTS_EVENTS
        "WORLD_TV", "STMIFY" -> SourceType.WORLD_TV
        "YOUTUBE_TV" -> SourceType.YOUTUBE_TV
        "RADIO" -> SourceType.RADIO
        "BROADCASTER", "VERIFIED", "INDEPENDENT" -> SourceType.BROADCASTER
        else -> null
    }

    private fun mergeById(sources: List<List<Channel>>): List<Channel> {
        val merged = LinkedHashMap<String, Channel>()
        val allSources = sources.flatten()
        for (ch in allSources) {
            if (ch.id !in merged) {
                merged[ch.id] = ch
            } else {
                val existing = merged[ch.id]!!
                val mergedSources = existing.sources + ch.sources
                merged[ch.id] = existing.copy(sources = mergedSources)
            }
        }
        return merged.values.toList()
    }

    private fun computeChecksum(channels: List<Channel>): String {
        val digest = MessageDigest.getInstance("MD5")
        for (ch in channels) {
            digest.update(ch.id.toByteArray())
            digest.update(ch.displayName.toByteArray())
            ch.logoUrl?.let { digest.update(it.toByteArray()) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readManifest(): CatalogueManifest? {
        return try {
            if (!manifestFile.exists()) null
            else {
                val text = manifestFile.readText()
                gson.fromJson(text, CatalogueManifest::class.java)
            }
        } catch (_: Exception) { null }
    }

    private fun saveManifest(m: CatalogueManifest) {
        try {
            manifestFile.writeText(gson.toJson(m))
        } catch (_: Exception) {}
    }

    fun getManifest(): CatalogueManifest? = readManifest()
}
