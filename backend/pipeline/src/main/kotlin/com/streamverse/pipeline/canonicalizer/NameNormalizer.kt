package com.streamverse.pipeline.canonicalizer

import java.text.Normalizer

object NameNormalizer {
    private val RE_DIACRITICS = Regex("\\p{Mn}+")
    private val RE_RES_TAG = Regex(
        """[\(\[\{]\s*(?:\d{3,4}[pi]|4k|fhd|uhd|hdr|hd|sd|hevc|avc|h\.264|h\.265)\s*[\)\]\}]""",
        RegexOption.IGNORE_CASE,
    )
    private val RE_BRACKETED = Regex("""\s*\[.*?\]\s*""")
    private val RE_PAREN = Regex("""\s*\(.*?\)\s*""")
    private val RE_PUNCTUATION = Regex("""[\s\-–—_/&|,.:;!'"()\[\]{}«»„"“”‘’]+""")
    private val RE_MULTI_SPACE = Regex("\\s{2,}")
    private val RE_WHITESPACE = Regex("\\s+")
    private val RE_LETTER_DIGIT = Regex("(?<=[A-Za-z])(?=\\d)|(?<=\\d)(?=[A-Za-z])")
    private val RE_NON_ALNUM = Regex("[^a-z0-9]")
    private val RE_BRANDING = listOf(
        Regex("""\bhd\b""", RegexOption.IGNORE_CASE),
        Regex("""\btv\b""", RegexOption.IGNORE_CASE),
        Regex("""\bworld\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnetwork\b""", RegexOption.IGNORE_CASE),
        Regex("""\bchannel\b""", RegexOption.IGNORE_CASE),
        Regex("""\blive\b""", RegexOption.IGNORE_CASE),
        Regex("""\b24[\/]?7\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnews\b""", RegexOption.IGNORE_CASE),
    )
    private val RE_QUALITY_SUFFIX = Regex(
        """(?:\s*[-–—]\s*)?(?:fhd|uhd|hdr|hd|sd|4k|2160p|1080p|720p|480p|360p|hevc|avc)$""",
        RegexOption.IGNORE_CASE,
    )
    private val RE_RES_PARENS = Regex(
        """\s*\(?\s*(?:\d{3,4}[pi]|4k|fhd|uhd|hdr|hd|sd)\s*\)?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val RE_DECORATIVE = Regex("""[\p{So}️]+""")
    private val RE_STATUS_TAG = Regex(
        """\s*[\(\[]\s*(?:not\s*24[/\- ]?7|geo[\- ]?blocked)\s*[\)\]]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val RE_LANG_CODE = Regex("""\s+\p{Lu}{3}$""")

    fun hashKey(name: String, aliasDict: AliasDictionary): String {
        var s = name.trim()
        if (s.isEmpty()) return ""
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = s.replace(RE_DIACRITICS, "")
        s = s.lowercase().trim()
        s = s.replace(RE_STATUS_TAG, " ")
        s = s.replace(RE_RES_TAG, " ")
        s = s.replace(RE_BRACKETED, " ")
        s = s.replace(RE_PAREN, " ")
        s = s.replace(RE_PUNCTUATION, " ")
        aliasDict.resolve(s)?.let { s = it }
        for (re in RE_BRANDING) s = re.replace(s, " ")
        s = s.replace(RE_MULTI_SPACE, " ").trim()
        s = s.replace(RE_QUALITY_SUFFIX, "")
        s = s.replace(RE_RES_PARENS, "")
        s = s.replace(RE_DECORATIVE, " ")
        s = s.replace(RE_MULTI_SPACE, " ").trim()
        return s.replace(RE_NON_ALNUM, "")
    }

    fun cleanDisplayName(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw
            .replace(RE_STATUS_TAG, " ")
            .replace(RE_RES_TAG, " ")
            .replace(RE_BRACKETED, " ")
            .replace(RE_PAREN, " ")
            .replace(RE_DECORATIVE, " ")
            .replace(RE_PUNCTUATION, " ")
        for (re in RE_BRANDING) s = re.replace(s, " ")
        s = s.replace(RE_QUALITY_SUFFIX, "")
        s = s.replace(RE_RES_PARENS, "")
        s = s.replace(RE_LANG_CODE, "")
        s = s.replace(RE_MULTI_SPACE, " ").trim()
        return s
    }

    // ── Display‑formatting helpers (mirrors ChannelNameFormatter) ────────────
    private val UPPER_TOKENS = setOf(
        "ABC", "NBC", "CBS", "CNN", "BBC", "ITV", "FOX", "PBS", "TBS", "TNT", "FX", "AMC",
        "HBO", "MTV", "VH1", "BET", "TLC", "ESPN", "NFL", "NBA", "MLB", "NHL", "UFC",
        "MBC", "TRT", "RAI", "ARD", "ZDF", "RTL", "NHK", "SBS", "KBS",
        "CCTV", "CGTN", "SABC", "DSTV", "MNET",
        "USA", "UAE", "UK", "US",
        "HD", "FHD", "UHD", "4K", "SD", "TV", "FM", "AM",
    )
    private val BRAND_CASES = mapOf(
        "etv" to "eTV",
        "ewtn" to "EWTN",
        "ifilm" to "iFilm",
        "bein" to "beIN",
    )
    private val LOWER_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "for", "yet", "so",
        "in", "on", "at", "by", "of", "to", "up", "as", "vs",
    )

    /**
     * Apply title‑case formatting rules designed for TV display:
     * - Total length ≤ 4 → ALL CAPS  ("3ABN")
     * - First word ≤ 4 chars → ALL CAPS  ("16tv Budapest" → "16TV Budapest")
     * - Known acronyms → ALL CAPS  ("bbc world" → "BBC World")
     * - Brand exceptions → preserved  ("bein sports" → "beIN Sports")
     * - Small words → lowercase (non‑first)  ("cnn and fox" → "CNN and Fox")
     * - Default → Title Case  ("comedy central" → "Comedy Central")
     *
     * Because the hash‑key pipeline normalises everything to lowercase before matching,
     * this formatting is safe to apply to the display name without affecting dedup.
     */
    fun formatDisplayName(name: String): String {
        if (name.isBlank()) return name
        val trimmed = name.trim()
        if (trimmed.length <= 4) return trimmed.uppercase()

        val spaced = trimmed.replace(RE_LETTER_DIGIT, " ")
        val tokens = spaced.split(RE_WHITESPACE)
        return tokens.mapIndexed { i, token ->
            val lower = token.lowercase()
            val upper = token.uppercase()
            when {
                BRAND_CASES[lower] != null -> BRAND_CASES[lower]!!
                upper in UPPER_TOKENS -> upper
                i == 0 && token.length <= 4 -> upper
                i > 0 && lower in LOWER_WORDS -> lower
                else -> lower.replaceFirstChar { it.uppercaseChar() }
            }
        }.joinToString(" ")
    }
}
