package com.streamverse.core.util

/**
 * Standardised application-wide category taxonomy.  Every one of the ~fifteen categories below
 * appears as a selectable filter in Search, a Home‑screen rail, and a header in the TV guide.
 *
 * ## Aliases
 * Provider metadata may use any of the aliases defined in [ALIASES]; the normalisation pass
 * maps them all to the canonical constant.
 */
object CategoryTaxonomy {

    // ── Canonical categories ─────────────────────────────────────────────────
    const val NEWS          = "News"
    const val SPORTS        = "Sports"
    const val MOVIES        = "Movies & Series"
    const val KIDS          = "Kids & Family"
    const val MUSIC         = "Music"
    const val DOCUMENTARY   = "Documentary"
    const val RELIGIOUS     = "Religious"
    const val LIFESTYLE     = "Lifestyle"
    const val COMEDY        = "Comedy"
    const val SCIENCE       = "Science & Tech"
    const val ENTERTAINMENT = "Entertainment"
    const val BUSINESS      = "Business"
    const val GENERAL       = "General"
    const val RADIO         = "Radio"

    /** Display order for the browse headers (TV/filter). */
    val ALL_TV = listOf(
        NEWS, SPORTS, MOVIES, KIDS, MUSIC, DOCUMENTARY,
        RELIGIOUS, LIFESTYLE, COMEDY, SCIENCE, BUSINESS, ENTERTAINMENT, GENERAL,
    )
    val ALL = ALL_TV + RADIO

    /**
     * Aliases that provider metadata may use for each canonical category.
     * Every provider-specific label is normalised to the canonical key via [normaliseAlias].
     */
    val ALIASES: Map<String, String> = mapOf(
        // ── News ──────────────────────────────────────────────────────────
        "news"               to NEWS,
        "news & politics"    to NEWS,
        "current affairs"    to NEWS,
        "headlines"          to NEWS,
        "noticias"           to NEWS,
        "nachrichten"        to NEWS,
        "actualités"         to NEWS,
        "información"        to NEWS,
        "berita"             to NEWS,
        "haber"              to NEWS,

        // ── Sports ─────────────────────────────────────────────────────────
        "sports"             to SPORTS,
        "sport"              to SPORTS,
        "football"           to SPORTS,
        "soccer"             to SPORTS,
        "basketball"         to SPORTS,
        "cricket"            to SPORTS,
        "tennis"             to SPORTS,
        "golf"               to SPORTS,
        "motor sports"       to SPORTS,
        "motorsport"         to SPORTS,
        "racing"             to SPORTS,
        "nfl"                to SPORTS,
        "nba"                to SPORTS,
        "mlb"                to SPORTS,
        "nhl"                to SPORTS,
        "ufc"                to SPORTS,
        "boxing"             to SPORTS,
        "wrestling"          to SPORTS,
        "olympics"           to SPORTS,
        "esports"            to SPORTS,
        "fighting"           to SPORTS,
        "extreme sports"     to SPORTS,
        "outdoor"            to SPORTS,
        "sports hd"          to SPORTS,
        "live sports"        to SPORTS,

        // ── Movies & Series ────────────────────────────────────────────────
        "movies"             to MOVIES,
        "movie"              to MOVIES,
        "films"              to MOVIES,
        "cinema"             to MOVIES,
        "series"             to MOVIES,
        "drama"              to MOVIES,
        "thriller"           to MOVIES,
        "horror"             to MOVIES,
        "action"             to MOVIES,
        "comedy movies"      to MOVIES,
        "hollywood"          to MOVIES,
        "nollywood"          to MOVIES,
        "bollywood"          to MOVIES,
        "telenovelas"        to MOVIES,
        "novelas"            to MOVIES,
        "entertainment"      to MOVIES,  // many providers bundle movies under "entertainment"

        // ── Kids & Family ──────────────────────────────────────────────────
        "kids"               to KIDS,
        "children"           to KIDS,
        "kids & family"      to KIDS,
        "family"             to KIDS,
        "cartoons"           to KIDS,
        "animation"          to KIDS,
        "anime"              to KIDS,
        "preschool"          to KIDS,
        "junior"             to KIDS,

        // ── Music ──────────────────────────────────────────────────────────
        "music"              to MUSIC,
        "musik"              to MUSIC,
        "concerts"           to MUSIC,
        "hip hop"            to MUSIC,

        // ── Documentary ────────────────────────────────────────────────────
        "documentary"        to DOCUMENTARY,
        "discovery"          to DOCUMENTARY,
        "history"            to DOCUMENTARY,
        "nature"             to DOCUMENTARY,
        "wildlife"           to DOCUMENTARY,
        "science & nature"   to DOCUMENTARY,
        "science"            to DOCUMENTARY,

        // ── Religious ──────────────────────────────────────────────────────
        "religious"          to RELIGIOUS,
        "religion"           to RELIGIOUS,
        "faith"              to RELIGIOUS,
        "christian"          to RELIGIOUS,
        "islamic"            to RELIGIOUS,
        "gospel"             to RELIGIOUS,
        "spiritual"          to RELIGIOUS,
        "catholic"           to RELIGIOUS,
        "muslim"             to RELIGIOUS,
        "hindu"              to RELIGIOUS,

        // ── Lifestyle ──────────────────────────────────────────────────────
        "lifestyle"          to LIFESTYLE,
        "cooking"            to LIFESTYLE,
        "food"               to LIFESTYLE,
        "travel"             to LIFESTYLE,
        "fashion"            to LIFESTYLE,
        "beauty"             to LIFESTYLE,
        "health"             to LIFESTYLE,
        "fitness"            to LIFESTYLE,
        "home"               to LIFESTYLE,
        "gardening"          to LIFESTYLE,
        "diy"                to LIFESTYLE,

        // ── Comedy ─────────────────────────────────────────────────────────
        "comedy"             to COMEDY,
        "humor"              to COMEDY,
        "stand-up"           to COMEDY,
        "comedy movies"      to MOVIES,  // more specific wins

        // ── Science & Tech ─────────────────────────────────────────────────
        "science & tech"     to SCIENCE,
        "technology"         to SCIENCE,
        "tech"               to SCIENCE,
        "education"          to SCIENCE,
        "learning"           to SCIENCE,

        // ── Business ───────────────────────────────────────────────────────
        "business"           to BUSINESS,
        "finance"            to BUSINESS,
        "economy"            to BUSINESS,
        "market"             to BUSINESS,
        "investing"          to BUSINESS,

        // ── General / misc ─────────────────────────────────────────────────
        "general"            to GENERAL,
        "variety"            to GENERAL,
        "misc"               to GENERAL,
        "other"              to GENERAL,
    )

    /**
     * Normalise a raw provider category label to a canonical category.
     * Returns the canonical key when an alias exists, or `null` when no alias matches.
     */
    fun normaliseAlias(raw: String): String? {
        val key = raw.lowercase().trim()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[^a-z0-9 &/]"""), "")
            .trim()
        return ALIASES[key]
    }

    /**
     * Split on common multi-category separators and return the first recognised alias.
     */
    fun normaliseAliasWithSplit(raw: String): String? {
        for (part in raw.split(";", ",", "|", "/")) {
            normaliseAlias(part.trim())?.let { return it }
        }
        return null
    }

    /**
     * Whether [category] is considered "generic" (i.e. not meaningful enough to use as-is).
     */
    fun isGeneric(category: String): Boolean = category.equals(GENERAL, ignoreCase = true)
}
