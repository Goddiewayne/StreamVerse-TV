package com.streamverse.pipeline.canonicalizer

import com.streamverse.pipeline.model.RawChannel
import com.streamverse.pipeline.model.SourceType
import com.streamverse.pipeline.model.CanonicalChannel

class MatchingEngine(
    private val aliasDict: AliasDictionary,
) {
    private val MIN_SIMILARITY = 0.70

    fun findPotentialMatches(
        channel: RawChannel,
        existing: List<CanonicalChannel>,
    ): List<MatchResult> {
        val hk = NameNormalizer.hashKey(channel.displayName, aliasDict)
        if (hk.isBlank()) return emptyList()

        val results = mutableListOf<MatchResult>()

        for (candidate in existing) {
            val candidateHk = NameNormalizer.hashKey(candidate.displayName, aliasDict)
            if (candidateHk == hk) {
                results.add(MatchResult(candidate, 1.0, "exact_hash"))
                continue
            }
            val similarity = computeSimilarity(
                channel.displayName.lowercase(),
                candidate.displayName.lowercase(),
            )
            if (similarity >= MIN_SIMILARITY) {
                results.add(MatchResult(candidate, similarity, "fuzzy"))
            }
        }

        return results.sortedByDescending { it.confidence }
    }

    fun exactMatchExists(
        raw: RawChannel,
        canonicalChannels: Map<String, CanonicalChannel>,
    ): Boolean {
        val hk = NameNormalizer.hashKey(raw.displayName, aliasDict)
        if (hk.isBlank()) return false
        return canonicalChannels.values.any {
            NameNormalizer.hashKey(it.displayName, aliasDict) == hk
        }
    }

    private fun computeSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val bigramA = a.windowed(2).toSet()
        val bigramB = b.windowed(2).toSet()
        if (bigramA.isEmpty() || bigramB.isEmpty()) return 0.0

        val intersection = bigramA.intersect(bigramB).size
        val union = bigramA.union(bigramB).size

        return intersection.toDouble() / union.toDouble()
    }

    data class MatchResult(
        val channel: CanonicalChannel,
        val confidence: Double,
        val method: String,
    )
}
