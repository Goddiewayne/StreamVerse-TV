package com.streamverse.core.util

object ChannelNameFormatter {

    // Known acronyms / brand tokens that should always be rendered ALL-CAPS
    private val UPPER_TOKENS = setOf(
        "ABC", "NBC", "CBS", "CNN", "BBC", "ITV", "FOX", "PBS", "TBS", "TNT", "FX", "AMC",
        "HBO", "MTV", "VH1", "BET", "TLC", "ESPN", "ATP", "NFL", "NBA", "MLB", "NHL", "UFC",
        "WWE", "MBC", "OSN", "OSP", "TRT", "RAI", "ARD", "ZDF", "RTL", "NHK", "SBS", "KBS",
        "EBS", "CNA", "DW", "RTE", "SVT", "NRK", "YLE", "RTV", "ERT", "CCTV", "CGTN",
        "VTV", "OAN", "HLN", "C-SPAN", "CSPAN", "SABC", "DSTV", "MNET", "ENCA", "KTN",
        "NTV", "UBC", "NBS", "WBS", "AIT", "NTA", "TVC", "OGTV", "DRTV",
        "ITV", "GTV", "GBC", "JTV", "LTV", "RTV", "DTV", "MAX",
        "TBN", "CTN", "GOD", "LUX", "ONE", "EVE",
        "UK", "US", "USA", "UAE", "SA", "QA", "KW", "BH", "EU", "MX", "BR", "AR", "AU", "NZ",
        "HD", "FHD", "UHD", "4K", "SD", "TV", "FM", "AM", "IP", "VIP",
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
        "1HD", "2HD", "3HD",
    )

    // Brands with non-standard mixed-case — preserve exactly as listed (keyed by lowercase)
    private val BRAND_CASES = mapOf(
        "etv" to "eTV",
        "e!" to "e!",
        "ewtn" to "EWTN",
        "ifilm" to "iFilm",
        "itv" to "ITV",
        "bein" to "beIN",
    )

    // Small words kept lowercase unless they open the name or follow an opening paren
    private val LOWER_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "nor", "for", "yet", "so",
        "in", "on", "at", "by", "of", "to", "up", "as", "vs",
    )

    // Quality / resolution suffixes that belong in lower-case inside parens
    private val QUALITY_TOKENS = setOf("1080p", "720p", "480p", "360p", "4k", "hdr", "fhd", "sd")

    // Bracketed resolution / quality tags to strip from channel names, e.g. (360p) (720p)
    // (1080i) (1080p) (2160p) (480i) (HD) (FHD) (UHD) (4K) (SD) (HDR) — any (NNNp|NNNi) plus words.
    private val RES_PAREN = Regex(
        """\s*[\(\[]\s*(?:\d{3,4}[piPI]|4[Kk]|FHD|UHD|HDR|HD|SD)\s*[\)\]]\s*""",
        RegexOption.IGNORE_CASE,
    )

    // Decorative "other symbols" that pollute IPTV/FreeTV names but carry no info: Ⓢ (source tags),
    // ® ™ • ★ ☆ ♫ flags/emoji, circled letters/numbers, etc. (Unicode category So.) Kept OUT: math
    // symbols like + (Canal+) and punctuation like ! (E!).
    private val DECORATIVE_SYMBOLS = Regex("""[\p{So}️]+""")

    // Upstream playlist availability annotations that are implementation noise to viewers:
    // "[Not 24/7]", "(Not 24-7)", "[Geo-blocked]", "[Geo-Blocked]". Liveness is surfaced via the
    // LIVE badge instead of polluting the channel title.
    private val STATUS_TAG = Regex(
        """\s*[\(\[]\s*(?:not\s*24[/\- ]?7|geo[\- ]?blocked)\s*[\)\]]\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** Removes bracketed resolution/quality tags and decorative symbols, without otherwise altering
     *  the name. Safe for brand-cased names ("beIN Sports 1 (1080p)" → "beIN Sports 1",
     *  "MBC 2 Ⓢ" → "MBC 2", "ESPN ®" → "ESPN") so the same channel from different sources matches. */
    fun stripResolution(raw: String): String {
        if (raw.isBlank()) return raw
        val s = raw
            .replace(STATUS_TAG, " ")
            .replace(RES_PAREN, " ")
            .replace(DECORATIVE_SYMBOLS, " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return s.ifBlank { raw.trim() }
    }

    fun format(raw: String): String {
        if (raw.isBlank()) return raw
        val name = stripResolution(raw)

        // Tokenise keeping parentheses as their own "words" so we can detect first-after-paren
        val tokens = tokenize(name)
        val result = StringBuilder()
        var firstOfSegment = true  // true at start or just after '('

        for (token in tokens) {
            when {
                token == "(" || token == "[" -> {
                    result.append(token)
                    firstOfSegment = true
                }
                token == ")" || token == "]" -> {
                    result.append(token)
                    firstOfSegment = false
                }
                token == "-" || token == "–" || token == ":" -> {
                    result.append(token)
                    firstOfSegment = true
                }
                token.isBlank() -> result.append(token)
                else -> {
                    result.append(formatToken(token, firstOfSegment))
                    firstOfSegment = false
                }
            }
        }

        return result.toString().trim()
    }

    private fun formatToken(token: String, isFirst: Boolean): String {
        val lower = token.lowercase()
        val upper = token.uppercase()
        // Special brand casing (e.g. eTV, iFilm) — check before other rules
        BRAND_CASES[lower]?.let { return it }
        // Always-upper known tokens
        if (upper in UPPER_TOKENS) return upper
        // Quality suffixes inside parens stay lowercase
        if (lower in QUALITY_TOKENS) return lower
        // First word ≤4 letters → ALL CAPS (handles SABC, CNN, MBC, 16TV, 3ABN, etc.)
        if (isFirst && token.length <= 4) return upper
        // Pure digit / digit+letter like "2", "3AW" — keep as-is (non-first tokens)
        if (token.matches(Regex("[0-9]+[A-Za-z]*"))) return token
        // Small words lowercase (except at segment start)
        if (!isFirst && lower in LOWER_WORDS) return lower
        // Default: Title-case
        return lower.replaceFirstChar { it.uppercaseChar() }
    }

    private fun tokenize(s: String): List<String> {
        // Split while keeping delimiters as separate tokens
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in s) {
            if (ch == '(' || ch == ')' || ch == '[' || ch == ']') {
                if (buf.isNotEmpty()) { result.add(buf.toString()); buf.clear() }
                // removeAt(lastIndex), not removeLast(): the latter compiles to Java 21's
                // SequencedCollection.removeLast(), which crashes on Android < 35 (most Fire TVs).
                if (result.isNotEmpty() && result.last() == " ") result.removeAt(result.lastIndex)
                result.add(ch.toString())
                // absorb the space after '(' that we'll re-add as needed
            } else if (ch == ' ') {
                if (buf.isNotEmpty()) { result.add(buf.toString()); buf.clear() }
                result.add(" ")
            } else {
                buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) result.add(buf.toString())
        return result
    }
}
