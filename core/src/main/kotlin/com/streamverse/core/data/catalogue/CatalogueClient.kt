package com.streamverse.core.data.catalogue

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamverse.core.data.ChannelCacheManager
import com.streamverse.core.domain.model.Channel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CatalogueManifest(
    val version: Int = 0,
    val updatedAt: String = "",
    val channelCount: Int = 0,
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
    private val catalogueUrl = "https://Goddiewayne.github.io/streamverse-data/channels.json"

    data class CatalogueResult(
        val channels: List<Channel>,
        val manifest: CatalogueManifest,
        val fromCache: Boolean,
    )

    suspend fun load(): CatalogueResult = withContext(Dispatchers.IO) {
        val cachedManifest = readManifest()

        val result = fetchCatalogue()
        if (result != null) return@withContext result

        val cached = cacheManager.load()
        if (cached != null && cached.isNotEmpty()) {
            Log.d(tag, "Loaded ${cached.size} channels from cache (network unavailable)")
            return@withContext CatalogueResult(cached, cachedManifest ?: CatalogueManifest(), fromCache = true)
        }

        CatalogueResult(emptyList(), CatalogueManifest(), fromCache = true)
    }

    private suspend fun fetchCatalogue(): CatalogueResult? {
        return try {
            val channels = fetchSingleSource()
            if (channels.isEmpty()) return null

            val version = (readManifest()?.version ?: 0) + 1
            val manifest = CatalogueManifest(
                version = version,
                channelCount = channels.size,
            )

            saveManifest(manifest)
            cacheManager.save(channels)

            Log.d(tag, "Fetched ${channels.size} channels (v$version)")
            CatalogueResult(channels, manifest, fromCache = false)
        } catch (e: Exception) {
            Log.w(tag, "Fetch failed: ${e.message}")
            null
        }
    }

    private fun fetchSingleSource(): List<Channel> {
        return try {
            val request = Request.Builder().url(catalogueUrl)
                .header("Accept", "application/json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            parseChannelsJson(body)
        } catch (e: Exception) {
            Log.w(tag, "Fetch failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseChannelsJson(body: String): List<Channel> {
        val type = object : TypeToken<List<Channel>>() {}.type
        return gson.fromJson(body, type) ?: emptyList()
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
