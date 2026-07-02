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
import com.streamverse.core.data.repository.HomeRankingEngine
import com.streamverse.core.data.repository.LoadingPhase
import com.streamverse.core.data.repository.ProgrammeRepository
import com.streamverse.core.data.repository.RankingContext
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.DayPeriod
import com.streamverse.core.domain.model.HomeSection
import com.streamverse.core.domain.model.numberedDisplayName
import com.streamverse.core.domain.model.SectionType
import com.streamverse.core.domain.model.SortMode
import com.streamverse.core.domain.model.TimeOfDay
import com.streamverse.core.util.CategoryNormalizer
import com.streamverse.core.util.RegionProvider
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.FlowPreview
import javax.inject.Inject

@AndroidEntryPoint
@OptIn(FlowPreview::class)
class TVBrowseFragment : BrowseSupportFragment() {

    @Inject lateinit var channelRepository: ChannelRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository
    @Inject lateinit var streamPreResolver: StreamPreResolver
    @Inject lateinit var channelHealthEngine: ChannelHealthEngine
    @Inject lateinit var programmeRepo: ProgrammeRepository
    @Inject lateinit var homeRanking: HomeRankingEngine

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
            if (item is Channel) title = item.numberedDisplayName()
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

        // ── Structural updates: channels/radio/sort mode change → rebuild rows ──
        loadingJob?.cancel()
        loadingJob = lifecycleScope.launch(Dispatchers.Default) {
            // Wait for initial health data so the first render has correct live filtering.
            // If health data doesn't arrive within 3s, proceed with all channels.
            val initialLive = channelHealthEngine.liveChannelIds.value.let { current ->
                if (current.isNotEmpty()) current
                else withTimeoutOrNull(3_000L) {
                    channelHealthEngine.liveChannelIds.first { it.isNotEmpty() }
                } ?: emptySet()
            }
            liveIds = initialLive

            combine(
                channelRepository.channels,
                channelRepository.radioChannels,
                _sortMode,
            ) { chs, radios, sortMode ->
                Triple(chs, radios, sortMode)
            }
                .debounce(1000)
                .collect { (chs, radios, sortMode) ->
                    if (chs.isNotEmpty() || radios.isNotEmpty()) {
                        computeAndApplyRows(chs, radios, sortMode)
                    }
                }
        }

        // ── Live badge updates: only refresh card states, never rebuild rows ──
        lifecycleScope.launch {
            channelHealthEngine.liveChannelIds
                .debounce(300)
                .collect { live ->
                    if (live != liveIds) {
                        liveIds = live
                        if (rowsBuilt) refreshLiveBadges()
                    }
                }
        }

        lifecycleScope.launch {
            favoritesRepository.getAllFavoriteIds().collect { favs ->
                favoriteIds = favs.toSet()
                refreshFavoriteHearts()
            }
        }

        lifecycleScope.launch {
            withContext(NonCancellable) {
                channelRepository.load()
            }
        }

        // Progressive verification: once loading finishes, kick off health checks on
        // the full unfiltered catalogue so live badges populate progressively.
        lifecycleScope.launch {
            channelRepository.loadingPhase.first { it == LoadingPhase.DONE }
            val allChs = channelRepository.getCachedChannels()
            if (allChs.isNotEmpty()) { /* verify handled by backend pipeline */ }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        preResolveHandler.removeCallbacksAndMessages(null)
        activeBillboard = null
        title = "StreamVerse TV"
    }

    private var showingPlaceholders = false
    /** True once the initial row structure has been built. Subsequent emissions should
     *  update cards incrementally rather than rebuilding everything. */
    private var rowsBuilt = false

    /**
     * Computes all row data on [Dispatchers.Default], then applies adapter updates on Main.
     * This keeps heavy sorting/ranking off the UI thread and prevents ANRs during
     * progressive channel loading.
     */
    private suspend fun computeAndApplyRows(all: List<Channel>, radioChannels: List<Channel>, sortMode: SortMode) {
        // Phase 1: compute all channel lists off Main thread
        // Schedule health verification
        val recentChs = loadHistory(all)
        val recentIds = recentChs.map { it.id }.toSet()
        // verify handled by backend pipeline

        val ctx = RankingContext(
            userRegion = RegionProvider.getRegionCode(),
            recentlyWatchedIds = recentIds,
            favoriteIds = favoriteIds,
        )
        programmeRepo.refreshTimeOfDay()
        currentTimeOfDay = programmeRepo.timeOfDay.value
        val timeOfDay = currentTimeOfDay
        val localLiveIds = liveIds

        val liveFirst: (List<Channel>) -> List<Channel> = { list ->
            val live = list.filter { it.id in localLiveIds }
            val rest = list.filter { it.id !in localLiveIds }
            (live + rest).distinctBy { it.id }
        }

        val liveChs = if (localLiveIds.isNotEmpty()) all.filter { it.id in localLiveIds } else all

        data class SectionData(
            val sectionType: SectionType,
            val title: String,
            val channels: List<Channel>,
        )

        val sections = mutableListOf<SectionData>()

        // Compute each section's channel list
        val featuredChs = programmeRepo.getGloballyPopular(liveFirst(liveChs), limit = 10, ctx)
            .filter { !it.logoUrl.isNullOrBlank() }

        sections += SectionData(SectionType.EDITOR_PICKS, "Editor's Picks",
            programmeRepo.editorialPicks(liveFirst(liveChs), limit = 8, ctx))

        val onNow = liveFirst(liveChs).sortedByDescending { programmeRepo.rankChannel(it, ctx) }.take(12)
        sections += SectionData(SectionType.HERO_BANNER, "On Now", onNow)
        // channelHealthEngine.verify(featuredChs + onNow, deep = true)

        programmeRepo.updateLiveEvents(liveChs)
        val liveEvents = programmeRepo.liveEvents.value
        if (liveEvents.isNotEmpty()) {
            val eventChs = liveEvents.flatMap { it.channelIds }
                .mapNotNull { id -> liveChs.find { it.id == id } }
                .filter { !it.logoUrl.isNullOrBlank() }
            sections += SectionData(SectionType.LIVE_EVENTS, "Live Events", eventChs.take(8))
        }

        sections += SectionData(SectionType.CONTINUE_WATCHING, "Continue Watching",
            liveFirst(recentChs))

        sections += SectionData(SectionType.TOP_NEWS, "Top News",
            programmeRepo.topByCategory(liveChs, CategoryNormalizer.C.NEWS, limit = 8, ctx).let { liveFirst(it) })

        programmeRepo.updateLiveScores(liveChs)
        sections += SectionData(SectionType.TOP_SPORTS, "Top Sports",
            programmeRepo.topByCategory(liveChs, CategoryNormalizer.C.SPORTS, limit = 8, ctx).let { liveFirst(it) })

        sections += SectionData(SectionType.TOP_ENTERTAINMENT, "Top Entertainment",
            programmeRepo.topByCategory(liveChs, CategoryNormalizer.C.ENTERTAINMENT, limit = 8, ctx).let { liveFirst(it) })

        sections += SectionData(SectionType.TOP_MOVIES, "Top Movies",
            programmeRepo.topByCategory(liveChs, CategoryNormalizer.C.MOVIES, limit = 8, ctx).let { liveFirst(it) })

        sections += SectionData(SectionType.TOP_DOCUMENTARIES, "Top Documentaries",
            programmeRepo.topByCategory(liveChs, CategoryNormalizer.C.DOCUMENTARY, limit = 8, ctx).let { liveFirst(it) })

        programmeRepo.updateTrending(liveChs)
        val trending = programmeRepo.trending.value.map { it.channel }.filter { !it.logoUrl.isNullOrBlank() }
        sections += SectionData(SectionType.TRENDING, "Trending Live", trending.take(8))

        sections += SectionData(SectionType.POPULAR_WORLDWIDE, "Popular Worldwide",
            programmeRepo.getGloballyPopular(liveFirst(liveChs), limit = 12, ctx))

        sections += SectionData(SectionType.POPULAR_IN_REGION, "Popular in Your Region",
            programmeRepo.popularInRegion(liveFirst(liveChs), limit = 8, ctx))

        sections += SectionData(SectionType.KIDS, "Kids",
            programmeRepo.topByCategory(liveChs, CategoryNormalizer.C.KIDS, limit = 20, ctx).let { liveFirst(it) })

        sections += SectionData(SectionType.MUSIC, "Music",
            programmeRepo.topByCategory(liveChs, CategoryNormalizer.C.MUSIC, limit = 20, ctx).let { liveFirst(it) })

        sections += SectionData(SectionType.RECOMMENDATIONS, "Recommended For You",
            programmeRepo.getRecommendedChannels(liveChs, recentChs,
                liveChs.mapNotNull { it.category }.toSet(), timeOfDay.period).let { liveFirst(it).take(8) })

        sections += SectionData(SectionType.FAVOURITES, "My Favourites",
            liveFirst(liveChs.filter { it.id in favoriteIds }))

        // SortMode-specific channel groupings
        val categories: List<Pair<String, List<Channel>>> = when (sortMode) {
            SortMode.CATEGORY -> {
                val cats = liveChs.mapNotNull { it.category }.distinct()
                    .filter { it != CategoryNormalizer.C.RADIO }.sorted()
                cats.mapNotNull { cat ->
                    val chs = programmeRepo.topByCategory(liveChs, cat, limit = 40, ctx).let { liveFirst(it) }
                    if (chs.isNotEmpty()) cat to chs else null
                }
            }
            else -> emptyList()
        }
        val alphabeticalDigits: List<Channel> = when (sortMode) {
            SortMode.ALPHABETICAL -> {
                val sorted = liveFirst(liveChs).sortedBy { it.displayName.lowercase() }
                sorted.filter { ch ->
                    val first = ch.displayName.lowercase().firstOrNull()
                    first != null && first in '0'..'9'
                }.take(40)
            }
            else -> emptyList()
        }
        val alphabeticalLetters: List<Pair<Char, List<Channel>>> = when (sortMode) {
            SortMode.ALPHABETICAL -> {
                val sorted = liveFirst(liveChs).sortedBy { it.displayName.lowercase() }
                ('A'..'Z').mapNotNull { letter ->
                    val lc = letter.lowercase()[0]
                    val chs = sorted.filter { ch ->
                        val first = ch.displayName.lowercase().firstOrNull()
                        first != null && first == lc
                    }.take(40)
                    if (chs.isNotEmpty()) letter to chs else null
                }
            }
            else -> emptyList()
        }
        val regionGroups: List<Pair<String, List<Channel>>> = when (sortMode) {
            SortMode.REGION -> {
                val byRegion = liveChs.filterNot { it.country.isNullOrBlank() }
                    .groupBy { it.country!! }.entries.sortedBy { it.key }
                byRegion.map { (region, chs) -> region to liveFirst(chs).take(40) }
            }
            else -> emptyList()
        }
        val noRegionChs: List<Channel> = when (sortMode) {
            SortMode.REGION -> liveChs.filter { it.country.isNullOrBlank() }.let { liveFirst(it).take(20) }
            else -> emptyList()
        }

        val radioChs = radioChannels.take(40)

        // Compute section ordering
        val sectionOrder = homeRanking.rankSections(
            sections.map { HomeSection(id = it.title, title = it.title, type = it.sectionType) },
            timeOfDay.period,
        ).map { it.type }

        // Now apply to the adapter on the Main thread
        withContext(Dispatchers.Main) {
            rowsAdapter.clear()
            showingPlaceholders = false

            val presenter = TVChannelPresenter(
                recentIds = recentIds,
                isFavorite = { ch -> ch.id in favoriteIds },
                onToggleFavorite = { ch -> toggleFavorite(ch) },
                isLive = { ch -> ch.id in liveIds },
            )
            val onNowPresenter = TVOnNowPresenter(isLive = { ch -> ch.id in liveIds })

            var rowIndex = 0L

            // 1. Sort Mode
            val sortAdapter = ArrayObjectAdapter(TVSettingsCardPresenter(
                mainText = "Sort: ${sortMode.displayName}",
                subText = "Tap to cycle",
            ))
            sortAdapter.add(sortMode)
            rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Sort"), sortAdapter))

            // 2. Billboard (Featured)
            if (featuredChs.isNotEmpty()) {
                val billboardAdapter = ArrayObjectAdapter(TVBillboardPresenter { launchChannel(it) })
                billboardAdapter.add(featuredChs)
                rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Featured"), billboardAdapter))
            }

            // 3. Ordered sections
            for (sectionType in sectionOrder) {
                val sec = sections.find { it.sectionType == sectionType && it.channels.isNotEmpty() } ?: continue
                val adapter = when (sectionType) {
                    SectionType.KIDS, SectionType.MUSIC, SectionType.CONTINUE_WATCHING, SectionType.FAVOURITES -> {
                        ArrayObjectAdapter(presenter)
                    }
                    else -> ArrayObjectAdapter(onNowPresenter)
                }
                sec.channels.forEach { adapter.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(rowIndex++, sec.title), adapter))
            }

            // 4. Sort-mode rows
            when (sortMode) {
                SortMode.CATEGORY -> {
                    for ((cat, chs) in categories) {
                        val adapter = ArrayObjectAdapter(presenter)
                        chs.forEach { adapter.add(it) }
                        rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "${categoryEmoji(cat)}  $cat"), adapter))
                    }
                }
                SortMode.ALPHABETICAL -> {
                    if (alphabeticalDigits.isNotEmpty()) {
                        val adapter = ArrayObjectAdapter(presenter)
                        alphabeticalDigits.forEach { adapter.add(it) }
                        rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "0-9"), adapter))
                    }
                    for ((letter, chs) in alphabeticalLetters) {
                        val adapter = ArrayObjectAdapter(presenter)
                        chs.forEach { adapter.add(it) }
                        rowsAdapter.add(ListRow(HeaderItem(rowIndex++, letter.toString()), adapter))
                    }
                }
                SortMode.REGION -> {
                    for ((region, chs) in regionGroups) {
                        val adapter = ArrayObjectAdapter(presenter)
                        chs.forEach { adapter.add(it) }
                        rowsAdapter.add(ListRow(HeaderItem(rowIndex++, region), adapter))
                    }
                    if (noRegionChs.isNotEmpty()) {
                        val adapter = ArrayObjectAdapter(presenter)
                        noRegionChs.forEach { adapter.add(it) }
                        rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Other"), adapter))
                    }
                }
            }

            // 5. Radio
            if (radioChs.isNotEmpty()) {
                val radioAdapter = ArrayObjectAdapter(TVChannelPresenter(
                    isFavorite = { ch -> ch.id in favoriteIds },
                    onToggleFavorite = { ch -> toggleFavorite(ch) },
                    isLive = { ch -> ch.id in liveIds },
                ))
                radioChs.forEach { radioAdapter.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "Radio"), radioAdapter))
            }

            // 6. Settings
            val settingsAdapter = ArrayObjectAdapter(TVSettingsCardPresenter())
            settingsAdapter.add(SETTINGS_SENTINEL)
            rowsAdapter.add(ListRow(HeaderItem(rowIndex, "Settings"), settingsAdapter))

            rowsBuilt = true
        }
    }

    /** Schedule health verification for these channels (safe to call from any thread). */
    private fun scheduleVerify(channels: List<Channel>, deep: Boolean) {
        // verify handled by backend pipeline
    }

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

    /** Refresh live badge renderings on all channel cards without rebuilding rows. */
    private fun refreshLiveBadges() {
        if (rowsAdapter.size() == 0) return
        for (i in 0 until rowsAdapter.size()) {
            val row = rowsAdapter.get(i) as? ListRow ?: continue
            val adapter = row.adapter as? ArrayObjectAdapter ?: continue
            for (j in 0 until adapter.size()) {
                if (adapter.get(j) is Channel) {
                    adapter.notifyArrayItemRangeChanged(j, 1)
                }
            }
        }
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