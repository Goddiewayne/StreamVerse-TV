package com.streamverse.tv.ui.browse

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.FavoritesRepository
import com.streamverse.core.data.repository.ProgrammeRepository
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.DayPeriod
import com.streamverse.core.domain.model.SortMode
import com.streamverse.core.domain.model.TimeOfDay
import com.streamverse.core.util.CategoryNormalizer
import com.streamverse.core.util.StreamPreResolver
import com.streamverse.tv.ui.playback.TVPlaybackActivity
import com.streamverse.tv.ui.search.TVRegionSearchActivity
import com.streamverse.tv.ui.search.TVSearchActivity
import com.streamverse.tv.ui.settings.TVSettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class TVBrowseFragment : BrowseSupportFragment() {

    @Inject lateinit var channelRepository: ChannelRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository
    @Inject lateinit var streamPreResolver: StreamPreResolver
    @Inject lateinit var channelHealthEngine: ChannelHealthEngine
    @Inject lateinit var programmeRepo: ProgrammeRepository

    private var liveIds: Set<String> = emptySet()

    private val preResolveHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val rowsAdapter = ArrayObjectAdapter(
        ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE)
    )

    private var activeBillboard: TVBillboardView? = null
    private var loadingJob: Job? = null
    private var favoriteIds: Set<String> = emptySet()
    private val _sortMode = MutableStateFlow(SortMode.CATEGORY)
    private var currentTimeOfDay = TimeOfDay(DayPeriod.EVENING, "Evening", 0xFF1E90FF)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        title = "StreamVerse TV"
        setBrandColor(Color.parseColor("#0F172A"))
        setSearchAffordanceColor(Color.parseColor("#22D3EE"))

        adapter = rowsAdapter

        setOnSearchClickedListener {
            startActivity(android.content.Intent(requireContext(), TVSearchActivity::class.java))
        }

        setOnItemViewClickedListener { itemViewHolder, item, _, _ ->
            if (item == SETTINGS_SENTINEL) {
                startActivity(android.content.Intent(requireContext(), TVSettingsActivity::class.java))
                return@setOnItemViewClickedListener
            }
            if (item == REGION_SENTINEL) {
                startActivity(android.content.Intent(requireContext(), TVRegionSearchActivity::class.java))
                return@setOnItemViewClickedListener
            }
            if (item is SortMode) {
                val modes = SortMode.entries
                val next = modes[(modes.indexOf(item) + 1) % modes.size]
                _sortMode.value = next
                prefs().edit().putString(KEY_SORT, next.name).apply()
                return@setOnItemViewClickedListener
            }

            val ch = when {
                item is Channel -> item
                item is List<*> ->
                    (itemViewHolder as? TVBillboardPresenter.BillboardHolder)?.billboard?.currentChannel
                        ?: activeBillboard?.currentChannel
                else -> null
            }
            ch?.let { launchChannel(it) }
        }

        setOnItemViewSelectedListener { itemVH, item, _, _ ->
            if (item is Channel) title = item.displayName
            if (item is List<*>) title = "StreamVerse TV"
            if (item is List<*> && itemVH?.view is TVBillboardView) {
                activeBillboard = itemVH.view as TVBillboardView
            }
            if (item is Channel) {
                preResolveHandler.removeCallbacksAndMessages(null)
                preResolveHandler.postDelayed({ streamPreResolver.preResolve(item) }, 300)
            }
        }

        _sortMode.value = prefs().getString(KEY_SORT, null)
            ?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.CATEGORY

        showLoadingPlaceholders()

        loadingJob?.cancel()
        loadingJob = lifecycleScope.launch {
            combine(
                channelRepository.channels,
                channelRepository.radioChannels,
                _sortMode,
            ) { chs, radios, sortMode ->
                Triple(chs, radios, sortMode)
            }.collect { (chs, radios, sortMode) ->
                if (chs.isNotEmpty() || radios.isNotEmpty()) populateRows(chs, radios, sortMode)
            }
        }

        lifecycleScope.launch {
            favoritesRepository.getAllFavoriteIds().collect { favs ->
                favoriteIds = favs.toSet()
                refreshFavoriteHearts()
            }
        }

        lifecycleScope.launch {
            channelHealthEngine.liveChannelIds.collect { ids ->
                liveIds = ids
                refreshFavoriteHearts()
            }
        }

        lifecycleScope.launch {
            withContext(NonCancellable) {
                channelRepository.load()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        preResolveHandler.removeCallbacksAndMessages(null)
        activeBillboard = null
        title = "StreamVerse TV"
    }

    private var showingPlaceholders = false

    private fun showLoadingPlaceholders() {
        rowsAdapter.clear()
        val titles = listOf("Featured", "On Now", "All Channels", "Movies & Series")
        titles.forEachIndexed { rowIndex, title ->
            val adapter = ArrayObjectAdapter(TVPlaceholderPresenter())
            repeat(8) { adapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex.toLong(), title), adapter))
        }
        showingPlaceholders = true
    }

    private fun populateRows(all: List<Channel>, radioChannels: List<Channel>, sortMode: SortMode = SortMode.CATEGORY) {
        rowsAdapter.clear()
        showingPlaceholders = false
        var rowIndex = 0L

        val recentChannels = loadHistory(all)
        val recentIds = recentChannels.map { it.id }.toSet()

        programmeRepo.refreshTimeOfDay()
        currentTimeOfDay = programmeRepo.timeOfDay.value

        channelHealthEngine.verify(recentChannels, deep = true)
        channelHealthEngine.verify(all.filter { it.id in favoriteIds }, deep = true)
        channelHealthEngine.verify(all.take(120), deep = false)

        val presenter = TVChannelPresenter(
            recentIds = recentIds,
            isFavorite = { ch -> ch.id in favoriteIds },
            onToggleFavorite = { ch -> toggleFavorite(ch) },
            isLive = { ch -> ch.id in liveIds },
        )

        // 1. Sort Mode
        val sortAdapter = ArrayObjectAdapter(TVSettingsCardPresenter(
            mainText = "Sort: ${sortMode.displayName}",
            subText = "Tap to cycle",
        ))
        sortAdapter.add(sortMode)
        rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Sort"), sortAdapter))

        // 2. Billboard
        val featuredChannels = selectFeatured(all, limit = 10)
        if (featuredChannels.isNotEmpty()) {
            val billboardAdapter = ArrayObjectAdapter(TVBillboardPresenter { launchChannel(it) })
            billboardAdapter.add(featuredChannels)
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Featured"), billboardAdapter))
        }

        // 3. What's On Now
        val onNow = selectOnNow(all, limit = 12)
        channelHealthEngine.verify(featuredChannels + onNow, deep = true)
        if (onNow.isNotEmpty()) {
            val onNowAdapter = ArrayObjectAdapter(TVOnNowPresenter())
            onNow.forEach { onNowAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "On Now"), onNowAdapter))
        }

        // 4. Live Events
        programmeRepo.updateLiveEvents(all)
        val liveEvents = programmeRepo.liveEvents.value
        if (liveEvents.isNotEmpty()) {
            val eventChs = liveEvents.flatMap { it.channelIds }
                .mapNotNull { id -> all.find { it.id == id } }
                .filter { !it.logoUrl.isNullOrBlank() }
            if (eventChs.isNotEmpty()) {
                val eventAdapter = ArrayObjectAdapter(TVOnNowPresenter())
                eventChs.take(8).forEach { eventAdapter.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Live Events"), eventAdapter))
            }
        }

        // 5. Continue Watching
        if (recentChannels.isNotEmpty()) {
            val continuePresenter = TVChannelPresenter(
                recentIds = recentIds,
                isFavorite = { ch -> ch.id in favoriteIds },
                onToggleFavorite = { ch -> toggleFavorite(ch) },
                isLive = { ch -> ch.id in liveIds },
            )
            val adapter = ArrayObjectAdapter(continuePresenter)
            recentChannels.forEach { adapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Continue Watching"), adapter))
        }

        // 6. News Centre
        val newsChs = all.filter { it.category == CategoryNormalizer.C.NEWS }
        if (newsChs.isNotEmpty()) {
            val newsAdapter = ArrayObjectAdapter(TVOnNowPresenter())
            newsChs.take(8).forEach { newsAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "News Centre"), newsAdapter))
        }

        // 7. Sports Centre
        programmeRepo.updateLiveScores(all)
        val sportsChs = all.filter { it.category == CategoryNormalizer.C.SPORTS }
        if (sportsChs.isNotEmpty()) {
            val sportsAdapter = ArrayObjectAdapter(TVOnNowPresenter())
            sportsChs.take(8).forEach { sportsAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Sports Centre"), sportsAdapter))
        }

        // 8. Trending
        programmeRepo.updateTrending(all)
        val trending = programmeRepo.trending.value.map { it.channel }.filter { !it.logoUrl.isNullOrBlank() }
        if (trending.isNotEmpty()) {
            val trendingAdapter = ArrayObjectAdapter(TVOnNowPresenter())
            trending.take(8).forEach { trendingAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Trending Live"), trendingAdapter))
        }

        // 9. Kids
        val kidsChs = all.filter { it.category == CategoryNormalizer.C.KIDS }
        if (kidsChs.isNotEmpty()) {
            val kidsAdapter = ArrayObjectAdapter(presenter)
            kidsChs.take(20).forEach { kidsAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Kids"), kidsAdapter))
        }

        // 10. Music
        val musicChs = all.filter { it.category == CategoryNormalizer.C.MUSIC }
        if (musicChs.isNotEmpty()) {
            val musicAdapter = ArrayObjectAdapter(presenter)
            musicChs.take(20).forEach { musicAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Music"), musicAdapter))
        }

        // 11. Recommendations
        val recs = programmeRepo.getRecommendedChannels(
            all, recentChannels,
            all.mapNotNull { it.category }.toSet(),
            currentTimeOfDay.period,
        )
        if (recs.isNotEmpty()) {
            val recsAdapter = ArrayObjectAdapter(TVOnNowPresenter())
            recs.take(8).forEach { recsAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Recommended For You"), recsAdapter))
        }

        // 12. My Favourites
        val favorites = all.filter { it.id in favoriteIds }
        if (favorites.isNotEmpty()) {
            val favPresenter = TVChannelPresenter(
                isFavorite = { ch -> ch.id in favoriteIds },
                onToggleFavorite = { ch -> toggleFavorite(ch) },
                isLive = { ch -> ch.id in liveIds },
            )
            val adapter = ArrayObjectAdapter(favPresenter)
            favorites.forEach { adapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "My Favourites"), adapter))
        }

        when (sortMode) {
            SortMode.CATEGORY -> {
                val categories = all.mapNotNull { it.category }.distinct()
                    .filter { it != CategoryNormalizer.C.RADIO }.sorted()
                categories.forEach { category ->
                    val catChannels = all.filter { it.category == category }
                    if (catChannels.isNotEmpty()) {
                        val adapter = ArrayObjectAdapter(presenter)
                        catChannels.take(40).forEach { adapter.add(it) }
                        rowsAdapter.add(
                            ListRow(HeaderItem(rowIndex++, "${categoryEmoji(category)}  $category"), adapter)
                        )
                    }
                }
            }
            SortMode.ALPHABETICAL -> {
                val sorted = all.sortedBy { it.displayName.lowercase() }
                val digitCh = sorted.filter { ch ->
                    val first = ch.displayName.lowercase().firstOrNull()
                    first != null && first in '0'..'9'
                }
                if (digitCh.isNotEmpty()) {
                    val adapter = ArrayObjectAdapter(presenter)
                    digitCh.take(40).forEach { adapter.add(it) }
                    rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "0-9"), adapter))
                }
                for (letter in 'A'..'Z') {
                    val lc = letter.lowercase()[0]
                    val letterCh = sorted.filter { ch ->
                        val first = ch.displayName.lowercase().firstOrNull()
                        first != null && first == lc
                    }
                    if (letterCh.isNotEmpty()) {
                        val adapter = ArrayObjectAdapter(presenter)
                        letterCh.take(40).forEach { adapter.add(it) }
                        rowsAdapter.add(ListRow(HeaderItem(rowIndex++, letter.toString()), adapter))
                    }
                }
            }
            SortMode.REGION -> {
                val byRegion = all.filterNot { it.country.isNullOrBlank() }
                    .groupBy { it.country!! }.entries.sortedBy { it.key }
                for ((region, regionChs) in byRegion) {
                    val adapter = ArrayObjectAdapter(presenter)
                    regionChs.take(40).forEach { adapter.add(it) }
                    rowsAdapter.add(ListRow(HeaderItem(rowIndex++, region), adapter))
                }
                val noRegion = all.filter { it.country.isNullOrBlank() }
                if (noRegion.isNotEmpty()) {
                    val adapter = ArrayObjectAdapter(presenter)
                    noRegion.take(20).forEach { adapter.add(it) }
                    rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Other"), adapter))
                }
            }
        }

        // Radio
        if (radioChannels.isNotEmpty()) {
            val radioPresenter = TVChannelPresenter(
                isFavorite = { ch -> ch.id in favoriteIds },
                onToggleFavorite = { ch -> toggleFavorite(ch) },
                isLive = { ch -> ch.id in liveIds },
            )
            val radioAdapter = ArrayObjectAdapter(radioPresenter)
            radioChannels.take(40).forEach { radioAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Radio"), radioAdapter))
        }

        // Settings
        val settingsAdapter = ArrayObjectAdapter(TVSettingsCardPresenter())
        settingsAdapter.add(SETTINGS_SENTINEL)
        rowsAdapter.add(ListRow(HeaderItem(rowIndex, "Settings"), settingsAdapter))
    }

    private fun toggleFavorite(channel: Channel) {
        lifecycleScope.launch {
            if (channel.id in favoriteIds) {
                favoritesRepository.removeFavorite(channel.id)
            } else {
                favoritesRepository.addFavorite(channel)
            }
        }
    }

    private fun launchChannel(ch: Channel) {
        saveToHistory(ch.id)
        startActivity(
            android.content.Intent(requireContext(), TVPlaybackActivity::class.java)
                .putExtra(TVPlaybackActivity.EXTRA_CHANNEL_ID, ch.id),
        )
    }

    private fun selectFeatured(all: List<Channel>, limit: Int): List<Channel> {
        val withLogo = all.filter { !it.logoUrl.isNullOrBlank() }
        val premium = withLogo.filter {
            it.quality == com.streamverse.core.domain.model.Quality._4K ||
            it.quality == com.streamverse.core.domain.model.Quality.FHD ||
            it.quality == com.streamverse.core.domain.model.Quality.HD
        }
        val fallback = if (withLogo.size < 3) {
            all.filter { it.logoUrl.isNullOrBlank() }
                .sortedByDescending { it.quality?.ordinal ?: 0 }
        } else emptyList()
        return (premium + withLogo + fallback).distinctBy { it.id }.take(limit)
    }

    private fun selectOnNow(all: List<Channel>, limit: Int): List<Channel> {
        val withLogo = all.filter { !it.logoUrl.isNullOrBlank() }
        if (withLogo.isEmpty()) return emptyList()

        fun isLive(c: Channel) = c.category?.let { cat ->
            listOf("sport", "news", "entertain").any { cat.contains(it, ignoreCase = true) }
        } ?: false

        val dayOffset = (System.currentTimeMillis() / 86_400_000L).toInt()
        val pool = (withLogo.filter { isLive(it) } + withLogo).distinctBy { it.id }
        val rotated = if (pool.size > limit) {
            val start = (dayOffset % pool.size + pool.size) % pool.size
            (pool.drop(start) + pool.take(start))
        } else pool

        val seenCategories = LinkedHashSet<String>()
        val spread = mutableListOf<Channel>()
        for (ch in rotated) {
            val cat = ch.category ?: "-"
            if (seenCategories.add(cat)) spread.add(ch)
            if (spread.size >= limit) break
        }
        if (spread.size < limit) {
            for (ch in rotated) {
                if (ch !in spread) spread.add(ch)
                if (spread.size >= limit) break
            }
        }
        return spread.take(limit)
    }

    private fun prefs() = requireContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveToHistory(channelId: String) {
        val existing = prefs().getString(KEY_RECENT, "")!!
            .split(",").filter { it.isNotBlank() }
        val updated = (listOf(channelId) + existing.filter { it != channelId }).take(MAX_HISTORY)
        prefs().edit().putString(KEY_RECENT, updated.joinToString(",")).apply()
    }

    private fun loadHistory(all: List<Channel>): List<Channel> {
        val ids = prefs().getString(KEY_RECENT, "")!!.split(",").filter { it.isNotBlank() }
        val byId = all.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private fun categoryEmoji(cat: String) = when {
        cat.contains("sport", ignoreCase = true)                       -> "\u26BD"
        cat.contains("news", ignoreCase = true)                        -> "\uD83D\uDCF0"
        cat.contains("movie", ignoreCase = true) ||
        cat.contains("film", ignoreCase = true)                        -> "\uD83C\uDFAC"
        cat.contains("entertain", ignoreCase = true)                   -> "\uD83C\uDFAD"
        cat.contains("kid", ignoreCase = true) ||
        cat.contains("child", ignoreCase = true)                       -> "\uD83E\uDDF8"
        cat.contains("music", ignoreCase = true)                       -> "\uD83C\uDFB5"
        cat.contains("doc", ignoreCase = true)                         -> "\uD83C\uDF9E\uFE0F"
        cat.contains("shop", ignoreCase = true)                        -> "\uD83D\uDECD\uFE0F"
        cat.contains("travel", ignoreCase = true)                      -> "\u2708\uFE0F"
        cat.contains("cook", ignoreCase = true) ||
        cat.contains("food", ignoreCase = true)                        -> "\uD83C\uDF73"
        cat.contains("business", ignoreCase = true) ||
        cat.contains("finance", ignoreCase = true)                     -> "\uD83D\uDCBC"
        cat.contains("science", ignoreCase = true) ||
        cat.contains("tech", ignoreCase = true)                        -> "\uD83D\uDD2C"
        cat.contains("religio", ignoreCase = true) ||
        cat.contains("faith", ignoreCase = true)                       -> "\u271D\uFE0F"
        cat.contains("radio", ignoreCase = true)                       -> "\uD83D\uDCFB"
        cat.contains("general", ignoreCase = true)                     -> "\uD83D\uDCFA"
        else                                                            -> "\uD83D\uDCFA"
    }

    private fun refreshFavoriteHearts() {
        for (i in 0 until rowsAdapter.size()) {
            val row = rowsAdapter.get(i) as? ListRow ?: continue
            val adapter = row.adapter as? ArrayObjectAdapter ?: continue
            for (j in 0 until adapter.size()) {
                val item = adapter.get(j)
                if (item is Channel) {
                    adapter.notifyArrayItemRangeChanged(j, 1)
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "sv_tv_history"
        private const val KEY_RECENT = "recent_channels"
        private const val KEY_SORT = "sort_mode"
        private const val MAX_HISTORY = 12
        internal const val SETTINGS_SENTINEL = "open_settings"
        internal const val REGION_SENTINEL = "browse_regions"
    }
}
