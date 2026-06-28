package com.streamverse.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.SearchHistoryPreferences
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.data.repository.FavoritesRepository
import com.streamverse.core.domain.model.Channel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class BrowseTab { SEARCH, CATEGORY, REGION, LANGUAGE }

val WORLD_REGIONS: Map<String, Set<String>> = mapOf(
    "Middle East" to setOf("AE","SA","QA","KW","BH","OM","JO","IQ","LB","SY","YE","PS","IR"),
    "Africa" to setOf("EG","NG","GH","ZA","KE","ET","TZ","UG","CM","CI","SN","MA","TN","DZ","LY","SD","RW","MU","MG","AO","MZ","ZM","ZW","BW","NA","SL","LR","TG","BJ","BF","ML","GN","NE","TD","CD","CG","CF","GA","GQ","ER","SO","DJ"),
    "Europe" to setOf("GB","FR","DE","IT","ES","PT","NL","BE","CH","AT","SE","NO","DK","FI","PL","RU","TR","GR","RO","CZ","UA","HU","SK","BG","HR","RS","IE","IS","LU","MT","CY","GE","AM","AZ","AL","BA","ME","MK","MD","LV","LT","EE","BY","XK","SI"),
    "Americas" to setOf("US","CA","MX","BR","AR","CO","VE","PE","CL","CU","DO","JM","TT","GT","EC","HN","SV","PA","CR","BO","UY","PY","NI","HT","PR","TC","BB","BS","BZ","GY","SR"),
    "Asia" to setOf("IN","PK","BD","LK","CN","JP","KR","ID","MY","TH","VN","PH","SG","HK","TW","AF","NP","MM","KH","LA","MN","UZ","KZ","KG","TJ","TM","BN"),
    "Oceania" to setOf("AU","NZ","FJ","PG","WS","TO","SB","VU"),
)

val COUNTRY_NAMES: Map<String, String> = mapOf(
    "AE" to "UAE", "SA" to "Saudi Arabia", "QA" to "Qatar", "KW" to "Kuwait",
    "BH" to "Bahrain", "OM" to "Oman", "JO" to "Jordan", "IQ" to "Iraq",
    "LB" to "Lebanon", "SY" to "Syria", "YE" to "Yemen", "PS" to "Palestine",
    "IR" to "Iran", "EG" to "Egypt", "NG" to "Nigeria", "GH" to "Ghana",
    "ZA" to "South Africa", "KE" to "Kenya", "ET" to "Ethiopia", "TZ" to "Tanzania",
    "UG" to "Uganda", "CM" to "Cameroon", "SN" to "Senegal", "MA" to "Morocco",
    "TN" to "Tunisia", "DZ" to "Algeria", "LY" to "Libya", "SD" to "Sudan",
    "GB" to "United Kingdom", "FR" to "France", "DE" to "Germany", "IT" to "Italy",
    "ES" to "Spain", "PT" to "Portugal", "NL" to "Netherlands", "BE" to "Belgium",
    "CH" to "Switzerland", "AT" to "Austria", "SE" to "Sweden", "NO" to "Norway",
    "DK" to "Denmark", "FI" to "Finland", "PL" to "Poland", "RU" to "Russia",
    "TR" to "Turkey", "GR" to "Greece", "RO" to "Romania", "CZ" to "Czech Republic",
    "UA" to "Ukraine", "HU" to "Hungary", "SK" to "Slovakia", "BG" to "Bulgaria",
    "HR" to "Croatia", "RS" to "Serbia", "IE" to "Ireland", "US" to "United States",
    "CA" to "Canada", "MX" to "Mexico", "BR" to "Brazil", "AR" to "Argentina",
    "CO" to "Colombia", "VE" to "Venezuela", "PE" to "Peru", "CL" to "Chile",
    "CU" to "Cuba", "DO" to "Dominican Rep.", "JM" to "Jamaica", "IN" to "India",
    "PK" to "Pakistan", "BD" to "Bangladesh", "LK" to "Sri Lanka", "CN" to "China",
    "JP" to "Japan", "KR" to "South Korea", "ID" to "Indonesia", "MY" to "Malaysia",
    "TH" to "Thailand", "VN" to "Vietnam", "PH" to "Philippines", "SG" to "Singapore",
    "HK" to "Hong Kong", "TW" to "Taiwan", "AF" to "Afghanistan", "AU" to "Australia",
    "NZ" to "New Zealand", "GE" to "Georgia", "AM" to "Armenia", "AZ" to "Azerbaijan",
    "KZ" to "Kazakhstan", "UZ" to "Uzbekistan", "AL" to "Albania", "BA" to "Bosnia",
)

fun countryCodeToName(code: String): String = COUNTRY_NAMES[code.uppercase()] ?: code.uppercase()

fun countryCodeToRegion(code: String): String {
    val upper = code.uppercase()
    return WORLD_REGIONS.entries.firstOrNull { upper in it.value }?.key ?: "Other"
}

data class SearchUiState(
    val query: String = "",
    val results: List<Channel> = emptyList(),
    val isSearching: Boolean = false,
    val browseTab: BrowseTab = BrowseTab.SEARCH,
    val selectedCountry: String? = null,
    val selectedLanguage: String? = null,
    val selectedRegion: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val favoritesRepository: FavoritesRepository,
    private val searchHistory: SearchHistoryPreferences,
    private val healthEngine: ChannelHealthEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    val favoriteIds: StateFlow<Set<String>> = favoritesRepository.getAllFavoriteIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // WhileSubscribed (not Eagerly): only materialize the filtered index while the Search
    // screen is actually observing it, so it doesn't churn in the background on every channel
    // update when the user is elsewhere.
    val allChannels: StateFlow<List<Channel>> = repository.channels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The user's recent searches (persisted), most-recent first. */
    val recentSearches: StateFlow<List<String>> = searchHistory.recent

    /**
     * Real popular searches — the user's most-repeated terms, topped up from the actual
     * catalogue when history is thin. Mainstream channels carry the most sources (available
     * via DLHD + Stmify + IPTV…), so source count is a genuine, data-driven popularity signal.
     */
    val popularSearches: StateFlow<List<String>> =
        combine(searchHistory.popular, allChannels) { tracked, channels ->
            if (tracked.size >= POPULAR_TARGET) tracked.take(POPULAR_TARGET)
            else {
                val fromCatalog = channels.asSequence()
                    .filter { it.displayName.isNotBlank() && it.logoUrl != null }
                    .sortedByDescending { it.sources.size }
                    .map { it.displayName }
                    .distinct()
                    .take(POPULAR_TARGET)
                    .toList()
                (tracked + fromCatalog).distinct().take(POPULAR_TARGET)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null

    private companion object {
        const val POPULAR_TARGET = 8
    }

    /** Persist a term the user actually acted on (submitted, or opened a result for). */
    fun recordSearch(term: String) = searchHistory.record(term)

    fun clearRecentSearches() = searchHistory.clearRecent()

    fun setBrowseTab(tab: BrowseTab) {
        _uiState.value = _uiState.value.copy(
            browseTab = tab,
            selectedCountry = null,
            selectedLanguage = null,
            selectedRegion = null,
        )
    }

    fun selectRegion(region: String?) {
        _uiState.value = _uiState.value.copy(selectedRegion = region, selectedCountry = null)
    }

    fun selectCountry(country: String?) {
        _uiState.value = _uiState.value.copy(selectedCountry = country)
    }

    fun selectLanguage(language: String?) {
        _uiState.value = _uiState.value.copy(selectedLanguage = language)
    }

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query, browseTab = BrowseTab.SEARCH)
        searchJob?.cancel()
        if (query.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(180)
                _uiState.value = _uiState.value.copy(isSearching = true)
                // Return EVERY channel that matches the query — the grid is lazy (only visible
                // cards are composed), so a broad term that matches thousands stays smooth while
                // nothing is silently dropped. Results are already relevance-ranked (curated/Stmify
                // hits first), so the most useful matches still sit at the top.
                val results = repository.searchChannels(query)
                _uiState.value = _uiState.value.copy(
                    results = results,
                    isSearching = false,
                )
                // Verify the top of the result set so LIVE badges fill in as the user scans.
                healthEngine.verify(results.take(60), deep = false)
            }
        } else {
            _uiState.value = _uiState.value.copy(results = emptyList(), isSearching = false)
        }
    }

    fun clearSearch() {
        _uiState.value = SearchUiState()
    }

    /** Popular/recent chip tapped: record it, clear the box, then run it as a fresh search. */
    fun onPopularSearch(term: String) {
        searchJob?.cancel()
        searchHistory.record(term)
        _uiState.value = SearchUiState()
        onQueryChanged(term)
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            val isFav = favoriteIds.value.contains(channel.id)
            if (isFav) {
                favoritesRepository.removeFavorite(channel.id)
            } else {
                favoritesRepository.addFavorite(channel)
            }
        }
    }
}
