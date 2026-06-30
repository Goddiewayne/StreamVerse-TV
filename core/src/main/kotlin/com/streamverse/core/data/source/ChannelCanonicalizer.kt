package com.streamverse.core.data.source

data class CanonicalResult(
    val originalName: String,
    val canonicalName: String,
    val hashKey: String,
    val tokens: Set<String>,
    val resolvedAlias: Boolean,
)

object ChannelCanonicalizer {

    private val RE_DIACRITICS = Regex("\\p{Mn}+")
    private val RE_AD = Regex("""\s*\(?\s*(?:عربي|arabic|english|español|français|deutsch|italiano|português|türkçe|русский|中文|日本語|한국어)\s*\)?\s*""", RegexOption.IGNORE_CASE)
    private val RE_LANG_QUALIFIER = Regex("""\b(?:Arabic|English|French|German|Italian|Spanish|Portuguese|Turkish|Russian|Chinese|Japanese|Korean)\b""", RegexOption.IGNORE_CASE)
    private val RE_RES_TAG = Regex("""[\(\[\{]\s*(?:\d{3,4}[pi]|4k|fhd|uhd|hdr|hd|sd|hevc|avc|h\.264|h\.265)\s*[\)\]\}]""", RegexOption.IGNORE_CASE)
    private val RE_QUALITY_SUFFIX = Regex("""(?:\s*[-–—]\s*)?(?:fhd|uhd|hdr|hd|sd|4k|2160p|1080p|720p|480p|360p|hevc|avc)$""", RegexOption.IGNORE_CASE)
    private val RE_RESOLUTION_PARENS = Regex("""\s*\(?\s*(?:\d{3,4}[pi]|4k|fhd|uhd|hdr|hd|sd)\s*\)?\s*$""", RegexOption.IGNORE_CASE)
    private val RE_PUNCTUATION = Regex("""[\s\-–—_/&|,.:;!'"()\[\]{}«»„"“”‘’]+""")
    private val RE_MULTI_SPACE = Regex("\\s{2,}")
    private val RE_NON_ALNUM = Regex("[^a-z0-9\\s]")
    private val RE_NON_ALNUM_FOR_HASH = Regex("[^a-z0-9]")
    private val RE_BRANDING_HD = Regex("""\bhd\b""", RegexOption.IGNORE_CASE)
    private val RE_BRANDING_TV = Regex("""\btv\b""", RegexOption.IGNORE_CASE)
    private val RE_BRANDING_WORLD = Regex("""\bworld\b""", RegexOption.IGNORE_CASE)
    private val RE_BRANDING_NETWORK = Regex("""\bnetwork\b""", RegexOption.IGNORE_CASE)
    private val RE_BRANDING_CHANNEL = Regex("""\bchannel\b""", RegexOption.IGNORE_CASE)
    private val RE_BRANDING_LIVE = Regex("""\blive\b""", RegexOption.IGNORE_CASE)
    private val RE_BRANDING_24 = Regex("""\b24[\/]?7\b""", RegexOption.IGNORE_CASE)
    private val RE_BRANDING_NEWS = Regex("""\bnews\b""", RegexOption.IGNORE_CASE)
    private val RE_WORD_SPLIT = Regex("""[\s\-_./&]+""")
    private val RE_BRACKETED_TAG = Regex("""\s*\[.*?\]\s*""")
    private val RE_PAREN_TAG = Regex("""\s*\(.*?\)\s*""")

    private val LANGUAGE_KEYWORDS = setOf(
        "عربي", "arabic", "english", "español", "français", "deutsch", "italiano",
        "português", "türkçe", "русский", "中文", "日本語", "한국어",
    )

    private val COMMON_WORDS = setOf(
        "tv", "hd", "sd", "4k", "fhd", "uhd", "hdr", "the", "and", "for", "via",
        "channel", "network", "live", "stream", "news", "radio", "sport", "plus",
        "world", "international", "global", "online", "digital", "media", "tv",
        "entertainment", "television",
    )

    fun canonicalize(name: String, aliasDictionary: AliasDictionary): CanonicalResult {
        val originalName = name.trim()
        if (originalName.isEmpty()) return CanonicalResult("", "", "", emptySet(), false)

        var s = originalName

        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        s = s.replace(RE_DIACRITICS, "")

        s = s.lowercase().trim()

        s = s.replace(RE_AD, " ")

        s = s.replace(RE_RES_TAG, " ")

        s = s.replace(RE_BRACKETED_TAG, " ")

        s = s.replace(RE_PAREN_TAG, " ")

        s = s.replace(RE_PUNCTUATION, " ")

        var aliasResolved = false
        val aliasResult = aliasDictionary.resolveWithOriginalName(s)
        if (aliasResult != null) {
            s = aliasResult.first
            aliasResolved = true
        }

        s = RE_BRANDING_HD.replace(s, " ")
        s = RE_BRANDING_TV.replace(s, " ")
        s = RE_BRANDING_WORLD.replace(s, " ")
        s = RE_BRANDING_NETWORK.replace(s, " ")
        s = RE_BRANDING_CHANNEL.replace(s, " ")
        s = RE_BRANDING_LIVE.replace(s, " ")
        s = RE_BRANDING_24.replace(s, " ")
        s = RE_BRANDING_NEWS.replace(s, " ")

        s = s.replace(RE_MULTI_SPACE, " ").trim()

        s = s.replace(RE_QUALITY_SUFFIX, "")

        s = s.replace(RE_RESOLUTION_PARENS, "")

        s = s.replace(RE_MULTI_SPACE, " ").trim()

        val hashKey = s.replace(RE_NON_ALNUM_FOR_HASH, "")

        val tokens = s.split(RE_WORD_SPLIT)
            .filter { it.length >= 2 && it !in COMMON_WORDS && !LANGUAGE_KEYWORDS.contains(it) }
            .toSet()

        return CanonicalResult(
            originalName = originalName,
            canonicalName = s,
            hashKey = hashKey,
            tokens = tokens,
            resolvedAlias = aliasResolved,
        )
    }

    private fun AliasDictionary.resolveWithOriginalName(name: String): Pair<String, String>? {
        val lower = name.lowercase().trim()
        val resolvedCanonical = resolve(lower) ?: return null
        val displayKey = allAliases()[resolvedCanonical]?.firstOrNull { it.equals(lower, ignoreCase = true) }
        return resolvedCanonical to (displayKey ?: lower)
    }
}
