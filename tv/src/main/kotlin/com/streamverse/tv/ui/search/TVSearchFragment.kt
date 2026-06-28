package com.streamverse.tv.ui.search

import android.os.Bundle
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.lifecycle.lifecycleScope
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.SearchHistoryPreferences
import com.streamverse.core.data.sourceProviderCount
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.domain.model.Channel
import com.streamverse.tv.ui.browse.TVChannelPresenter
import com.streamverse.tv.ui.playback.TVPlaybackActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Leanback full-screen search over the channel list cached in [ChannelRepository].
 *
 * Results are laid out as a **vertical grid**: matches are chunked into fixed-width rows
 * (one card column per [gridColumns]) so the list grows DOWNWARD like a real catalogue,
 * instead of a single row that scrolls infinitely to the right.
 *
 * When the query is empty, the screen shows **Recent** and **Popular** searches (mirroring the
 * mobile app), backed by [SearchHistoryPreferences]. Popular searches fall back to the most
 * multi-sourced channels in the catalogue when the user has little history yet.
 */
@AndroidEntryPoint
class TVSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    @Inject lateinit var channelRepository: ChannelRepository
    @Inject lateinit var searchHistory: SearchHistoryPreferences
    @Inject lateinit var channelHealthEngine: ChannelHealthEngine

    private var liveIds: Set<String> = emptySet()
    private val channelPresenter = TVChannelPresenter(isLive = { ch -> ch.id in liveIds })
    private val suggestionPresenter = TVSuggestionPresenter()
    // Headerless rows stacked vertically read as a grid. Disable the shadow so adjacent rows align.
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter().apply { shadowEnabled = false })

    // The term currently typed/submitted, recorded to history when a result is opened.
    private var lastQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)

        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Channel -> {
                    if (lastQuery.isNotBlank()) searchHistory.record(lastQuery)
                    startActivity(
                        android.content.Intent(requireContext(), TVPlaybackActivity::class.java)
                            .putExtra(TVPlaybackActivity.EXTRA_CHANNEL_ID, item.id)
                    )
                }
                is TVSuggestion -> {
                    // Re-run the search for the tapped term (also fills the search bar).
                    setSearchQuery(item.term, true)
                }
            }
        }

        // Show recent/popular immediately so the screen is never blank before typing.
        showSuggestions()

        // Keep LIVE badges current as the health engine verifies result channels.
        lifecycleScope.launch {
            channelHealthEngine.liveChannelIds.collect { ids ->
                liveIds = ids
                refreshLiveBadges()
            }
        }
    }

    /** Re-bind visible channel cards so newly-verified LIVE badges appear without rebuilding rows. */
    private fun refreshLiveBadges() {
        for (i in 0 until rowsAdapter.size()) {
            val row = rowsAdapter.get(i) as? ListRow ?: continue
            val adapter = row.adapter as? ArrayObjectAdapter ?: continue
            if (adapter.size() > 0) adapter.notifyArrayItemRangeChanged(0, adapter.size())
        }
    }

    // ---- SearchResultProvider -------------------------------------------------------

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String): Boolean {
        performSearch(newQuery)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        if (query.isNotBlank()) searchHistory.record(query)
        performSearch(query)
        return true
    }

    // ---- Filtering ------------------------------------------------------------------

    private var searchJob: kotlinx.coroutines.Job? = null

    private fun performSearch(query: String) {
        lastQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            showSuggestions()
            return
        }
        rowsAdapter.clear()

        searchJob = lifecycleScope.launch {
            delay(180)
            // Return every match — leanback rows are lazy, so chunking thousands of results into
            // grid rows stays smooth and nothing matching is dropped.
            val results = channelRepository.searchChannels(query)
            rowsAdapter.clear()
            if (results.isEmpty()) return@launch
            channelHealthEngine.verify(results.take(60), deep = false)
            // Lay results out as a grid: chunk into rows of `cols` so the list grows vertically
            // instead of one infinitely-wide row.
            val cols = gridColumns()
            results.chunked(cols).forEach { rowChannels ->
                val rowAdapter = ArrayObjectAdapter(channelPresenter)
                rowChannels.forEach { rowAdapter.add(it) }
                rowsAdapter.add(ListRow(rowAdapter))
            }
        }
    }

    /** Populate the screen with Recent + Popular search suggestions (query is empty). */
    private fun showSuggestions() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val recent = searchHistory.recent.value
            val popular = computePopular()
            rowsAdapter.clear()

            if (recent.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(suggestionPresenter)
                recent.forEach { adapter.add(TVSuggestion(it, popular = false)) }
                rowsAdapter.add(ListRow(HeaderItem(0, "🕒  Recent Searches"), adapter))
            }
            if (popular.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(suggestionPresenter)
                popular.forEach { adapter.add(TVSuggestion(it, popular = true)) }
                rowsAdapter.add(ListRow(HeaderItem(1, "🔥  Popular Searches"), adapter))
            }
        }
    }

    /**
     * Real popular searches: the user's most-repeated terms, topped up from the catalogue when
     * history is thin. Mainstream channels carry the most sources, so source count is a genuine,
     * data-driven popularity signal (matches the mobile app).
     */
    private fun computePopular(): List<String> {
        val tracked = searchHistory.popular.value
        if (tracked.size >= POPULAR_TARGET) return tracked.take(POPULAR_TARGET)
        val fromCatalog = channelRepository.getAllChannels().asSequence()
            .filter { it.displayName.isNotBlank() && it.logoUrl != null }
            .sortedByDescending { it.sourceProviderCount() }
            .map { it.displayName }
            .distinct()
            .take(POPULAR_TARGET)
            .toList()
        return (tracked + fromCatalog).distinct().take(POPULAR_TARGET)
    }

    /** How many cards fit across the screen, so each grid row fills the width without overflowing. */
    private fun gridColumns(): Int {
        val dm = resources.displayMetrics
        val cardPx = (TVChannelPresenter.CARD_WIDTH_DP + 16) * dm.density
        return (dm.widthPixels / cardPx).toInt().coerceIn(3, 7)
    }

    private companion object {
        const val POPULAR_TARGET = 8
    }
}
