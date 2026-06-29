package com.streamverse.core.data.remote.broadcaster

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamverse.core.data.remote.iptv.IptvChannel
import com.streamverse.core.util.StreamVerseDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class BroadcasterChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val language: String?,
    val quality: String?,
    val headers: Map<String, String>? = null,
    val drmKeyId: String? = null,
    val drmKey: String? = null,
)

@Singleton
class BroadcasterClient @Inject constructor(
    private val dispatchers: StreamVerseDispatchers,
    @ApplicationContext private val appContext: Context,
) {
    companion object {
        private const val TAG = "BroadcasterClient"
        private const val ASSET_FILE = "broadcaster_sources.json"
    }

    private val gson = Gson()

    @Volatile
    private var cached: List<BroadcasterChannel>? = null

    suspend fun fetchChannels(): List<IptvChannel> = withContext(dispatchers.io) {
        val channels = getOrLoad()
        channels.map { ch ->
            IptvChannel(
                id = ch.id,
                name = ch.name,
                streamUrl = ch.streamUrl,
                logoUrl = ch.logoUrl,
                category = ch.category,
                country = ch.country,
                language = ch.language,
                quality = ch.quality,
                headers = ch.headers ?: emptyMap(),
                drmKeyId = ch.drmKeyId,
                drmKey = ch.drmKey,
            )
        }
    }

    private fun getOrLoad(): List<BroadcasterChannel> {
        cached?.let { return it }
        val loaded = loadFromAsset()
        cached = loaded
        Log.i(TAG, "Loaded ${loaded.size} broadcaster channels from $ASSET_FILE")
        return loaded
    }

    private fun loadFromAsset(): List<BroadcasterChannel> {
        return try {
            val json = appContext.assets.open(ASSET_FILE).bufferedReader().readText()
            gson.fromJson(json, object : TypeToken<List<BroadcasterChannel>>() {}.type)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $ASSET_FILE: ${e.message}")
            emptyList()
        }
    }
}
