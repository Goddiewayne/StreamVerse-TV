package com.streamverse.core.data

import android.content.Context
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Binary streaming cache: length-prefixed UTF-8, far faster + lower-alloc than JSON for 50k+
    // channels. Bump the file suffix when the on-disk format or Channel model changes.
    private val cacheFile = File(context.cacheDir, "sv_channels_v10.bin")
    private val maxAgeMs = 2 * 60 * 60 * 1000L  // 2 hours

    private val searchableCacheFile = File(context.cacheDir, "sv_searchable_v2.bin")
    private val searchableMaxAgeMs = 24 * 60 * 60 * 1000L  // 24 hours

    fun load(): List<Channel>? = readIfFresh(cacheFile, maxAgeMs)

    fun save(channels: List<Channel>) = writeChannels(channels, cacheFile)

    fun invalidate() = cacheFile.delete()

    fun loadSearchable(): List<Channel>? = readIfFresh(searchableCacheFile, searchableMaxAgeMs)

    fun saveSearchable(channels: List<Channel>) = writeChannels(channels, searchableCacheFile)

    fun invalidateSearchable() = searchableCacheFile.delete()

    private fun readIfFresh(file: File, maxAge: Long): List<Channel>? {
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() > maxAge) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream(), BUFFER)).use { readChannels(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeChannels(channels: List<Channel>, file: File) {
        try {
            DataOutputStream(BufferedOutputStream(file.outputStream(), BUFFER)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(channels.size)
                for (ch in channels) {
                    out.writeStr(ch.id)
                    out.writeStr(ch.logicalId)
                    out.writeStr(ch.displayName)
                    out.writeStr(ch.logoUrl)
                    out.writeStr(ch.quality?.name)
                    out.writeStr(ch.category)
                    out.writeStr(ch.language)
                    out.writeStr(ch.country)
                    out.writeStr(ch.description)
                    out.writeStr(ch.tvgId)
                    out.writeInt(ch.aliases.size)
                    for (a in ch.aliases) out.writeStr(a)
                    out.writeInt(ch.sources.size)
                    for ((type, info) in ch.sources) {
                        out.writeStr(type.name)
                        out.writeStr(info.referenceId)
                        out.writeStr(info.streamUrl)
                        // headers intentionally omitted from cache — saves ~50MB heap on phone
                        out.writeStr(info.drmKeyId)
                        out.writeStr(info.drmKey)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun readChannels(inp: DataInputStream): List<Channel> {
        if (inp.readInt() != MAGIC) return emptyList()
        val count = inp.readInt()
        val channels = ArrayList<Channel>(count.coerceIn(0, 200_000))
        repeat(count) {
            val id = inp.readStr().orEmpty()
            val logicalId = inp.readStr()?.takeIf { it.isNotBlank() }
            val displayName = inp.readStr().orEmpty()
            val logoUrl = inp.readStr()?.takeIf { it.isNotBlank() }?.intern()
            val quality = inp.readStr()?.let { runCatching { Quality.valueOf(it) }.getOrNull() }
            val category = inp.readStr()?.takeIf { it.isNotBlank() }?.intern()
            val language = inp.readStr()?.takeIf { it.isNotBlank() }?.intern()
            val country = inp.readStr()?.takeIf { it.isNotBlank() }?.intern()
            val description = inp.readStr()?.takeIf { it.isNotBlank() }
            val tvgId = inp.readStr()?.takeIf { it.isNotBlank() }
            val aliasCount = inp.readInt()
            val aliases = ArrayList<String>(aliasCount.coerceIn(0, 64))
            repeat(aliasCount) { inp.readStr()?.let { aliases.add(it) } }
            val srcCount = inp.readInt()
            val sources = LinkedHashMap<SourceType, SourceInfo>(srcCount.coerceAtLeast(1))
            repeat(srcCount) {
                val typeName = inp.readStr()
                val refId = inp.readStr().orEmpty()
                val streamUrl = inp.readStr()?.takeIf { it.isNotBlank() }
                val drmKeyId = inp.readStr()?.takeIf { it.isNotBlank() }
                val drmKey = inp.readStr()?.takeIf { it.isNotBlank() }
                val type = typeName?.let { runCatching { SourceType.valueOf(it) }.getOrNull() }
                if (type != null) {
                    sources[type] = SourceInfo(
                        type = type, referenceId = refId, streamUrl = streamUrl,
                        headers = emptyMap(), drmKeyId = drmKeyId, drmKey = drmKey,
                    )
                }
            }
            if (id.isNotBlank() && displayName.isNotBlank()) {
                channels.add(
                    Channel(
                        id = id, logicalId = logicalId ?: id, displayName = displayName,
                        logoUrl = logoUrl, quality = quality, category = category,
                        language = language, country = country, description = description,
                        sources = sources, tvgId = tvgId, aliases = aliases,
                    )
                )
            }
        }
        return channels
    }

    private fun DataOutputStream.writeStr(s: String?) {
        if (s == null) { writeInt(-1); return }
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readStr(): String? {
        val len = readInt()
        if (len < 0) return null
        val bytes = ByteArray(len)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private companion object {
        const val MAGIC = 0x53564331  // "SVC1"
        const val BUFFER = 1 shl 16
    }
}
