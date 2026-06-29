package com.streamverse.core.data.source

import com.streamverse.core.domain.model.Channel
import com.streamverse.core.data.source.MatchSignal

enum class MatchConfidence {
    EXACT,
    HIGH,
    MEDIUM,
    LOW,
    NO_MATCH,
}

data class EngineMatchResult(
    val channel: Channel,
    val confidence: Float,
    val confidenceLevel: MatchConfidence,
    val signals: List<MatchSignal>,
)

class ChannelMatchingEngine(
    private val matcher: LogicalChannelMatcher,
) {
    private val knownMappings = mutableMapOf<String, String>()

    companion object {
        private val IDENTIFIER_SIGNALS = setOf(
            MatchSignal.EXACT_TVG_ID, MatchSignal.EXACT_NAME,
            MatchSignal.CANONICAL_NAME, MatchSignal.ALIAS_MATCH,
            MatchSignal.KNOWN_MAPPING, MatchSignal.TVG_NAME_MATCH,
        )
        private const val MEDIUM_THRESHOLD = 0.5f
        private const val HIGH_THRESHOLD = 0.7f
        private const val EXACT_THRESHOLD = 0.9f
    }

    fun findAllIdentifierChannels(channels: List<Channel>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (ch in channels) {
            ch.tvgId?.let { result[it.lowercase().trim()] = ch.id }
        }
        return result
    }

    fun findBestMatch(
        name: String,
        tvgId: String?,
        country: String?,
        language: String?,
        aliases: List<String>,
        byTvgId: Map<String, Channel>,
        byId: Map<String, Channel>,
        byCanonicalName: Map<String, Channel>,
        byWordIndex: Map<String, Set<String>>,
    ): EngineMatchResult? {
        if (tvgId != null) {
            byTvgId[tvgId.lowercase().trim()]?.let {
                return buildResult(it, listOf(MatchSignal.EXACT_TVG_ID))
            }
        }
        val canon = matcher.canonicalName(name)
        byCanonicalName[canon]?.let {
            return buildResult(it, listOf(MatchSignal.CANONICAL_NAME))
        }
        val candidates = findCandidates(name, byWordIndex, byId)
        var best: Pair<Channel, LogicalChannelMatcher.MatchResult>? = null
        for (ch in candidates) {
            val mr = matcher.findMatch(
                name = name,
                tvgId = tvgId,
                country = country,
                language = language,
                aliases = aliases,
                existing = listOf(ch),
                knownMappings = knownMappings,
            ) ?: continue
            val curBest = best?.second?.confidence ?: 0f
            if (mr.confidence > curBest) best = ch to mr
        }
        val (bestCh, bestMr) = best ?: return null
        if (isFalsePositive(bestMr.signals)) return null
        val level = classifyConfidence(bestMr.confidence)
        if (level != MatchConfidence.EXACT && level != MatchConfidence.HIGH) return null
        if (tvgId != null) knownMappings[bestCh.id] = tvgId
        return EngineMatchResult(
            channel = bestCh,
            confidence = bestMr.confidence,
            confidenceLevel = level,
            signals = bestMr.signals,
        )
    }

    private fun findCandidates(
        name: String,
        byWordIndex: Map<String, Set<String>>,
        byId: Map<String, Channel>,
    ): List<Channel> {
        val queryWords = words(name)
        if (queryWords.isEmpty()) return byId.values.toList()
        val candidateIds = mutableSetOf<String>()
        for (w in queryWords) {
            byWordIndex[w]?.let { candidateIds.addAll(it) }
        }
        return candidateIds.mapNotNull { byId[it] }
            .ifEmpty { byId.values.toList() }
    }

    private fun buildResult(ch: Channel, signals: List<MatchSignal>): EngineMatchResult {
        val confidence = multiSignalFusion(signals)
        return EngineMatchResult(
            channel = ch,
            confidence = confidence,
            confidenceLevel = classifyConfidence(confidence),
            signals = signals,
        )
    }

    private fun multiSignalFusion(signals: List<MatchSignal>): Float {
        if (signals.isEmpty()) return 0f
        val top = signals.maxOf { it.weight }
        val extras = signals.size - 1
        return (top + extras * 0.04f).coerceAtMost(1f)
    }

    private fun isFalsePositive(signals: List<MatchSignal>): Boolean =
        signals.none { it in IDENTIFIER_SIGNALS }

    private fun classifyConfidence(c: Float): MatchConfidence = when {
        c >= EXACT_THRESHOLD -> MatchConfidence.EXACT
        c >= HIGH_THRESHOLD -> MatchConfidence.HIGH
        c >= MEDIUM_THRESHOLD -> MatchConfidence.MEDIUM
        c > 0f -> MatchConfidence.LOW
        else -> MatchConfidence.NO_MATCH
    }

    private fun words(name: String): Set<String> = name.lowercase()
        .split(Regex("""[\s\-_./&]+"""))
        .filter { it.length >= 3 && it !in COMMON_WORDS }
        .toSet()

    private val COMMON_WORDS = setOf(
        "tv", "hd", "sd", "4k", "fhd", "uhd", "hdr", "the", "and", "for", "via",
        "channel", "network", "live", "stream", "news", "radio", "sport", "plus",
    )

    internal fun clearKnownMappings() { knownMappings.clear() }
}
