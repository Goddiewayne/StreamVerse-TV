package com.streamverse.core.data.source

class AliasDictionary {
    private val canonicalToAliases = mutableMapOf<String, MutableSet<String>>()
    private val aliasToCanonical = mutableMapOf<String, String>()

    private val lowerToAlias = mutableMapOf<String, String>()

    init {
        addMapping("National Geographic", "Nat Geo", "National Geographic Channel", "Nat Geo TV", "NGC")
        addMapping("Al Jazeera English", "Al Jazeera", "AJ English", "Al Jazeera International")
        addMapping("Al Jazeera Arabic", "AJ Arabic", "الجزيرة")
        addMapping("France 24", "France24", "France 24 English", "France 24 Français", "F24")
        addMapping("CGTN", "CGTN News", "China Global Television Network", "CGTN English")
        addMapping("BBC World News", "BBC World", "BBC World News TV", "BBCWN")
        addMapping("BBC News", "BBC News 24", "BBC News Channel")
        addMapping("Sky News", "Sky News UK", "Sky News Live")
        addMapping("CNN International", "CNNI", "CNN World", "CNN Intl")
        addMapping("EuroNews", "Euronews", "Euro News")
        addMapping("RT News", "Russia Today", "RT", "RT International")
        addMapping("Deutsche Welle", "DW", "DW News", "Deutsche Welle English", "DW English")
        addMapping("NHK World", "NHK World Japan", "NHK World TV", "NHK World Premium")
        addMapping("Arirang TV", "Arirang", "Arirang World")
        addMapping("TRT World", "TRT", "TRT International")
        addMapping("Fox News", "FOX News", "Fox News Channel", "FNC")
        addMapping("Fox Sports", "FOX Sports", "FS1", "Fox Sports 1")
        addMapping("ESPN", "ESPN US", "ESPN America", "ESPN USA")
        addMapping("Discovery Channel", "Discovery", "Discovery TV")
        addMapping("Discovery Science", "Discovery Sci", "Science Channel")
        addMapping("History Channel", "History", "History TV", "HISTORY")
        addMapping("National Geographic Wild", "Nat Geo Wild", "NGC Wild", "Nat Geo WILD")
        addMapping("Cartoon Network", "Cartoon Network TV", "CN", "Boomerang")
        addMapping("Nickelodeon", "Nick", "Nick TV", "Nickelodeon TV")
        addMapping("MTV", "MTV TV", "Music Television")
        addMapping("BBC One", "BBC1", "BBC 1")
        addMapping("BBC Two", "BBC2", "BBC 2")
        addMapping("BBC Three", "BBC3", "BBC 3")
        addMapping("BBC Four", "BBC4", "BBC 4")
        addMapping("ITV", "ITV 1", "ITV1", "ITV Network")
        addMapping("Channel 4", "CH4", "C4", "Channel Four")
        addMapping("Channel 5", "CH5", "C5", "Five")
        addMapping("ABC America", "ABC US", "ABC Network", "ABC TV")
        addMapping("CBS", "CBS News", "CBS Network", "CBS TV")
        addMapping("NBC", "NBC TV", "NBC Network", "National Broadcasting Company")
        addMapping("PBS", "PBS TV", "Public Broadcasting Service")
        addMapping("CNN US", "CNN America", "CNN United States")
        addMapping("MSNBC", "MS NBC", "MSNBC TV")
        addMapping("CNBC", "CNBC TV", "CNBC World")
        addMapping("Bloomberg TV", "Bloomberg", "Bloomberg Television", "Bloomberg News")
        addMapping("Al Arabiya", "Al Arabiya News", "العربية", "Al Arabiya TV")
        addMapping("Sky Sports", "Sky Sports UK", "Sky Sports 1", "Sky Sports Main Event")
        addMapping("beIN Sports", "beIN", "Bein Sports", "beIN SPORTS")
        addMapping("Eurosport", "Eurosport 1", "Eurosport International")
        addMapping("TNT", "TNT TV", "Turner Network Television", "TNT Channel")
        addMapping("TBS", "TBS TV", "Turner Broadcasting System", "Superstation")
        addMapping("USA Network", "USA TV", "USA Network TV")
        addMapping("Syfy", "Sci Fi", "Sci-Fi Channel", "Syfy Channel")
        addMapping("Comedy Central", "Comedy Central TV", "CC")
        addMapping("HBO", "HBO TV", "Home Box Office", "HBO Channel")
        addMapping("Disney Channel", "Disney", "Disney TV", "The Disney Channel")
        addMapping("Disney Junior", "Disney Jr", "Disney Junior TV")
        addMapping("Disney XD", "Disney XD TV")
        addMapping("Nick Jr", "Nick Jr TV", "Nick Junior")
        addMapping("PBS Kids", "PBS Kids TV", "PBS KIDS")
        addMapping("CBeebies", "CBeebies TV", "BBC CBeebies")
        addMapping("CBBC", "CBBC Channel", "BBC CBBC")
        addMapping("TV5Monde", "TV5 Monde", "TV5", "TV5 Monde Info")
        addMapping("Rai 1", "Rai Uno", "RAI 1", "Rai 1 HD")
        addMapping("Rai 2", "Rai Due", "RAI 2", "Rai 2 HD")
        addMapping("Rai 3", "Rai Tre", "RAI 3", "Rai 3 HD")
        addMapping("TF1", "TF1 HD", "TF1 TV")
        addMapping("France 2", "France 2 HD", "F2")
        addMapping("France 3", "France 3 HD", "F3")
        addMapping("France 5", "France 5 HD", "F5")
        addMapping("M6", "M6 HD", "M6 TV", "Metropole 6")
        addMapping("Arte", "Arte TV", "ARTE", "Arte HD")
        addMapping("ZDF", "ZDF HD", "ZDF TV", "Zweites Deutsches Fernsehen")
        addMapping("Das Erste", "ARD", "ARD TV", "Erste", "Das Erste HD")
        addMapping("RTL", "RTL TV", "RTL Television", "RTL HD")
        addMapping("ProSieben", "Pro 7", "Pro7", "ProSieben HD")
        addMapping("SAT.1", "Sat 1", "SAT1", "SAT.1 HD")
        addMapping("RTL 2", "RTL II", "RTL2", "RTL 2 HD")
        addMapping("VOX", "VOX HD", "VOX TV")
        addMapping("Kabel Eins", "Kabel 1", "Kabel1", "Kabel Eins HD")
        addMapping("N24", "Welt", "Welt TV", "N24 Doku")
        addMapping("n-tv", "n tv", "ntv", "n-tv HD")
        addMapping("Tele 5", "Tele5", "Tele 5 HD")
        addMapping("Super RTL", "Toggo", "SuperRTL", "Super RTL HD")
        addMapping("ORF 1", "ORF1", "ORF 1 HD")
        addMapping("ORF 2", "ORF2", "ORF 2 HD")
        addMapping("SRF 1", "SRF1", "SF 1", "SRF 1 HD")
        addMapping("SRF 2", "SRF2", "SF 2", "SRF 2 HD")
        addMapping("RTS 1", "RTS Un", "RTS 1 HD")
        addMapping("RTS 2", "RTS Deux", "RTS 2 HD")
        addMapping("RSI LA 1", "LA 1", "RSI La 1", "RSI LA 1 HD")
        addMapping("RSI LA 2", "LA 2", "RSI La 2", "RSI LA 2 HD")
    }

    fun addMapping(canonical: String, vararg aliases: String) {
        val canonLower = canonical.lowercase().trim()
        val aliasSet = canonicalToAliases.getOrPut(canonLower) { mutableSetOf() }
        for (alias in aliases) {
            val aliasLower = alias.lowercase().trim()
            aliasSet.add(aliasLower)
            aliasToCanonical[aliasLower] = canonLower
            lowerToAlias[aliasLower] = alias
        }
    }

    fun resolve(name: String): String? {
        val lower = name.lowercase().trim()
        return aliasToCanonical[lower]
    }

    fun resolveWithDisplayName(name: String): Pair<String, String>? {
        val lower = name.lowercase().trim()
        val canonical = aliasToCanonical[lower] ?: return null
        val displayName = canonicalToAliases[canonical]?.firstOrNull { it.equals(lower, ignoreCase = true) }
            ?: lowerToAlias[lower]
            ?: name
        return canonical to displayName
    }

    fun allAliases(): Map<String, Set<String>> {
        val result = mutableMapOf<String, Set<String>>()
        for ((canonical, aliases) in canonicalToAliases) {
            result[canonical] = aliases.toSet()
        }
        return result
    }

    fun isAlias(name: String): Boolean = name.lowercase().trim() in aliasToCanonical
}
