package com.streamverse.core.data.source

data class SimilarityScore(
    val confidence: Double,
    val signals: List<String>,
    val reason: String,
) {
    val isMergeCandidate: Boolean get() = confidence >= MIN_MERGE_CONFIDENCE
    val isHighConfidence: Boolean get() = confidence >= HIGH_CONFIDENCE

    companion object {
        const val MIN_MERGE_CONFIDENCE = 0.90
        const val HIGH_CONFIDENCE = 0.95
        val NONE = SimilarityScore(0.0, emptyList(), "no match")
    }
}

object SimilarityScorer {

    private val RE_BRAND_PREFIX = Regex("""^([a-z\s]+?)\s*(\d+)$""")
    private val KNOWN_NON_MERGE_PAIRS = setOf(
        "espn" to "espn2", "espn" to "espn 2",
        "espn2" to "espn3", "espn 2" to "espn 3",
        "cnn" to "cnn international",
        "fox news" to "fox business",
        "msnbc" to "cnbc",
        "bbc one" to "bbc two",
        "bbc one" to "bbc three",
        "bbc one" to "bbc four",
        "bbc two" to "bbc three",
        "bbc two" to "bbc four",
        "bbc three" to "bbc four",
        "discovery channel" to "discovery science",
        "discovery channel" to "discovery turbo",
        "discovery channel" to "discovery world",
        "nat geo" to "nat geo wild",
        "history channel" to "history 2",
        "cartoon network" to "cartoonito",
        "nickelodeon" to "nick jr",
        "nickelodeon" to "nicktoons",
        "mtv" to "mtv base",
        "mtv" to "mtv hits",
        "mtv" to "mtv rocks",
        "mtv" to "mtv live",
        "comedy central" to "comedy central extra",
        "e!" to "e! entertainment",
        "tnt" to "tnt series",
        "tbs" to "tnt",
        "sky sports" to "sky sports news",
        "sky sports" to "sky sports mix",
        "sky sports 1" to "sky sports 2",
        "sky sports 1" to "sky sports 3",
        "sky sports 2" to "sky sports 3",
        "bein sports" to "bein sports news",
        "bein sports 1" to "bein sports 2",
        "bein sports 1" to "bein sports 3",
        "bein sports 2" to "bein sports 3",
        "eurosport 1" to "eurosport 2",
        "rai 1" to "rai 2",
        "rai 1" to "rai 3",
        "rai 2" to "rai 3",
        "rai 1" to "rai 4",
        "rai 2" to "rai 4",
        "rai 3" to "rai 4",
        "channel 4" to "channel 5",
        "bbc world news" to "bbc news",
        "al jazeera english" to "al jazeera arabic",
        "france 24 english" to "france 24 francais",
        "cgtn" to "cgtn documentary",
        "cgtn" to "cgtn russian",
        "cgtn" to "cgtn french",
        "cgtn" to "cgtn spanish",
        "cgtn" to "cgtn arabic",
    )

    private const val SIGNAL_EXACT_HASH_KEY = "EXACT_HASH_KEY"
    private const val SIGNAL_EXACT_NAME = "EXACT_NAME"
    private const val SIGNAL_ALIAS_RESOLVED = "ALIAS_RESOLVED"
    private const val SIGNAL_TOKEN_JACCARD_HIGH = "TOKEN_JACCARD_HIGH"
    private const val SIGNAL_TOKEN_JACCARD_MEDIUM = "TOKEN_JACCARD_MEDIUM"
    private const val SIGNAL_TOKEN_JACCARD_LOW = "TOKEN_JACCARD_LOW"
    private const val SIGNAL_COUNTRY_MATCH = "COUNTRY_MATCH"
    private const val SIGNAL_LANGUAGE_MATCH = "LANGUAGE_MATCH"
    private const val SIGNAL_LOGO_MATCH = "LOGO_MATCH"
    private const val SIGNAL_NUMERIC_ID_MISMATCH = "NUMERIC_ID_MISMATCH"
    private const val SIGNAL_BRAND_SHARED_NAME_MISMATCH = "BRAND_SHARED_NAME_MISMATCH"
    private const val SIGNAL_KNOWN_NON_MERGE = "KNOWN_NON_MERGE"

    private const val WEIGHT_EXACT_HASH_KEY = 1.0
    private const val WEIGHT_EXACT_NAME = 0.99
    private const val WEIGHT_ALIAS_RESOLVED = 0.95
    private const val WEIGHT_TOKEN_JACCARD_HIGH = 0.80
    private const val WEIGHT_TOKEN_JACCARD_MEDIUM = 0.60
    private const val WEIGHT_TOKEN_JACCARD_LOW = 0.35
    private const val WEIGHT_COUNTRY_MATCH = 0.15
    private const val WEIGHT_LANGUAGE_MATCH = 0.15
    private const val WEIGHT_LOGO_MATCH = 0.30

    fun score(
        canonical1: CanonicalResult,
        canonical2: CanonicalResult,
        tvgId1: String?,
        tvgId2: String?,
        country1: String?,
        country2: String?,
        language1: String?,
        language2: String?,
        logoUrl1: String?,
        logoUrl2: String?,
    ): SimilarityScore {
        if (canonical1.hashKey.isEmpty() || canonical2.hashKey.isEmpty()) {
            return SimilarityScore.NONE
        }

        val signals = mutableListOf<String>()
        val c1 = canonical1.canonicalName
        val c2 = canonical2.canonicalName
        val h1 = canonical1.hashKey
        val h2 = canonical2.hashKey

        if (h1 == h2) {
            return SimilarityScore(1.0, listOf(SIGNAL_EXACT_HASH_KEY), "exact hash key match: $h1")
        }

        if (tvgId1 != null && tvgId2 != null &&
            tvgId1.lowercase().trim() == tvgId2.lowercase().trim() &&
            tvgId1.isNotBlank() && tvgId2.isNotBlank()
        ) {
            return SimilarityScore(1.0, listOf("EXACT_TVG_ID"), "exact tvgId match: $tvgId1")
        }

        if (isKnownNonMerge(c1, c2)) {
            return SimilarityScore(0.0, listOf(SIGNAL_KNOWN_NON_MERGE), "known non-merge pair: $c1 vs $c2")
        }

        if (hasNumericChannelMismatch(c1, c2)) {
            return SimilarityScore(0.0, listOf(SIGNAL_NUMERIC_ID_MISMATCH), "numeric channel id mismatch: $c1 vs $c2")
        }

        if (hasBrandSharedNameMismatch(c1, c2)) {
            return SimilarityScore(0.0, listOf(SIGNAL_BRAND_SHARED_NAME_MISMATCH), "shared brand name mismatch: $c1 vs $c2")
        }

        if (canonical1.resolvedAlias && canonical2.resolvedAlias && h1 == h2) {
            signals.add(SIGNAL_ALIAS_RESOLVED)
            return SimilarityScore(WEIGHT_ALIAS_RESOLVED, signals, "alias resolved match: $h1")
        }

        if (c1 == c2) {
            signals.add(SIGNAL_EXACT_NAME)
            val boost = countryMatchBoost(country1, country2, language1, language2)
            return SimilarityScore(
                confidence = maxOf(0.0, minOf(1.0, WEIGHT_EXACT_NAME + boost)),
                signals = signals,
                reason = "exact name match: $c1",
            )
        }

        if (canonical1.resolvedAlias && canonical2.resolvedAlias && c1 == c2) {
            signals.add(SIGNAL_ALIAS_RESOLVED)
            val boost = countryMatchBoost(country1, country2, language1, language2)
            return SimilarityScore(
                confidence = maxOf(0.0, minOf(1.0, WEIGHT_ALIAS_RESOLVED + boost)),
                signals = signals,
                reason = "alias resolved match: $c1",
            )
        }

        val tokenSim = tokenJaccard(canonical1.tokens, canonical2.tokens)
        val tokenSignal = when {
            tokenSim >= 0.6 -> { signals.add(SIGNAL_TOKEN_JACCARD_HIGH); WEIGHT_TOKEN_JACCARD_HIGH }
            tokenSim >= 0.4 -> { signals.add(SIGNAL_TOKEN_JACCARD_MEDIUM); WEIGHT_TOKEN_JACCARD_MEDIUM }
            tokenSim >= 0.2 -> { signals.add(SIGNAL_TOKEN_JACCARD_LOW); WEIGHT_TOKEN_JACCARD_LOW }
            else -> 0.0
        }

        if (country1 != null && country2 != null && country1.equals(country2, ignoreCase = true)) {
            signals.add(SIGNAL_COUNTRY_MATCH)
        }
        if (language1 != null && language2 != null && language1.equals(language2, ignoreCase = true)) {
            signals.add(SIGNAL_LANGUAGE_MATCH)
        }

        if (logoUrl1 != null && logoUrl2 != null && logoUrlsMatch(logoUrl1, logoUrl2)) {
            signals.add(SIGNAL_LOGO_MATCH)
        }

        val confidence = weightedConfidence(tokenSignal, signals)

        val reason = "signals=[${signals.joinToString(",")}] tokenSim=${"%.2f".format(tokenSim)}"

        return SimilarityScore(
            confidence = maxOf(0.0, minOf(1.0, confidence)),
            signals = signals,
            reason = reason,
        )
    }

    private fun isKnownNonMerge(c1: String, c2: String): Boolean {
        val pair1 = c1 to c2
        val pair2 = c2 to c1
        return pair1 in KNOWN_NON_MERGE_PAIRS || pair2 in KNOWN_NON_MERGE_PAIRS
    }

    private fun hasNumericChannelMismatch(c1: String, c2: String): Boolean {
        val m1 = RE_BRAND_PREFIX.find(c1)
        val m2 = RE_BRAND_PREFIX.find(c2)
        if (m1 != null && m2 != null) {
            if (m1.groupValues[1].trim() == m2.groupValues[1].trim()) {
                val num1 = m1.groupValues[2].trim()
                val num2 = m2.groupValues[2].trim()
                if (num1 != num2) return true
            }
        }
        return false
    }

    private fun hasBrandSharedNameMismatch(c1: String, c2: String): Boolean {
        val c1Lower = c1.lowercase()
        val c2Lower = c2.lowercase()
        val shorter = if (c1Lower.length <= c2Lower.length) c1Lower else c2Lower
        val longer = if (c1Lower.length <= c2Lower.length) c2Lower else c1Lower
        if (shorter.length < 3) return false
        if (longer.startsWith(shorter) && longer.length > shorter.length + 1) return true
        return false
    }

    private fun tokenJaccard(tokens1: Set<String>, tokens2: Set<String>): Double {
        if (tokens1.isEmpty() && tokens2.isEmpty()) return 0.0
        val intersection = tokens1.intersect(tokens2).size.toDouble()
        val union = tokens1.union(tokens2).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun logoUrlsMatch(url1: String, url2: String): Boolean {
        val u1 = url1.lowercase().trim()
        val u2 = url2.lowercase().trim()
        if (u1 == u2) return true
        val name1 = u1.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[-_]"), "")
        val name2 = u2.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[-_]"), "")
        return name1 == name2
    }

    private fun countryMatchBoost(
        country1: String?, country2: String?,
        language1: String?, language2: String?,
    ): Double {
        var boost = 0.0
        if (country1 != null && country2 != null && country1.equals(country2, ignoreCase = true)) {
            boost += WEIGHT_COUNTRY_MATCH
        }
        if (language1 != null && language2 != null && language1.equals(language2, ignoreCase = true)) {
            boost += WEIGHT_LANGUAGE_MATCH
        }
        return boost
    }

    private fun weightedConfidence(tokenWeight: Double, signals: List<String>): Double {
        if (signals.isEmpty()) return 0.0

        var total = 0.0
        for (signal in signals) {
            total += signalWeight(signal)
        }

        val hasTokenHigh = SIGNAL_TOKEN_JACCARD_HIGH in signals
        val tokenBoost = if (!hasTokenHigh) tokenWeight * 0.15 else 0.0

        return total + tokenBoost
    }

    private fun signalWeight(signal: String): Double = when (signal) {
        SIGNAL_EXACT_HASH_KEY -> WEIGHT_EXACT_HASH_KEY
        SIGNAL_EXACT_NAME -> WEIGHT_EXACT_NAME
        SIGNAL_ALIAS_RESOLVED -> WEIGHT_ALIAS_RESOLVED
        SIGNAL_TOKEN_JACCARD_HIGH -> WEIGHT_TOKEN_JACCARD_HIGH
        SIGNAL_TOKEN_JACCARD_MEDIUM -> WEIGHT_TOKEN_JACCARD_MEDIUM
        SIGNAL_TOKEN_JACCARD_LOW -> WEIGHT_TOKEN_JACCARD_LOW
        SIGNAL_COUNTRY_MATCH -> WEIGHT_COUNTRY_MATCH
        SIGNAL_LANGUAGE_MATCH -> WEIGHT_LANGUAGE_MATCH
        SIGNAL_LOGO_MATCH -> WEIGHT_LOGO_MATCH
        SIGNAL_NUMERIC_ID_MISMATCH -> -0.50
        SIGNAL_BRAND_SHARED_NAME_MISMATCH -> -0.40
        SIGNAL_KNOWN_NON_MERGE -> -1.0
        else -> 0.0
    }
}
