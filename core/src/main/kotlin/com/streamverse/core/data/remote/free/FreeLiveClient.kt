package com.streamverse.core.data.remote.free

import android.content.Context
import android.util.Log
import com.streamverse.core.data.remote.m3u.M3uParser
import com.streamverse.core.util.StreamVerseDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class FreeChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val service: String,
    val headers: Map<String, String> = emptyMap(),
    val drmKeyId: String? = null,
    val drmKey: String? = null,
)

@Singleton
class FreeLiveClient @Inject constructor(
    private val dispatchers: StreamVerseDispatchers,
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
) {
    private val client = okHttpClient.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private var cachedChannels: List<FreeChannel>? = null
    private var cacheTime: Long = 0L

    private data class SourceEntry(
        val key: String,
        val url: String,
        val name: String,
        val geo: String,
    )

    private fun loadSources(): List<SourceEntry> {
        return try {
            val json = context.assets.open("free_channels_sources.json")
                .bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val keys = obj.keys()
            val entries = mutableListOf<SourceEntry>()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = obj.getJSONObject(key)
                val url = entry.optString("url")
                if (url.isNotBlank()) {
                    entries.add(SourceEntry(key, url, entry.optString("name", key), entry.optString("geo", "ALL")))
                }
            }
            entries
        } catch (e: Exception) {
            Log.w("FreeLiveClient", "failed to load sources from asset: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchChannels(forceRefresh: Boolean = false): Result<List<FreeChannel>> =
        withContext(dispatchers.io) {
            runCatching {
                val now = System.currentTimeMillis()
                if (!forceRefresh && cachedChannels != null && (now - cacheTime) < 86_400_000L) {
                    return@runCatching cachedChannels!!
                }
                val sources = loadSources()
                if (sources.isEmpty()) {
                    Log.w("FreeLiveClient", "no sources defined in asset")
                    return@runCatching cachedChannels ?: emptyList()
                }
                val all = coroutineScope {
                    sources.map { src ->
                        async {
                            try {
                                val entries = M3uParser.parse(src.url, client)
                                Log.d("FreeLiveClient", "${src.key}: ${entries.size} channels from ${src.url}")
                                entries.map { entry ->
                                    FreeChannel(
                                        id = "${src.key}_${(entry.tvgId ?: entry.name).hashCode().and(0x7FFFFFFF)}",
                                        name = entry.name,
                                        streamUrl = entry.streamUrl,
                                        logoUrl = entry.logoUrl,
                                        category = entry.category,
                                        country = M3uParser.inferCountry(entry.tvgId) ?: src.geo,
                                        service = src.key,
                                        headers = entry.headers,
                                        drmKeyId = entry.drmKeyId,
                                        drmKey = entry.drmKey,
                                    )
                                }
                            } catch (e: Exception) {
                                Log.w("FreeLiveClient", "${src.key} failed: ${e.message}")
                                emptyList()
                            }
                        }
                    }.map { it.await() }.flatten()
                }
                cachedChannels = all
                cacheTime = now
                Log.d("FreeLiveClient", "total free channels: ${all.size}")
                all
            }
        }
}
