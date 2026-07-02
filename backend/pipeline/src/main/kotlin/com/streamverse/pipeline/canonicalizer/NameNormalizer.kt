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
        s = s.replace(RE_MULTI_SPACE, " ").trim()
        return s
    }
}
