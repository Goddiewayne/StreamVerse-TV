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
    private val matchingEngine: ChannelMatchingEngine,
    private val metadataAggregator: MetadataAggregator,
) {
    private val byId = LinkedHashMap<String, Channel>()
    private val byNameLower = HashMap<String, String>()
    private val byCanonicalName = HashMap<String, String>()
    private val byWordIndex = HashMap<String, MutableSet<String>>()
    private val byTvgId = HashMap<String, String>()
    private val dirtySources = mutableSetOf<SourceType>()

    var addedCount = 0; private set
    var updatedCount = 0; private set

    val channels: List<Channel> get() = synchronized(this) { byId.values.toList() }

    fun release() = synchronized(this) {
        byId.clear(); byNameLower.clear(); byCanonicalName.clear()
        byWordIndex.clear(); byTvgId.clear(); dirtySources.clear()
    }

    fun initializeFromChannels(chs: List<Channel>) = synchronized(this) {
        byId.clear(); byNameLower.clear(); byCanonicalName.clear()
        byWordIndex.clear(); byTvgId.clear()
        dirtySources.clear(); addedCount = 0; updatedCount = 0
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
            val canon = canonicalName(displayName)
            val existing = radioByName[canon] ?: byCanonicalName[canon]?.let { byId[it] }
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
                radioByName[canon] = byId[existing.id] ?: existing
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
                addToIndexes(ch); addedCount++; radioByName[canon] = ch
            }
        }
        dirtySources.add(SourceType.RADIO)
    }

    suspend fun mergeSources(items: List<SourceItem>, sourceType: SourceType) {
        var localAdded = 0; var localUpdated = 0
        for ((idx, item) in items.withIndex()) {
            if (idx % 500 == 0 && idx > 0) yield()
            val norm = item.name.lowercase().trim()
            val canon = canonicalName(item.name)
            val existing: Channel? = synchronized(this) {
                byNameLower[norm]?.let { byId[it] }
                    ?: byCanonicalName[canon]?.let { byId[it] }
                    ?: item.tvgId?.let { byTvgId[it.lowercase().trim()]?.let { id -> byId[id] } }
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
                val newCh = Channel(
                    id = "${sourceType.name.lowercase().substringBefore("_")}_${item.id}",
                    displayName = ChannelNameFormatter.stripResolution(item.name),
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
        byNameLower[ch.displayName.lowercase().trim()] = ch.id
        byCanonicalName[canonicalName(ch.displayName)] = ch.id
        ch.tvgId?.let { byTvgId[it.lowercase().trim()] = ch.id }
        words(ch.displayName).forEach { w ->
            byWordIndex.getOrPut(w) { mutableSetOf() }.add(ch.id)
        }
    }

    private fun updateIndexes(ch: Channel) {
        val old = byId[ch.id] ?: return
        val oldNorm = old.displayName.lowercase().trim()
        byNameLower.remove(oldNorm)
        byCanonicalName.remove(canonicalName(old.displayName))
        byId[ch.id] = ch
        byNameLower[ch.displayName.lowercase().trim()] = ch.id
        byCanonicalName[canonicalName(ch.displayName)] = ch.id
    }

    private fun canonicalName(name: String): String {
        var s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(RE_MARKS, "").lowercase()
        s = s.replace(RE_RES_TAG, " ")
        val alnum = s.replace(RE_NON_ALNUM, "")
        if (alnum.length < 2) return s.replace(RE_SPACES, " ").trim()
        return alnum.replace(RE_QUALITY_SUFFIX, "")
            .let { if (it.length >= 2) it else alnum }
    }

    private fun words(name: String): Set<String> = name.lowercase()
        .split(RE_WORD_SPLIT)
        .filter { it.length >= 3 && it !in COMMON_WORDS }.toSet()

    companion object {
        private val RE_MARKS = Regex("\\p{Mn}+")
        private val RE_RES_TAG = Regex("""[\(\[\{]\s*(?:\d{3,4}[pi]|4k|fhd|uhd|hdr|hd|sd)\s*[\)\]\}]""", RegexOption.IGNORE_CASE)
        private val RE_NON_ALNUM = Regex("[^a-z0-9]")
        private val RE_SPACES = Regex("\\s+")
        private val RE_QUALITY_SUFFIX = Regex("""(?:fhd|uhd|hdr|hd|sd|4k|2160p|1080p|720p|480p|360p)$""")
        private val RE_WORD_SPLIT = Regex("""[\s\-_./&]+""")
        private val COMMON_WORDS = setOf(
            "tv", "hd", "sd", "4k", "fhd", "uhd", "hdr", "the", "and", "for", "via",
            "channel", "network", "live", "stream", "news", "radio", "sport", "plus",
        )
    }
}
