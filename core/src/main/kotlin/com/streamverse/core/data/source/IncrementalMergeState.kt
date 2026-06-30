package com.streamverse.core.data.source

import android.util.Log
import com.streamverse.core.data.model.RadioStation
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.SourceInfo
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.CategoryNormalizer
import com.streamverse.core.util.ChannelNameFormatter
import kotlinx.coroutines.yield

data class SourceItem(
    val id: String,
    val name: String,
    val streamUrl: String?,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val language: String?,
    val quality: String?,
    val tvgId: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drmKeyId: String? = null,
    val drmKey: String? = null,
)

class IncrementalMergeState(
    private val entityResolutionEngine: EntityResolutionEngine,
    private val metadataAggregator: MetadataAggregator,
) {
    private val byId = LinkedHashMap<String, Channel>()
    private val byWordIndex = HashMap<String, MutableSet<String>>()
    private val dirtySources = mutableSetOf<SourceType>()

    var addedCount = 0; private set
    var updatedCount = 0; private set

    val channels: List<Channel> get() = synchronized(this) { byId.values.toList() }

    fun release() = synchronized(this) {
        byId.clear(); byWordIndex.clear(); dirtySources.clear()
        entityResolutionEngine.clear()
    }

    fun initializeFromChannels(chs: List<Channel>) = synchronized(this) {
        byId.clear(); byWordIndex.clear(); dirtySources.clear()
        entityResolutionEngine.clear()
        addedCount = 0; updatedCount = 0
        for (ch in chs) addToIndexes(ch)
    }

    fun mergePremiumBase(items: List<Channel>) = synchronized(this) {
        for (ch in items) {
            if (ch.id !in byId) { addToIndexes(ch); addedCount++ }
        }
    }

    fun mergeRadio(items: List<RadioStation>) = synchronized(this) {
        val radioByName = mutableMapOf<String, Channel>()
        for (r in items) {
            val displayName = ChannelNameFormatter.stripResolution(r.name)
            val canon = ChannelCanonicalizer.canonicalize(displayName, entityResolutionEngine.aliasDictionary)
            val existing = radioByName[canon.hashKey]
                ?: entityResolutionEngine.findIdByHashKey(canon.hashKey)?.let { byId[it] }
            if (existing != null) {
                val newInfo = SourceInfo(type = SourceType.RADIO, referenceId = r.id, streamUrl = r.streamUrl)
                if (newInfo !in existing.sources.values) {
                    val updated = existing.copy(
                        sources = existing.sources + (SourceType.RADIO to newInfo),
                        logoUrl = existing.logoUrl ?: r.logoUrl?.intern(),
                        country = existing.country ?: r.country?.intern(),
                        language = existing.language ?: r.language?.intern(),
                    )
                    byId[updated.id] = updated; updateIndexes(updated); updatedCount++
                }
                radioByName[canon.hashKey] = byId[existing.id] ?: existing
            } else {
                val radioId = "radio_${r.id}"
            val ch = Channel(
                id = radioId, displayName = displayName, logoUrl = r.logoUrl?.intern(),
                quality = null, category = CategoryNormalizer.C.RADIO,
                language = r.language?.intern(), country = r.country?.intern(),
                    description = buildString {
                        r.codec?.let { append(it) }
                        r.bitrate?.let { if (isNotEmpty()) append(" "); append("${it}kbps") }
                    }.ifBlank { null },
                    sources = mapOf(SourceType.RADIO to SourceInfo(type = SourceType.RADIO, referenceId = r.id, streamUrl = r.streamUrl)),
                )
                addToIndexes(ch); addedCount++; radioByName[canon.hashKey] = ch
            }
        }
        dirtySources.add(SourceType.RADIO)
    }

    suspend fun mergeSources(items: List<SourceItem>, sourceType: SourceType) {
        var localAdded = 0; var localUpdated = 0
        for ((idx, item) in items.withIndex()) {
            if (idx % 500 == 0 && idx > 0) yield()
            val norm = item.name.lowercase().trim()
            val itemCanonical = ChannelCanonicalizer.canonicalize(item.name, entityResolutionEngine.aliasDictionary)
            val existing: Channel? = synchronized(this) {
                entityResolutionEngine.findIdByExactName(norm)?.let { byId[it] }
                    ?: entityResolutionEngine.findIdByHashKey(itemCanonical.hashKey)?.let { byId[it] }
                    ?: item.tvgId?.let { entityResolutionEngine.findIdByTvgId(it.lowercase().trim()) }?.let { byId[it] }
            }
            val info = SourceInfo(
                type = sourceType, referenceId = item.id,
                streamUrl = item.streamUrl, headers = item.headers,
                drmKeyId = item.drmKeyId, drmKey = item.drmKey,
            )
            if (existing != null) {
                if (sourceType !in existing.sources) {
                    val updated = existing.copy(
                        sources = existing.sources + (sourceType to info),
                        country = existing.country ?: item.country?.intern(),
                        language = existing.language ?: item.language?.intern(),
                        logoUrl = existing.logoUrl ?: item.logoUrl?.intern(),
                        category = if (existing.category == CategoryNormalizer.C.GENERAL)
                            CategoryNormalizer.normalize(item.category, false)?.intern()
                        else existing.category,
                    )
                    synchronized(this) { byId[updated.id] = updated; updateIndexes(updated); localUpdated++ }
                }
            } else {
                val displayName = ChannelNameFormatter.stripResolution(item.name)
                val newCh = Channel(
                    id = "${sourceType.name.lowercase().substringBefore("_")}_${item.id}",
                    displayName = displayName,
                    logoUrl = item.logoUrl?.intern(),
                    quality = qualityFrom(item.quality),
                    category = CategoryNormalizer.normalize(item.category, false)?.intern(),
                    language = item.language?.intern(),
                    country = item.country?.intern(),
                    description = null,
                    sources = mapOf(sourceType to info),
                )
                synchronized(this) { addToIndexes(newCh); localAdded++ }
            }
        }
        synchronized(this) {
            addedCount += localAdded; updatedCount += localUpdated
            dirtySources.add(sourceType)
        }
    }

    private fun qualityFrom(q: String?) = when (q) {
        "4K" -> Quality._4K; "FHD" -> Quality.FHD; "HD" -> Quality.HD; "SD" -> Quality.SD; else -> null
    }

    private fun addToIndexes(ch: Channel) {
        byId[ch.id] = ch
        val canon = ChannelCanonicalizer.canonicalize(ch.displayName, entityResolutionEngine.aliasDictionary)
        entityResolutionEngine.indexChannel(
            id = ch.id, canonical = canon,
            tvgId = ch.tvgId, country = ch.country,
            displayName = ch.displayName,
        )
        words(ch.displayName).forEach { w ->
            byWordIndex.getOrPut(w) { mutableSetOf() }.add(ch.id)
        }
    }

    private fun updateIndexes(ch: Channel) {
        val old = byId[ch.id] ?: return
        val oldCanon = ChannelCanonicalizer.canonicalize(old.displayName, entityResolutionEngine.aliasDictionary)
        entityResolutionEngine.removeChannel(
            id = ch.id, oldCanonical = oldCanon,
            oldTvgId = old.tvgId, oldCountry = old.country,
            oldDisplayName = old.displayName,
        )
        val norm = ch.displayName.lowercase().trim()
        byId[ch.id] = ch
        val newCanon = ChannelCanonicalizer.canonicalize(ch.displayName, entityResolutionEngine.aliasDictionary)
        entityResolutionEngine.indexChannel(
            id = ch.id, canonical = newCanon,
            tvgId = ch.tvgId, country = ch.country,
            displayName = ch.displayName,
        )
    }

    private fun words(name: String): Set<String> = name.lowercase()
        .split(RE_WORD_SPLIT)
        .filter { it.length >= 3 && it !in COMMON_WORDS }.toSet()

    companion object {
        private val RE_WORD_SPLIT = Regex("""[\s\-_./&]+""")
        private val COMMON_WORDS = setOf(
            "tv", "hd", "sd", "4k", "fhd", "uhd", "hdr", "the", "and", "for", "via",
            "channel", "network", "live", "stream", "news", "radio", "sport", "plus",
        )
    }
}
