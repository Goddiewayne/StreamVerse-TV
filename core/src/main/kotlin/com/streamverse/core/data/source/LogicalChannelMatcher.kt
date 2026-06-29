package com.streamverse.core.data.source

import com.streamverse.core.domain.model.Channel
import java.text.Normalizer

enum class MatchSignal(val weight: Float) {
    EXACT_TVG_ID(1.0f),
    EXACT_NAME(0.95f),
    CANONICAL_NAME(0.85f),
    ALIAS_MATCH(0.8f),
    TVG_NAME_MATCH(0.75f),
    KNOWN_MAPPING(0.9f),
    COUNTRY_LANG_BRAND(0.6f),
    FUZZY_HIGH(0.65f),
    FUZZY_MEDIUM(0.45f),
    PREFIX_MATCH(0.55f),
}

class LogicalChannelMatcher {

    data class MatchResult(
        val channelId: String,
        val confidence: Float,
        val signals: List<MatchSignal>,
    )

    companion object {
        private const val MATCH_THRESHOLD_HIGH = 0.7f
        private const val MATCH_THRESHOLD_MEDIUM = 0.5f

        private val COMMON_WORDS = setOf(
            "tv", "hd", "sd", "4k", "fhd", "uhd", "hdr", "the", "and", "for", "via",
            "channel", "network", "live", "stream", "news", "radio", "sport", "plus",
            "max", "one", "two", "free", "show", "music", "world", "asia", "africa",
            "europe", "middle", "east", "west", "north", "south", "central",
        )
    }

    fun findMatch(
        name: String,
        tvgId: String?,
        country: String?,
        language: String?,
        aliases: List<String>,
        existing: List<Channel>,
        knownMappings: Map<String, String> = emptyMap(),
    ): MatchResult? {
        val norm = name.lowercase().trim()
        val canon = canonicalName(name)
        var best: MatchResult? = null

        for (ch in existing) {
            val signals = mutableListOf<MatchSignal>()

            if (tvgId != null && ch.tvgId.equals(tvgId, ignoreCase = true)) {
                signals.add(MatchSignal.EXACT_TVG_ID)
            }

            val chNorm = ch.displayName.lowercase().trim()
            if (chNorm == norm) {
                signals.add(MatchSignal.EXACT_NAME)
            }

            val chCanon = canonicalName(ch.displayName)
            if (chCanon == canon) {
                signals.add(MatchSignal.CANONICAL_NAME)
            }

            val aliasMatch = aliases.any { alias ->
                val aliasNorm = alias.lowercase().trim()
                chNorm == aliasNorm || canonicalName(alias) == chCanon
            }
            if (aliasMatch) {
                signals.add(MatchSignal.ALIAS_MATCH)
            }

            if (ch.tvgId != null && tvgId != null &&
                (ch.tvgId.lowercase().contains(tvgId.lowercase()) ||
                 tvgId.lowercase().contains(ch.tvgId.lowercase()))
            ) {
                signals.add(MatchSignal.TVG_NAME_MATCH)
            }

            ch.aliases.forEach { chAlias ->
                val aliasNorm = chAlias.lowercase().trim()
                if (aliasNorm == norm || aliasNorm == canon) {
                    signals.add(MatchSignal.ALIAS_MATCH)
                }
            }

            knownMappings[ch.id]?.let { mappedRef ->
                if (tvgId?.equals(mappedRef, ignoreCase = true) == true) {
                    signals.add(MatchSignal.KNOWN_MAPPING)
                }
            }

            if (signals.isEmpty()) {
                val fuzzy = fuzzyMatchScore(name, ch.displayName)
                if (fuzzy >= 0.7f) {
                    signals.add(MatchSignal.FUZZY_HIGH)
                } else if (fuzzy >= 0.5f) {
                    signals.add(MatchSignal.FUZZY_MEDIUM)
                }
            }

            if (signals.isEmpty() && country != null && language != null) {
                val chCountry = ch.country
                val chLang = ch.language
                if (country.equals(chCountry, ignoreCase = true) &&
                    language.equals(chLang, ignoreCase = true)
                ) {
                    val brandWords = extractBrandWords(name)
                    val chBrandWords = extractBrandWords(ch.displayName)
                    if (brandWords.intersect(chBrandWords).size >= 1) {
                        signals.add(MatchSignal.COUNTRY_LANG_BRAND)
                    }
                }
            }

            if (signals.isNotEmpty()) {
                val confidence = computeConfidence(signals)
                if (confidence > (best?.confidence ?: 0f)) {
                    best = MatchResult(ch.id, confidence, signals)
                }
            }
        }

        return best?.takeIf {
            it.confidence >= MATCH_THRESHOLD_MEDIUM && !isFalsePositive(it.signals)
        }
    }

    fun findBestMatch(channelDto: SourceChannelDTO, existing: List<Channel>): MatchResult? {
        return findMatch(
            name = channelDto.name,
            tvgId = channelDto.tvgId,
            country = channelDto.country,
            language = channelDto.language,
            aliases = channelDto.aliases,
            existing = existing,
        )
    }

    fun findMatchBetweenChannels(a: Channel, b: Channel): MatchResult? {
        return findMatch(
            name = a.displayName,
            tvgId = a.tvgId,
            country = a.country,
            language = a.language,
            aliases = emptyList(),
            existing = listOf(b),
        )
    }

    private fun computeConfidence(signals: List<MatchSignal>): Float {
        if (signals.isEmpty()) return 0f
        val top = signals.maxOf { it.weight }
        val extras = signals.size - 1
        return (top + extras * 0.04f).coerceAtMost(1f)
    }

    private fun isFalsePositive(signals: List<MatchSignal>): Boolean {
        val identifierSignals = setOf(
            MatchSignal.EXACT_TVG_ID, MatchSignal.EXACT_NAME,
            MatchSignal.CANONICAL_NAME, MatchSignal.ALIAS_MATCH,
            MatchSignal.KNOWN_MAPPING, MatchSignal.TVG_NAME_MATCH,
        )
        return signals.none { it in identifierSignals }
    }

    fun fuzzyMatchScore(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f

        val aWords = extractSignificantWords(a)
        val bWords = extractSignificantWords(b)
        if (aWords.isEmpty() || bWords.isEmpty()) return 0f

        val common = aWords.intersect(bWords)
        val maxWords = maxOf(aWords.size, bWords.size)
        val scoreA = common.size.toFloat() / aWords.size
        val scoreB = common.size.toFloat() / bWords.size

        val left = a.lowercase().trim()
        val right = b.lowercase().trim()
        val prefixBonus = if (left.startsWith(right) || right.startsWith(left)) 0.15f else 0f

        return ((scoreA + scoreB) / 2f + prefixBonus).coerceAtMost(1f)
    }

    private fun extractSignificantWords(name: String): Set<String> =
        name.lowercase()
            .split(Regex("""[\s\-_./&':,()\[\]{}]+"""))
            .filter { it.length >= 3 && it !in COMMON_WORDS }
            .toSet()

    private fun extractBrandWords(name: String): Set<String> {
        val all = extractSignificantWords(name)
        return all.filterNot { it in LOW_VALUE_WORDS }.toSet()
    }

    private val LOW_VALUE_WORDS = setOf(
        "channel", "network", "live", "stream", "radio", "digital", "television",
        "broadcasting", "tv", "plus", "max", "online", "direct", "worldwide",
    )

    fun canonicalName(name: String): String {
        var s = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
        s = s.replace(
            Regex("""[\(\[\{]\s*(?:\d{3,4}[pi]|4k|fhd|uhd|hdr|hd|sd)\s*[\)\]\}]""", RegexOption.IGNORE_CASE),
            " ",
        )
        val alnum = s.replace(Regex("[^a-z0-9]"), "")
        if (alnum.length < 2) {
            return s.replace(Regex("\\s+"), " ").trim()
        }
        val trimmed = alnum.replace(Regex("""(?:fhd|uhd|hdr|hd|sd|4k|2160p|1080p|720p|480p|360p)$"""), "")
        return if (trimmed.length >= 2) trimmed else alnum
    }
}
