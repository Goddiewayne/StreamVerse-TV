package com.streamverse.core.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()
    // Bump version suffix when Channel model changes to auto-invalidate old cache
    private val cacheFile = File(context.cacheDir, "sv_channels_v9.json")
    private val maxAgeMs = 2 * 60 * 60 * 1000L  // 2 hours

    private val searchableCacheFile = File(context.cacheDir, "sv_searchable_v1.json")
    private val searchableMaxAgeMs = 24 * 60 * 60 * 1000L  // 24 hours

    fun load(): List<Channel>? {
        if (!cacheFile.exists()) return null
        if (System.currentTimeMillis() - cacheFile.lastModified() > maxAgeMs) return null
        return try {
            FileReader(cacheFile).use { reader ->
                readChannelsFromStream(reader)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun save(channels: List<Channel>) {
        try {
            FileWriter(cacheFile).use { writer ->
                writeChannelsToStream(channels, writer)
            }
        } catch (_: Exception) {}
    }

    fun invalidate() = cacheFile.delete()

    fun loadSearchable(): List<Channel>? {
        if (!searchableCacheFile.exists()) return null
        if (System.currentTimeMillis() - searchableCacheFile.lastModified() > searchableMaxAgeMs) return null
        return try {
            FileReader(searchableCacheFile).use { reader ->
                readChannelsFromStream(reader)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveSearchable(channels: List<Channel>) {
        try {
            FileWriter(searchableCacheFile).use { writer ->
                writeChannelsToStream(channels, writer)
            }
        } catch (_: Exception) {}
    }

    fun invalidateSearchable() = searchableCacheFile.delete()

    /** Stream-based JSON write - avoids building massive string in memory for 15K+ channels */
    private fun writeChannelsToStream(channels: List<Channel>, writer: FileWriter) {
        val jsonWriter = JsonWriter(BufferedWriter(writer))
        jsonWriter.setLenient(true)
        // Omit null fields entirely — smaller files AND it keeps the reader off NULL tokens
        // (JsonReader.nextString() throws on a NULL token, which previously made any channel with a
        // null field abort the whole cache read → the cache silently never loaded).
        jsonWriter.serializeNulls = false
        jsonWriter.beginArray()
        for (ch in channels) {
            jsonWriter.beginObject()
            jsonWriter.name("id").value(ch.id)
            jsonWriter.name("displayName").value(ch.displayName)
            jsonWriter.name("logoUrl").value(ch.logoUrl)
            jsonWriter.name("quality").value(ch.quality?.name)
            jsonWriter.name("category").value(ch.category)
            jsonWriter.name("language").value(ch.language)
            jsonWriter.name("country").value(ch.country)
            jsonWriter.name("description").value(ch.description)
            // Serialize sources map
            jsonWriter.name("sources")
            jsonWriter.beginObject()
            for ((type, info) in ch.sources) {
                jsonWriter.name(type.name)
                jsonWriter.beginObject()
                jsonWriter.name("type").value(info.type.name)
                jsonWriter.name("referenceId").value(info.referenceId)
                jsonWriter.name("streamUrl").value(info.streamUrl)
                jsonWriter.name("headers")
                jsonWriter.beginObject()
                for ((k, v) in info.headers) {
                    jsonWriter.name(k).value(v)
                }
                jsonWriter.endObject()
                jsonWriter.name("drmKeyId").value(info.drmKeyId)
                jsonWriter.name("drmKey").value(info.drmKey)
                jsonWriter.endObject()
            }
            jsonWriter.endObject()
            jsonWriter.endObject()
        }
        jsonWriter.endArray()
        jsonWriter.close()
    }

    /** Stream-based JSON read — parses the channel array incrementally without loading the full
     *  JSON string. Null-tolerant and resilient: a null token, an unknown enum, or one malformed
     *  channel is skipped rather than aborting the whole cache (which would silently disable it). */
    private fun readChannelsFromStream(reader: FileReader): List<Channel> {
        val jsonReader = JsonReader(BufferedReader(reader))
        jsonReader.isLenient = true
        val channels = mutableListOf<Channel>()
        jsonReader.beginArray()
        while (jsonReader.hasNext()) {
            jsonReader.beginObject()
            var id = ""
            var displayName = ""
            var logoUrl: String? = null
            var quality: Quality? = null
            var category: String? = null
            var language: String? = null
            var country: String? = null
            var description: String? = null
            val sources = mutableMapOf<SourceType, SourceInfo>()

            while (jsonReader.hasNext()) {
                when (jsonReader.nextName()) {
                    "id" -> id = jsonReader.nextStringOrNull().orEmpty()
                    "displayName" -> displayName = jsonReader.nextStringOrNull().orEmpty()
                    "logoUrl" -> logoUrl = jsonReader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                    "quality" -> quality = jsonReader.nextStringOrNull()
                        ?.let { runCatching { Quality.valueOf(it) }.getOrNull() }
                    "category" -> category = jsonReader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                    "language" -> language = jsonReader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                    "country" -> country = jsonReader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                    "description" -> description = jsonReader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                    "sources" -> {
                        jsonReader.beginObject()
                        while (jsonReader.hasNext()) {
                            val type = runCatching { SourceType.valueOf(jsonReader.nextName()) }.getOrNull()
                            if (type == null) { jsonReader.skipValue(); continue }  // unknown source type
                            jsonReader.beginObject()
                            var refId = ""
                            var streamUrl: String? = null
                            val headers = mutableMapOf<String, String>()
                            var drmKeyId: String? = null
                            var drmKey: String? = null
                            while (jsonReader.hasNext()) {
                                when (jsonReader.nextName()) {
                                    "referenceId" -> refId = jsonReader.nextStringOrNull().orEmpty()
                                    "streamUrl" -> streamUrl = jsonReader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                                    "headers" -> {
                                        jsonReader.beginObject()
                                        while (jsonReader.hasNext()) {
                                            val hKey = jsonReader.nextName()
                                            jsonReader.nextStringOrNull()?.let { headers[hKey] = it }
                                        }
                                        jsonReader.endObject()
                                    }
                                    "drmKeyId" -> drmKeyId = jsonReader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                                    "drmKey" -> drmKey = jsonReader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                                    else -> jsonReader.skipValue()   // "type" etc.
                                }
                            }
                            jsonReader.endObject()
                            sources[type] = SourceInfo(
                                type = type,
                                referenceId = refId,
                                streamUrl = streamUrl,
                                headers = headers,
                                drmKeyId = drmKeyId,
                                drmKey = drmKey,
                            )
                        }
                        jsonReader.endObject()
                    }
                    else -> jsonReader.skipValue()
                }
            }
            jsonReader.endObject()
            if (id.isNotBlank() && displayName.isNotBlank()) {
                channels.add(Channel(
                    id = id,
                    displayName = displayName,
                    logoUrl = logoUrl,
                    quality = quality,
                    category = category,
                    language = language,
                    country = country,
                    description = description,
                    sources = sources,
                ))
            }
        }
        jsonReader.endArray()
        jsonReader.close()
        return channels
    }

    /** Reads a string, or consumes a JSON null and returns null — never throws on a NULL token. */
    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
}
