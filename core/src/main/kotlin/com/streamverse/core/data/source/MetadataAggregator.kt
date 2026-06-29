package com.streamverse.core.data.source

import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.util.CategoryNormalizer

class MetadataAggregator {

    data class MetadataSource(
        val channel: Channel,
        val providerPriority: Int,
        val signalStrength: Float,
    )

    fun aggregate(
        base: Channel,
        incoming: Channel,
        incomingProviderPriority: Int,
        matchConfidence: Float,
    ): Channel {
        val src1 = MetadataSource(base, 0, 1f)
        val src2 = MetadataSource(incoming, incomingProviderPriority, matchConfidence)

        return base.copy(
            displayName = pickBest(
                base.displayName to src1,
                incoming.displayName to src2,
                select = { a, b -> decorationScore(a) <= decorationScore(b) },
            ),
            logoUrl = pickBestLogo(base.logoUrl, incoming.logoUrl),
            quality = pickBestQuality(base.quality, incoming.quality),
            category = pickBestCategory(base.category, incoming.category),
            country = pickBest(base.country to src1, incoming.country to src2) { a, b -> a != null && (b == null || a.length >= b.length) },
            language = pickBest(base.language to src1, incoming.language to src2) { a, _ -> a != null },
            description = pickBest(base.description to src1, incoming.description to src2) { a, b ->
                a != null && (b == null || a.length >= b.length * 2)
            },
            tvgId = pickBest(base.tvgId to src1, incoming.tvgId to src2) { a, _ -> a != null },
            aliases = mergeAliases(base.aliases, incoming.aliases),
            isFavorite = base.isFavorite || incoming.isFavorite,
        )
    }

    fun mergeSources(
        base: Channel,
        incoming: Channel,
    ): Channel {
        val merged = base.sources.toMutableMap()
        for ((type, info) in incoming.sources) {
            if (!merged.containsKey(type)) {
                merged[type] = info
            }
        }
        return base.copy(sources = merged)
    }

    fun mergeAliases(existing: List<String>, incoming: List<String>): List<String> {
        val merged = existing.toMutableList()
        for (alias in incoming) {
            val norm = alias.lowercase().trim()
            if (merged.none { it.lowercase().trim() == norm }) {
                merged.add(alias)
            }
        }
        return merged
    }

    private fun decorationScore(name: String): Int =
        name.count { !it.isLetterOrDigit() && !it.isWhitespace() }

    private fun pickBestLogo(current: String?, incoming: String?): String? {
        if (current == null) return incoming
        if (incoming == null) return current
        return when {
            scoreLogo(current) >= scoreLogo(incoming) -> current
            else -> incoming
        }
    }

    private fun scoreLogo(url: String): Int {
        var score = 0
        if (url.contains(".svg", ignoreCase = true)) score += 3
        else if (url.contains(".png", ignoreCase = true)) score += 2
        else score += 1
        val depth = url.count { it == '/' }
        if (depth <= 4) score += 2
        if (!url.contains("default", ignoreCase = true)) score += 1
        return score
    }

    private fun pickBestQuality(current: Quality?, incoming: Quality?): Quality? {
        if (current == null) return incoming
        if (incoming == null) return current
        val order = listOf(Quality._4K, Quality.FHD, Quality.HD, Quality.SD)
        return minOf(order.indexOf(current), order.indexOf(incoming))
            .let { idx -> order.getOrNull(idx) }
    }

    private fun pickBestCategory(current: String?, incoming: String?): String? {
        if (current == null) return incoming
        if (incoming == null) return current
        val gen = CategoryNormalizer.C.GENERAL
        return if (current == gen) incoming else current
    }

    private fun <T> pickBest(
        current: Pair<T, MetadataSource>,
        incoming: Pair<T, MetadataSource>,
        select: (T, T) -> Boolean,
    ): T {
        val (curVal, curSrc) = current
        val (incVal, incSrc) = incoming
        return if (select(curVal, incVal)) curVal
        else if (select(incVal, curVal)) incVal
        else if (curSrc.providerPriority <= incSrc.providerPriority) curVal
        else incVal
    }
}
