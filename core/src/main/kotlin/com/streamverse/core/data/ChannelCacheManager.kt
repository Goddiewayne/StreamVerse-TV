package com.streamverse.core.data

import android.content.Context
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val cacheDir get() = context.cacheDir
    private val manifestFile = File(cacheDir, "channels_v11.manifest")
    private val chunkPrefix = "channels_v11_chunk_"
    private val maxChunkSize = 500
    private val maxAgeMs = 2 * 60 * 60 * 1000L

    /** Load all chunks in parallel. Returns null if cache is absent, stale, or corrupt. */
    suspend fun load(): List<Channel>? = coroutineScope {
        if (!manifestFile.exists()) return@coroutineScope null
        if (System.currentTimeMillis() - manifestFile.lastModified() > maxAgeMs) return@coroutineScope null
        val manifest = readManifest() ?: return@coroutineScope null
        val results = (0 until manifest.chunkCount).map { i ->
            async { readChunk(chunkFile(i)) }
        }
        val all = ArrayList<Channel>(manifest.totalChannels.coerceIn(0, 200_000))
        for (r in results) {
            val chunk = r.await() ?: return@coroutineScope null
            all.addAll(chunk)
        }
        all
    }

    /** Save channels split into chunks. */
    fun save(channels: List<Channel>) {
        invalidate()
        val chunkCount = (channels.size + maxChunkSize - 1) / maxChunkSize
        for (i in 0 until chunkCount) {
            val from = i * maxChunkSize
            val to = minOf(from + maxChunkSize, channels.size)
            writeChunk(channels.subList(from, to), chunkFile(i))
        }
        writeManifest(Manifest(chunkCount = chunkCount, totalChannels = channels.size))
    }

    fun invalidate() {
        manifestFile.delete()
        var i = 0
        while (true) {
            val f = chunkFile(i)
            if (!f.exists()) break
            f.delete(); i++
        }
    }

    fun invalidateSearchable() {}

    private fun chunkFile(index: Int) = File(cacheDir, "$chunkPrefix${"%04d".format(index)}.bin")

    private class Manifest(val chunkCount: Int, val totalChannels: Int)

    private fun readManifest(): Manifest? = try {
        DataInputStream(BufferedInputStream(manifestFile.inputStream(), BUFFER)).use { inp ->
            if (inp.readInt() != MANIFEST_MAGIC) return null
            Manifest(chunkCount = inp.readInt(), totalChannels = inp.readInt())
        }
    } catch (_: Exception) { null }

    private fun writeManifest(m: Manifest) {
        try {
            DataOutputStream(BufferedOutputStream(manifestFile.outputStream(), BUFFER)).use { out ->
                out.writeInt(MANIFEST_MAGIC)
                out.writeInt(m.chunkCount)
                out.writeInt(m.totalChannels)
            }
        } catch (_: Exception) {}
    }

    private fun readChunk(file: File): List<Channel>? = try {
        DataInputStream(BufferedInputStream(file.inputStream(), BUFFER)).use { inp ->
            if (inp.readInt() != CHUNK_MAGIC) return null
            val count = inp.readInt()
            val channels = ArrayList<Channel>(count.coerceIn(0, maxChunkSize))
            repeat(count) { readOne(inp)?.let { channels.add(it) } }
            channels
        }
    } catch (_: Exception) { null }

    private fun writeChunk(channels: List<Channel>, file: File) {
        try {
            DataOutputStream(BufferedOutputStream(file.outputStream(), BUFFER)).use { out ->
                out.writeInt(CHUNK_MAGIC)
                out.writeInt(channels.size)
                for (ch in channels) writeOne(out, ch)
            }
        } catch (_: Exception) {}
    }

    private fun writeOne(out: DataOutputStream, ch: Channel) {
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
            out.writeStr(info.drmKeyId)
            out.writeStr(info.drmKey)
        }
    }

    private fun readOne(inp: DataInputStream): Channel? {
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
        return if (id.isNotBlank() && displayName.isNotBlank()) {
            Channel(
                id = id, logicalId = logicalId ?: id, displayName = displayName,
                logoUrl = logoUrl, quality = quality, category = category,
                language = language, country = country, description = description,
                sources = sources, tvgId = tvgId, aliases = aliases,
            )
        } else null
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
        const val MANIFEST_MAGIC = 0x53564D46
        const val CHUNK_MAGIC = 0x53564348
        const val BUFFER = 1 shl 16
    }
}
