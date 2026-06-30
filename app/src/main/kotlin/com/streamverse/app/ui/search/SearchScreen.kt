package com.streamverse.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamverse.app.ui.components.ChannelCard
import com.streamverse.app.ui.components.GridShimmer
import com.streamverse.app.ui.player.LocalMiniPlayerInset
import com.streamverse.app.ui.theme.CyberCyan
import com.streamverse.app.ui.theme.ElectricViolet
import com.streamverse.app.ui.theme.NavyCard
import com.streamverse.app.ui.theme.SpaceNavy
import com.streamverse.core.domain.model.ChannelSummary
import com.streamverse.core.util.CategoryNormalizer

@Composable
fun SearchScreen(
    onChannelClick: (String) -> Unit,
    onPopularSearch: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val favIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val allChannels by viewModel.allChannels.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val popularSearches by viewModel.popularSearches.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(CyberCyan.copy(0.08f), ElectricViolet.copy(0.08f))))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Discover",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
        }

        // Tab row
        TabRow(
            selectedTabIndex = state.browseTab.ordinal,
            containerColor = SpaceNavy,
            contentColor = CyberCyan,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[state.browseTab.ordinal]),
                    color = CyberCyan,
                )
            },
            divider = {},
        ) {
            listOf("Search", "Category", "Region", "Language").forEachIndexed { idx, label ->
                val tab = BrowseTab.entries[idx]
                Tab(
                    selected = state.browseTab == tab,
                    onClick = {
                        viewModel.setBrowseTab(tab)
                        focusManager.clearFocus()
                    },
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (state.browseTab == tab) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    selectedContentColor = CyberCyan,
                    unselectedContentColor = Color(0xFF64748B),
                )
            }
        }

        when (state.browseTab) {
            BrowseTab.SEARCH -> SearchTab(
                state = state,
                favIds = favIds,
                recentSearches = recentSearches,
                popularSearches = popularSearches,
                onQueryChanged = viewModel::onQueryChanged,
                onPopularSearch = viewModel::onPopularSearch,
                onClearRecent = viewModel::clearRecentSearches,
                onSubmit = { viewModel.recordSearch(state.query); focusManager.clearFocus() },
                onClear = {
                    viewModel.clearSearch()
                    focusManager.clearFocus()
                },
                onChannelClick = { id -> viewModel.recordSearch(state.query); onChannelClick(id) },
                onToggleFavorite = viewModel::toggleFavorite,
            )

            BrowseTab.CATEGORY -> CategoryTab(
                state = state,
                allChannels = allChannels,
                favIds = favIds,
                onSelectCategory = { viewModel.selectLanguage(it) },  // reuse selectedLanguage slot
                onChannelClick = onChannelClick,
                onToggleFavorite = viewModel::toggleFavorite,
            )

            BrowseTab.REGION -> RegionTab(
                state = state,
                allChannels = allChannels,
                favIds = favIds,
                onSelectRegion = viewModel::selectRegion,
                onSelectCountry = viewModel::selectCountry,
                onChannelClick = onChannelClick,
                onToggleFavorite = viewModel::toggleFavorite,
            )

            BrowseTab.LANGUAGE -> LanguageTab(
                state = state,
                allChannels = allChannels,
                favIds = favIds,
                onSelectLanguage = viewModel::selectLanguage,
                onChannelClick = onChannelClick,
                onToggleFavorite = viewModel::toggleFavorite,
            )
        }
    }
}

// ── Search tab ──────────────────────────────────────────────────────────────

@Composable
private fun SearchTab(
    state: SearchUiState,
    favIds: Set<String>,
    recentSearches: List<String>,
    popularSearches: List<String>,
    onQueryChanged: (String) -> Unit,
    onPopularSearch: (String) -> Unit,
    onClearRecent: () -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onChannelClick: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val availableCategories = state.results.mapNotNull { it.category }
        .filter { !it.contains(",") && it.length <= 30 }
        .distinct().sorted()

    // Premium-search behaviour: opening Search focuses the field and raises the keyboard with
    // the cursor active — no extra tap required (mobile + TV).
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TextField(
            value = state.query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(28.dp))
                .focusRequester(focusRequester),
            placeholder = {
                Text(
                    "Search channels, sports, news…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(20.dp))
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Color(0xFF64748B))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = NavyCard,
                unfocusedContainerColor = NavyCard,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = CyberCyan,
            ),
        )

        if (availableCategories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(availableCategories) { cat ->
                    FilterChip(
                        selected = false,
                        onClick = { onQueryChanged(cat) },
                        label = { Text(cat, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = NavyCard,
                            labelColor = Color(0xFF64748B),
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = false,
                            borderColor = Color(0xFF334155),
                        ),
                    )
                }
            }
        }

        when {
            state.isSearching -> Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { GridShimmer() }

            state.results.isNotEmpty() -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(148.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp + LocalMiniPlayerInset.current),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.results, key = { it.id }) { ch ->
                        ChannelCard(
                            channel = ch,
                            onClick = { onChannelClick(ch.id) },
                            isFavorite = favIds.contains(ch.id),
                            onToggleFavorite = { onToggleFavorite(ch.id) },
                        )
                    }
                }
            }

            state.query.length >= 2 -> EmptySearchState(query = state.query)

            else -> SearchHintState(
                recentSearches = recentSearches,
                popularSearches = popularSearches,
                onHintClick = onPopularSearch,
                onClearRecent = onClearRecent,
            )
        }
    }
}

// ── Category tab ─────────────────────────────────────────────────────────────

val CATEGORY_ICONS = mapOf(
    CategoryNormalizer.C.NEWS          to "📰",
    CategoryNormalizer.C.SPORTS        to "⚽",
    CategoryNormalizer.C.MOVIES        to "🎬",
    CategoryNormalizer.C.KIDS          to "🧸",
    CategoryNormalizer.C.MUSIC         to "🎵",
    CategoryNormalizer.C.DOCUMENTARY   to "🎞",
    CategoryNormalizer.C.RELIGIOUS     to "🕌",
    CategoryNormalizer.C.LIFESTYLE     to "🌿",
    CategoryNormalizer.C.COMEDY        to "😄",
    CategoryNormalizer.C.SCIENCE       to "🔬",
    CategoryNormalizer.C.ENTERTAINMENT to "🎭",
    CategoryNormalizer.C.GENERAL       to "📺",
    CategoryNormalizer.C.RADIO         to "📻",
)

@Composable
private fun CategoryTab(
    state: SearchUiState,
    allChannels: List<ChannelSummary>,
    favIds: Set<String>,
    onSelectCategory: (String?) -> Unit,
    onChannelClick: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val channelsByCategory = allChannels.groupBy { it.category ?: CategoryNormalizer.C.GENERAL }
    val selectedCategory = state.selectedLanguage  // reusing selectedLanguage slot for category

    if (selectedCategory != null) {
        val channels = channelsByCategory[selectedCategory].orEmpty()
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectCategory(null) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyberCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "${CATEGORY_ICONS[selectedCategory] ?: "📺"} $selectedCategory",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("${channels.size}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(148.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp + LocalMiniPlayerInset.current),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(channels, key = { it.id }) { ch ->
                    ChannelCard(
                        channel = ch,
                        onClick = { onChannelClick(ch.id) },
                        isFavorite = favIds.contains(ch.id),
                        onToggleFavorite = { onToggleFavorite(ch.id) },
                    )
                }
            }
        }
        return
    }

    val orderedCategories = CategoryNormalizer.C.ALL.filter { it in channelsByCategory }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + LocalMiniPlayerInset.current),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (allChannels.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Loading channels…", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(orderedCategories, key = { it }) { cat ->
            val count = channelsByCategory[cat]?.size ?: 0
            CategoryCard(
                name = cat,
                emoji = CATEGORY_ICONS[cat] ?: "📺",
                count = count,
                onClick = { onSelectCategory(cat) },
            )
        }
    }
}

@Composable
private fun CategoryCard(name: String, emoji: String, count: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Column {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("$count channels", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
        }
    }
}

// ── Region tab ───────────────────────────────────────────────────────────────

@Composable
private fun RegionTab(
    state: SearchUiState,
    allChannels: List<ChannelSummary>,
    favIds: Set<String>,
    onSelectRegion: (String?) -> Unit,
    onSelectCountry: (String?) -> Unit,
    onChannelClick: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    // Build country → channel count map from loaded channels
    val channelsByCountry = allChannels
        .mapNotNull { ch -> ch.country?.uppercase()?.takeIf { it.length == 2 }?.let { code -> code to ch } }
        .groupBy({ it.first }, { it.second })

    val regionChannelMap: Map<String, Map<String, List<ChannelSummary>>> = WORLD_REGIONS.mapValues { (_, codes) ->
        codes.mapNotNull { code ->
            channelsByCountry[code]?.let { chs -> code to chs }
        }.toMap()
    }.filter { it.value.isNotEmpty() }

    if (state.selectedCountry != null) {
        // Show channels for selected country
        val channels = channelsByCountry[state.selectedCountry!!.uppercase()].orEmpty()
        Column(modifier = Modifier.fillMaxSize()) {
            // Back header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectCountry(null) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyberCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = countryCodeToName(state.selectedCountry!!),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("${channels.size}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(148.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp + LocalMiniPlayerInset.current),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(channels, key = { it.id }) { ch ->
                    ChannelCard(
                        channel = ch,
                        onClick = { onChannelClick(ch.id) },
                        isFavorite = favIds.contains(ch.id),
                        onToggleFavorite = { onToggleFavorite(ch.id) },
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + LocalMiniPlayerInset.current),
    ) {
        if (allChannels.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Loading channels…", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        regionChannelMap.entries.sortedBy { it.key }.forEach { (region, countriesMap) ->
            val isExpanded = state.selectedRegion == region
            item(key = "region_$region") {
                RegionHeader(
                    name = region,
                    totalChannels = countriesMap.values.sumOf { it.size },
                    isExpanded = isExpanded,
                    onClick = { onSelectRegion(if (isExpanded) null else region) },
                )
            }
            if (isExpanded) {
                item(key = "countries_$region") {
                    CountryChips(
                        countriesMap = countriesMap,
                        onSelectCountry = onSelectCountry,
                    )
                }
            }
        }
    }
}

@Composable
private fun RegionHeader(
    name: String,
    totalChannels: Int,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val regionEmoji = when (name) {
            "Middle East" -> "🌙"
            "Africa" -> "🌍"
            "Europe" -> "🏰"
            "Americas" -> "🗽"
            "Asia" -> "🏯"
            "Oceania" -> "🦘"
            else -> "🌐"
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(NavyCard, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(regionEmoji, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("$totalChannels channels", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
        }
        Text(
            text = if (isExpanded) "▲" else "▶",
            style = MaterialTheme.typography.labelSmall,
            color = CyberCyan,
        )
    }
}

@Composable
private fun CountryChips(
    countriesMap: Map<String, List<ChannelSummary>>,
    onSelectCountry: (String) -> Unit,
) {
    val sorted = countriesMap.entries.sortedByDescending { it.value.size }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        items(sorted, key = { it.key }) { (code, chs) ->
            CountryChip(
                name = countryCodeToName(code),
                count = chs.size,
                onClick = { onSelectCountry(code) },
            )
        }
    }
}

@Composable
private fun CountryChip(name: String, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(NavyCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            modifier = Modifier
                .background(CyberCyan.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text("$count", style = MaterialTheme.typography.labelSmall, color = CyberCyan, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Language tab ─────────────────────────────────────────────────────────────

@Composable
private fun LanguageTab(
    state: SearchUiState,
    allChannels: List<ChannelSummary>,
    favIds: Set<String>,
    onSelectLanguage: (String?) -> Unit,
    onChannelClick: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val channelsByLanguage = allChannels
        .mapNotNull { ch -> ch.language?.lowercase()?.replaceFirstChar { c -> c.uppercaseChar() }?.let { lang -> lang to ch } }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedByDescending { it.value.size }

    if (state.selectedLanguage != null) {
        val selectedLang = state.selectedLanguage
        val channels = allChannels.filter { ch ->
            ch.language?.lowercase()?.replaceFirstChar { c -> c.uppercaseChar() } == selectedLang
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectLanguage(null) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyberCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(state.selectedLanguage!!, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("${channels.size}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(148.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp + LocalMiniPlayerInset.current),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(channels, key = { it.id }) { ch ->
                    ChannelCard(
                        channel = ch,
                        onClick = { onChannelClick(ch.id) },
                        isFavorite = favIds.contains(ch.id),
                        onToggleFavorite = { onToggleFavorite(ch.id) },
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + LocalMiniPlayerInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (channelsByLanguage.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Loading channels…", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(channelsByLanguage, key = { it.key }) { (lang, chs) ->
            LanguageRow(language = lang, count = chs.size, onClick = { onSelectLanguage(lang) })
        }
    }
}

@Composable
private fun LanguageRow(language: String, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(NavyCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🗣", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.width(12.dp))
        Text(language, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
        Spacer(modifier = Modifier.width(8.dp))
        Text("▶", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
    }
}

// ── Empty / hint states ───────────────────────────────────────────────────────

@Composable
private fun EmptySearchState(query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔭", style = MaterialTheme.typography.displaySmall)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No results for \"$query\"",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Try a different channel name",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
        }
    }
}

@Composable
private fun SearchHintState(
    recentSearches: List<String>,
    popularSearches: List<String>,
    onHintClick: (String) -> Unit,
    onClearRecent: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = LocalMiniPlayerInset.current),
    ) {
        if (recentSearches.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recent searches", style = MaterialTheme.typography.labelLarge, color = Color(0xFF64748B))
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberCyan,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onClearRecent() }
                        .semantics { contentDescription = "Clear recent searches" }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            recentSearches.forEach { term ->
                SearchSuggestionRow(term, Icons.Default.History, onClick = { onHintClick(term) })
                Spacer(modifier = Modifier.height(6.dp))
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        if (popularSearches.isNotEmpty()) {
            Text("Popular searches", style = MaterialTheme.typography.labelLarge, color = Color(0xFF64748B))
            Spacer(modifier = Modifier.height(10.dp))
            popularSearches.forEach { term ->
                SearchSuggestionRow(term, Icons.AutoMirrored.Filled.TrendingUp, onClick = { onHintClick(term) })
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun SearchSuggestionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(NavyCard)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Search for $label" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
