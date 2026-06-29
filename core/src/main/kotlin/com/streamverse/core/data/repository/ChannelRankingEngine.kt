package com.streamverse.core.data.repository

import com.streamverse.core.data.source.provider.ProviderRegistry
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.CategoryNormalizer
import javax.inject.Inject
import javax.inject.Singleton

data class RankingContext(
    val userRegion: String?,
    val recentlyWatchedIds: Set<String> = emptySet(),
    val favoriteIds: Set<String> = emptySet(),
)

@Singleton
class ChannelRankingEngine @Inject constructor(
    private val providerRegistry: ProviderRegistry,
) {

    fun rankChannel(channel: Channel, context: RankingContext): Float {
        var s = 0f
        s += brandScore(channel)
        s += sourceCoverageScore(channel)
        s += sourcePriorityScore(channel)
        s += qualityScore(channel)
        s += categoryScore(channel)
        s += metadataScore(channel)
        s += regionalScore(channel, context)
        s += reliabilityScore(channel)
        s += engagementScore(channel, context)
        return s
    }

    fun topByCategory(channels: List<Channel>, category: String, limit: Int, context: RankingContext): List<Channel> {
        return channels.filter { it.category == category }
            .sortedByDescending { rankChannel(it, context) }
            .take(limit)
    }

    fun globallyPopular(channels: List<Channel>, limit: Int, context: RankingContext): List<Channel> {
        return channels.sortedByDescending { rankChannel(it, context) }.take(limit)
    }

    fun topFeatured(channels: List<Channel>, limit: Int, context: RankingContext, liveIds: Set<String>): List<Channel> {
        return channels.filter { it.id in liveIds && !it.logoUrl.isNullOrBlank() }
            .sortedByDescending { rankChannel(it, context) }
            .take(limit)
    }

    fun editorialPicks(channels: List<Channel>, limit: Int, context: RankingContext): List<Channel> {
        val editorial = channels.filter { it.category in EDITORIAL_CATEGORIES }
        val ranked = editorial.sortedByDescending { rankChannel(it, context) }
        val picks = mutableListOf<Channel>()
        val seenCategories = mutableSetOf<String>()
        for (round in 0 until 3) {
            for (ch in ranked) {
                if (picks.size >= limit) break
                val cat = ch.category ?: "Other"
                if (round == 0 && cat in seenCategories) continue
                if (ch !in picks) {
                    picks.add(ch)
                    seenCategories.add(cat)
                }
            }
            if (picks.size >= limit) break
        }
        return picks
    }

    fun popularInRegion(channels: List<Channel>, limit: Int, context: RankingContext): List<Channel> {
        if (context.userRegion.isNullOrBlank()) return emptyList()
        return channels.filter { it.country.equals(context.userRegion, ignoreCase = true) }
            .sortedByDescending { rankChannel(it, context) }
            .take(limit)
    }

    // ── Signal scores ──────────────────────────────────────────────

    companion object {
        const val MAX_BRAND = 30f
        const val MAX_SOURCE_COVERAGE = 20f
        const val MAX_SOURCE_PRIORITY = 15f
        const val MAX_QUALITY = 10f
        const val MAX_CATEGORY = 10f
        const val MAX_METADATA = 5f
        const val MAX_REGIONAL = 10f
        const val MAX_RELIABILITY = 5f
        const val MAX_ENGAGEMENT = 10f
        const val MAX_TOTAL = MAX_BRAND + MAX_SOURCE_COVERAGE + MAX_SOURCE_PRIORITY +
                MAX_QUALITY + MAX_CATEGORY + MAX_METADATA + MAX_REGIONAL +
                MAX_RELIABILITY + MAX_ENGAGEMENT

        private val CATEGORY_WEIGHTS = mapOf(
            CategoryNormalizer.C.NEWS to 10f,
            CategoryNormalizer.C.SPORTS to 10f,
            CategoryNormalizer.C.ENTERTAINMENT to 9f,
            CategoryNormalizer.C.MOVIES to 8f,
            CategoryNormalizer.C.DOCUMENTARY to 7f,
            CategoryNormalizer.C.KIDS to 6f,
            CategoryNormalizer.C.MUSIC to 5f,
            CategoryNormalizer.C.SCIENCE to 4f,
            CategoryNormalizer.C.BUSINESS to 4f,
            CategoryNormalizer.C.LIFESTYLE to 3f,
            CategoryNormalizer.C.COMEDY to 3f,
            CategoryNormalizer.C.GENERAL to 2f,
            CategoryNormalizer.C.RELIGIOUS to 2f,
        )

        private val EXACT_TIER_1_NAMES = setOf(
            "cnn", "bbc", "bbc world news", "bbc news", "bbc one", "bbc two",
            "bbc three", "bbc four", "espn", "eurosport", "hbo", "hbo 2",
            "discovery channel", "nat geo", "national geographic",
            "fox news", "sky news", "al jazeera", "mtv",
            "cartoon network", "nickelodeon", "disney channel",
            "bloomberg", "cnbc", "msnbc", "france 24", "euronews",
            "history", "history channel", "animal planet",
            "hgtv", "food network", "tlc", "comedy central",
            "tnt", "tbs", "syfy", "bravo",
        )

        private val TIER_1_PREFIXES = listOf(
            "bbc ", "espn ", "hbo ", "discovery ", "nat geo ",
            "sky ", "al jazeera ", "cartoon network ", "nickelodeon ",
            "mtv ", "disney ", "eurosport ", "bloomberg ", "cnbc ",
            "msnbc ", "france 24 ", "euronews ", "cctv ",
            "pbs ", "itv",
        )

        private val TIER_1_INFIX = listOf(
            "bbc", "cnn", "espn", "hbo", "nat geo", "national geographic",
            "discovery channel", "fox news", "sky news", "al jazeera",
            "cartoon network", "nickelodeon", "disney channel",
            "history channel", "animal planet", "food network",
            "comedy central", "bloomberg", "cnbc", "msnbc",
            "france 24", "euronews", "euronews", "tlc",
        )

        private val TIER_2_NAMES = setOf(
            "abc news", "nbc news", "cbs news", "fox", "fox sports",
            "rte", "tg4", "virgin media", "talksport",
            "zdf", "ard", "rtl", "sat.1", "prosieben",
            "tf1", "france 2", "france 3", "france 5", "m6", "arte",
            "rai 1", "rai 2", "rai 3", "rai sport", "rai news",
            "mediaset", "canale 5", "italia 1", "rete 4", "la7",
            "tve", "antena 3", "telecinco", "la sexta", "cuatro",
            "rtp", "sic", "tvi",
            "globo", "record tv", "band tv", "sbt",
            "network 10", "channel 9", "channel 7", "sbs",
            "tvnz", "cbc", "cbc news", "ctv", "ctv news",
            "global tv", "citytv",
            "geo news", "ary", "hum tv",
            "star plus", "star gold", "star sports",
            "sony tv", "sony max", "sony sab",
            "zee tv", "zee cinema", "zee news",
            "colors tv", "ndtv", "times now",
            "channel newsasia", "mediacorp",
            "abs-cbn", "gma network",
            "telemundo", "univision",
            "caracol", "rcn tv",
            "telefe",
            "nba tv", "nfl network", "golf channel",
            "premier sports", "viaplay",
            "euronews",
            "wwe network",
        )

        private val TIER_2_PREFIXES = listOf(
            "abc ", "nbc ", "cbs ", "fox ", "fox sports ",
            "rte ", "zdf ", "ard ", "rtl ", "sat.1 ",
            "tf1 ", "arte ", "rai ", "globo ",
            "star ", "sony ", "zee ", "colors ",
        )

        private val TIER_3_NAMES = setOf(
            "news18", "republic tv", "india today", "wion",
            "sports 18", "ten sports", "sony sports",
            "discovery plus", "investigation discovery", "discovery science",
            "discovery turbo", "discovery world", "dmax",
            "boomerang", "pogo", "sony yay", "hungama",
            "&tv", "&flix", "&privehd",
            "utv", "b4u", "shemaroo",
            "multishow", "gloob", "futura", "canal brasil",
            "megapix", "woohoo", "combate",
            "band news", "band sport",
            "teleantioquia", "telepacifico", "telecaribe",
            "azteca 7", "azteca uno",
            "nhk world", "now tv",
            "beinsports", "bein sports",
            "dazn", "dazn sports",
            "elite sport",
            "fox sports", "fox deportes",
            "tyc sports",
            "sportsnet", "sportsnet one",
        )

        private val TIER_3_PREFIXES = listOf(
            "news18 ", "republic tv ", "india today ", "wion ",
            "sports 18 ", "ten sports ", "sony sports ",
            "discovery ", "dmax", "boomerang ", "pogo ",
            "utv ", "b4u ", "shemaroo ",
            "multishow ", "gloob ", "futura ",
            "band ", "azteca ", "nhk world ",
        )

        val DISPLAY_CATEGORY_ORDER = listOf(
            CategoryNormalizer.C.NEWS,
            CategoryNormalizer.C.SPORTS,
            CategoryNormalizer.C.ENTERTAINMENT,
            CategoryNormalizer.C.MOVIES,
            CategoryNormalizer.C.DOCUMENTARY,
            CategoryNormalizer.C.KIDS,
            CategoryNormalizer.C.MUSIC,
            CategoryNormalizer.C.SCIENCE,
            CategoryNormalizer.C.BUSINESS,
            CategoryNormalizer.C.LIFESTYLE,
            CategoryNormalizer.C.COMEDY,
            CategoryNormalizer.C.GENERAL,
            CategoryNormalizer.C.RELIGIOUS,
        )

        private val EDITORIAL_CATEGORIES = setOf(
            CategoryNormalizer.C.NEWS,
            CategoryNormalizer.C.SPORTS,
            CategoryNormalizer.C.ENTERTAINMENT,
            CategoryNormalizer.C.MOVIES,
            CategoryNormalizer.C.DOCUMENTARY,
            CategoryNormalizer.C.KIDS,
        )
    }

    private fun brandScore(channel: Channel): Float {
        val name = channel.displayName.lowercase().trim()
        if (name in EXACT_TIER_1_NAMES) return MAX_BRAND
        if (TIER_1_PREFIXES.any { name.startsWith(it) }) return MAX_BRAND
        if (TIER_1_INFIX.any { name.contains(it) }) return MAX_BRAND
        if (name in TIER_2_NAMES) return MAX_BRAND * 0.6f
        if (TIER_2_PREFIXES.any { name.startsWith(it) }) return MAX_BRAND * 0.6f
        if (name in TIER_3_NAMES) return MAX_BRAND * 0.3f
        if (TIER_3_PREFIXES.any { name.startsWith(it) }) return MAX_BRAND * 0.3f
        return 0f
    }

    private fun sourceCoverageScore(channel: Channel): Float {
        return minOf(channel.sources.size.toFloat(), 10f) / 10f * MAX_SOURCE_COVERAGE
    }

    private fun sourcePriorityScore(channel: Channel): Float {
        val sorted = providerRegistry.prioritySorted()
        var best = Int.MAX_VALUE
        for (st in channel.sources.keys) {
            val idx = sorted.indexOf(SourceType.canonicalOf(st))
            if (idx >= 0 && idx < best) best = idx
        }
        if (best == Int.MAX_VALUE) return 0f
        val maxIdx = sorted.size - 1
        return (maxIdx - best).toFloat() / maxIdx * MAX_SOURCE_PRIORITY
    }

    private fun qualityScore(channel: Channel): Float {
        return when (channel.quality) {
            Quality._4K -> MAX_QUALITY
            Quality.FHD -> MAX_QUALITY * 0.8f
            Quality.HD -> MAX_QUALITY * 0.5f
            Quality.SD -> MAX_QUALITY * 0.2f
            null -> MAX_QUALITY * 0.3f
        }
    }

    private fun categoryScore(channel: Channel): Float {
        val cat = channel.category ?: return 0f
        return CATEGORY_WEIGHTS[cat] ?: 0f
    }

    private fun metadataScore(channel: Channel): Float {
        var s = 0f
        if (!channel.logoUrl.isNullOrBlank()) s += 2f
        if (!channel.description.isNullOrBlank()) s += 1.5f
        if (!channel.language.isNullOrBlank()) s += 1f
        if (!channel.tvgId.isNullOrBlank()) s += 0.5f
        return minOf(s, MAX_METADATA)
    }

    private fun regionalScore(channel: Channel, context: RankingContext): Float {
        if (context.userRegion.isNullOrBlank()) {
            return if (channel.country.isNullOrBlank()) 3f else 2f
        }
        val cc = channel.country ?: ""
        if (cc.isBlank()) return MAX_REGIONAL * 0.5f
        if (cc.equals(context.userRegion, ignoreCase = true)) return MAX_REGIONAL
        return 0f
    }

    private fun reliabilityScore(channel: Channel): Float {
        if (channel.sources.isEmpty()) return 0f
        var total = 0f
        for ((_, info) in channel.sources) {
            total += info.reliabilityScore
        }
        return (total / channel.sources.size) * MAX_RELIABILITY
    }

    private fun engagementScore(channel: Channel, context: RankingContext): Float {
        var s = 0f
        if (channel.id in context.recentlyWatchedIds) s += 5f
        if (channel.id in context.favoriteIds) s += 5f
        return s
    }
}
