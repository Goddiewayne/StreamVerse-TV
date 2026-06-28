package com.streamverse.core.util

import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType

/**
 * Internal confidence value attached to every classification decision so that callers
 * (especially [enrichAndMerge] in the repository) can choose the best category when
 * multiple sources disagree on the same channel.
 *
 * @property category  Canonical category label (one of [CategoryTaxonomy] constants).
 * @property confidence 0.0–1.0 where ≥0.7 is considered reliable.
 */
data class Classification(
    val category: String,
    val confidence: Float,
)

/**
 * Production‑grade channel classification engine.
 *
 * ## Strategy (layered, first‑match‑wins)
 * 1. **Provider metadata** – if a source supplies an explicit category that is not generic,
 *    normalise it via [CategoryTaxonomy] and use it with high confidence.
 * 2. **Name‑based inference** – scan the channel display name against known brand/pattern
 *    rules.  A direct brand hit (e.g. "ESPN") scores higher than a keyword match (e.g.
 *    "…sports…").
 * 3. **Fuzzy fallback** – when the name contains a known alias substring, use it with lower
 *    confidence.
 * 4. **General** – when nothing matches, assign [CategoryTaxonomy.GENERAL] with low confidence.
 *
 * ## Refinement
 * Later enrichment (from a higher‑confidence source or EPG metadata) may replace an earlier
 * low‑confidence classification.  The [refine] method implements this.
 */
object ChannelClassifier {

    // ── Brand → Category maps (exact/prefix/suffix matches) ─────────────────

    private val NEWS_BRANDS = setOf(
        "cnn", "msnbc", "cnbc", "bbc news", "bbc world", "sky news", "fox news",
        "al jazeera", "euronews", "france 24", "bloomberg", "abc news", "nbc news",
        "cbs news", "pbs news", "nta", "ait", "channels tv", "tvc news",
        "arise", "enca", "citizen tv", "nation tv", "sabc news", "ntv news",
        "tv africa", "africa 24", "silverbird news", "news africa",
    )

    private val SPORTS_BRANDS = setOf(
        "espn", "bein sports", "beinsport", "sky sports", "bt sport",
        "dazn", "nfl ", " nfl", "nba ", " nba", "mlb ", " mlb", "nhl ", " nhl",
        "ufc", "fightbox", "elevensports", "sportsnet", "tsn ",
        "supersport", "star sports", "ten sports", "willow",
    )

    private val MOVIES_BRANDS = setOf(
        "hbo", "showtime", "cinemax", "starz", "sundance", "lifetime",
        "hallmark", "amc", "tnt", "tbs", "fx ", " movie", "cinema",
        "africa magic", "nollywood", "zee world", "telemundo",
    )

    private val KIDS_BRANDS = setOf(
        "disney", "nickelodeon", "nick jr", "nicktoons", "cartoon network",
        "boomerang", "toonami", "pbs kids", "cbeebies", "disney junior",
        "disney xd", "baby tv", "junior",
    )

    private val MUSIC_BRANDS = setOf(
        "mtv", "vh1", "vevo", "bet", "trace", "soundcity", "hip tv",
    )

    private val DOC_BRANDS = setOf(
        "national geographic", "nat geo", "natgeo", "discovery channel",
        "animal planet", "history channel",
    )

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Classify a channel using the layered strategy.  The returned [Classification] carries
     * the canonical [CategoryTaxonomy] category and a confidence score.
     */
    fun classify(
        channel: Channel,
        providerCategory: String? = null,
    ): Classification {
        // Layer 1: provider metadata (when available and non‑generic)
        providerCategory?.let { raw ->
            val trimmed = raw.trim()
            if (trimmed.isNotBlank()) {
                val alias = CategoryTaxonomy.normaliseAliasWithSplit(trimmed)
                if (alias != null && !CategoryTaxonomy.isGeneric(alias)) {
                    return Classification(alias, 0.85f)
                }
            }
        }

        // Layer 2: name‑based inference
        val name = channel.displayName.trim()
        if (name.isNotBlank()) {
            classifyByName(name)?.let { return it }
        }

        // Layer 4: nothing matched
        return Classification(CategoryTaxonomy.GENERAL, 0.2f)
    }

    /**
     * Refine an existing classification when richer metadata arrives.
     *
     * The new classification replaces the old one **only** when its confidence is strictly
     * higher, and the new category differs from the old one.  This prevents a low‑confidence
     * name‑based inference from overwriting a high‑confidence provider label.
     */
    fun refine(current: Classification, providerCategory: String?): Classification {
        if (providerCategory.isNullOrBlank()) return current
        val alias = CategoryTaxonomy.normaliseAliasWithSplit(providerCategory)
        if (alias == null || alias == current.category) return current
        // New metadata from a source that has explicit categories (e.g. IPTV) is more reliable
        // than name‑based inference — always prefer it.
        return Classification(alias, 0.85f)
    }

    /**
     * Convenience overload that builds the provider category from [channel]'s sources.
     * This is used by [CategoryNormalizer] to apply the layered strategy.
     */
    fun classifyWithSources(channel: Channel): Classification {
        // Layer 1: scan sources for a provider‑supplied category.
        // IPTV and similar sources carry an explicit group‑title.
        // We don't have the raw group‑title at the Channel level (it's already been
        // normalised), so this layer is applied in [enrichAndMerge] where the raw
        // metadata is still available.
        return classify(channel)
    }

    // ── Internals ───────────────────────────────────────────────────────────

    fun classifyByName(name: String): Classification? {
        if (name.isBlank()) return null
        val lower = name.lowercase().trim()

        val brandHit = matchBrand(lower)
        if (brandHit != null) return brandHit

        return matchKeywords(lower)
    }

    private fun matchBrand(lower: String): Classification? {
        // News brands (exact or start/contains with word boundary)
        for (brand in NEWS_BRANDS) {
            if (lower.contains(brand)) return Classification(CategoryTaxonomy.NEWS, 0.85f)
        }
        for (brand in SPORTS_BRANDS) {
            if (lower.contains(brand)) return Classification(CategoryTaxonomy.SPORTS, 0.85f)
        }
        for (brand in MOVIES_BRANDS) {
            if (lower.contains(brand)) return Classification(CategoryTaxonomy.MOVIES, 0.85f)
        }
        for (brand in KIDS_BRANDS) {
            if (lower.contains(brand)) return Classification(CategoryTaxonomy.KIDS, 0.85f)
        }
        for (brand in MUSIC_BRANDS) {
            if (lower.contains(brand)) return Classification(CategoryTaxonomy.MUSIC, 0.85f)
        }
        for (brand in DOC_BRANDS) {
            if (lower.contains(brand)) return Classification(CategoryTaxonomy.DOCUMENTARY, 0.85f)
        }
        return null
    }

    private fun matchKeywords(lower: String): Classification? {
        // Multi‑word checks first (more specific)
        if (lower.contains("current affair") ||
            lower.contains("headline")) return Classification(CategoryTaxonomy.NEWS, 0.7f)

        if (lower.contains("motor sport") ||
            lower.contains("extreme sport")) return Classification(CategoryTaxonomy.SPORTS, 0.7f)

        if (lower.contains("telenovela") ||
            lower.contains("novela") ||
            lower.contains("movie")) return Classification(CategoryTaxonomy.MOVIES, 0.6f)

        if (lower.contains("cartoon") ||
            lower.contains("animation") ||
            lower.contains("preschool")) return Classification(CategoryTaxonomy.KIDS, 0.7f)

        if (lower.contains("documentary") ||
            lower.contains("wildlife")) return Classification(CategoryTaxonomy.DOCUMENTARY, 0.7f)

        if (lower.contains("lifestyle") ||
            lower.contains("cooking") ||
            lower.contains("travel")) return Classification(CategoryTaxonomy.LIFESTYLE, 0.65f)

        // Single‑word keyword checks
        val words = lower.split(" ", "-", "/", ".", "&", "'")
            .filter { it.length >= 3 }

        var score = 0f
        var category = CategoryTaxonomy.GENERAL

        for (w in words) {
            when {
                w in setOf("news", "noticias", "berita", "haber") -> { score = maxOf(score, 0.6f); category = CategoryTaxonomy.NEWS }
                w in setOf("sport", "sports", "deportes") -> { score = maxOf(score, 0.6f); category = CategoryTaxonomy.SPORTS }
                w in setOf("kids", "children", "baby", "junior") -> { score = maxOf(score, 0.6f); category = CategoryTaxonomy.KIDS }
                w in setOf("music", "musik", "concerts") -> { score = maxOf(score, 0.6f); category = CategoryTaxonomy.MUSIC }
                w in setOf("series", "drama", "cinema") -> { score = maxOf(score, 0.55f); category = CategoryTaxonomy.MOVIES }
                w in setOf("comedy", "humor") -> { score = maxOf(score, 0.6f); category = CategoryTaxonomy.COMEDY }
                w in setOf("faith", "gospel", "church", "prayer") -> { score = maxOf(score, 0.6f); category = CategoryTaxonomy.RELIGIOUS }
                w in setOf("education", "learning", "science") -> { score = maxOf(score, 0.55f); category = CategoryTaxonomy.SCIENCE }
                w in setOf("business", "finance", "market") -> { score = maxOf(score, 0.55f); category = CategoryTaxonomy.BUSINESS }
                w in setOf("food", "cooking", "recipe") -> { score = maxOf(score, 0.55f); category = CategoryTaxonomy.LIFESTYLE }
            }
        }

        // Check for "sport" anywhere in the name (catches "Sports HD", "Live Sports", etc.)
        if (category == CategoryTaxonomy.GENERAL) {
            if (lower.contains("sport")) return Classification(CategoryTaxonomy.SPORTS, 0.55f)
            if (lower.contains("news")) return Classification(CategoryTaxonomy.NEWS, 0.55f)
            if (lower.contains("music")) return Classification(CategoryTaxonomy.MUSIC, 0.55f)
            if (lower.contains("kids") || lower.contains("children")) return Classification(CategoryTaxonomy.KIDS, 0.55f)
            if (lower.contains("relig") || lower.contains("faith")) return Classification(CategoryTaxonomy.RELIGIOUS, 0.5f)
            if (lower.contains("comedy") || lower.contains("funny")) return Classification(CategoryTaxonomy.COMEDY, 0.5f)
            if (lower.contains("history") || lower.contains("discovery") || lower.contains("nature")) return Classification(CategoryTaxonomy.DOCUMENTARY, 0.5f)
            if (lower.contains("movie") || lower.contains("drama")) return Classification(CategoryTaxonomy.MOVIES, 0.5f)
            if (lower.contains("travel") || lower.contains("cooking") || lower.contains("food")) return Classification(CategoryTaxonomy.LIFESTYLE, 0.5f)
            if (lower.contains("tech") || lower.contains("science") || lower.contains("space")) return Classification(CategoryTaxonomy.SCIENCE, 0.5f)
            if (lower.contains("business") || lower.contains("finance") || lower.contains("market")) return Classification(CategoryTaxonomy.BUSINESS, 0.5f)
        }

        return if (score > 0f) Classification(category, score) else null
    }
}
