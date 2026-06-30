@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.streamverse.app.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamverse.app.ui.components.ChannelCard
import com.streamverse.app.ui.components.ChannelLogo
import com.streamverse.app.ui.components.HomeShimmer
import com.streamverse.app.ui.components.LiveBadge
import com.streamverse.app.ui.components.LocalLiveChannels
import com.streamverse.app.ui.components.QualityBadge
import com.streamverse.app.ui.components.accentColors
import com.streamverse.app.ui.player.LocalMiniPlayerInset
import com.streamverse.app.ui.theme.*
import com.streamverse.core.data.repository.ProgrammeRepository
import com.streamverse.core.domain.model.ChannelSummary
import com.streamverse.core.domain.model.*
import com.streamverse.core.util.CategoryNormalizer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    onChannelClick: (String) -> Unit,
    onGuideClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSeeAllClick: (type: String, value: String) -> Unit = { _, _ -> },
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val favIds by viewModel.favouriteIds.collectAsStateWithLifecycle()
    val recentlyWatched by viewModel.recentlyWatched.collectAsStateWithLifecycle()

    val channelsByCategory = remember(state.channels) { state.channels.groupBy { it.category } }
    val channelsByCountry = remember(state.channels) { state.channels.groupBy { it.country }.mapValues { (_, v) -> v } }
    val letterRows = remember(state.channels) { buildLetterRows(state.channels) }

    if (state.isLoading && state.channels.isEmpty()) {
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.statusBars)) {
            PremiumTopBar(timeOfDay = state.timeOfDay, onSearchClick = onSearchClick)
            HomeShimmer()
        }
        return
    }
    if (state.error != null && state.channels.isEmpty()) {
        ErrorScreen(message = state.error!!, onRetry = viewModel::retry)
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        TimeAwareBackground(
            timeOfDay = state.timeOfDay,
            featuredChannels = state.featured,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp + LocalMiniPlayerInset.current),
        ) {
            item(key = "topbar") {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    PremiumTopBar(timeOfDay = state.timeOfDay, onSearchClick = onSearchClick)
                    SortChipRow(
                        currentSort = state.sortMode,
                        onSortSelected = viewModel::setSortMode,
                    )
                }
            }

            // 1. Hero banner
            if (state.featured.isNotEmpty()) {
                item(key = "hero") {
                    HeroBanner(
                        channels = state.featured,
                        onChannelClick = onChannelClick,
                    )
                }
            }

            // 2. Dynamic sections from ranking engine
            for (section in state.sections) {
                when (section.type) {
                    SectionType.CONTINUE_WATCHING -> {
                        if (section.channels.isNotEmpty()) {
                            item(key = "continue_header") {
                                SectionHeader(title = section.title, icon = Icons.Default.History)
                            }
                            item(key = "continue_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(items = section.channels, key = { "recent_${it.id}" }) { ch ->
                                        LiveChannelCard(
                                            channel = ch.toSummary(),
                                            onClick = { onChannelClick(ch.id) },
                                            modifier = Modifier.width(160.dp),
                                            isFavourite = favIds.contains(ch.id),
                                            onToggleFavourite = { viewModel.toggleFavourite(ch.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SectionType.LIVE_EVENTS -> {
                        if (section.events.isNotEmpty()) {
                            item(key = "events_header") {
                                SectionHeader(title = section.title, icon = Icons.Default.LiveTv)
                            }
                            item(key = "events_row") {
                                FeaturedEventsRow(
                                    events = section.events,
                                    onEventClick = { event ->
                                        event.channelIds.firstOrNull()?.let(onChannelClick)
                                    },
                                )
                            }
                        }
                    }

                    SectionType.TRENDING -> {
                        if (section.trending.isNotEmpty()) {
                            item(key = "trending_header") {
                                SectionHeader(title = section.title, icon = Icons.Default.Whatshot)
                            }
                            item(key = "trending_row") {
                                TrendingRow(
                                    trending = section.trending,
                                    onChannelClick = onChannelClick,
                                    isFavourite = { favIds.contains(it) },
                                    onToggleFavourite = viewModel::toggleFavourite,
                                )
                            }
                        }
                    }

                    SectionType.EDITOR_PICKS -> {
                        if (section.channels.isNotEmpty()) {
                            item(key = "editors_header") {
                                SectionHeader(title = section.title, icon = Icons.Default.Star)
                            }
                            item(key = "editors_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(items = section.channels, key = { it.id }) { ch ->
                                        LiveChannelCard(
                                            channel = ch.toSummary(),
                                            onClick = { onChannelClick(ch.id) },
                                            modifier = Modifier.width(160.dp),
                                            isFavourite = favIds.contains(ch.id),
                                            onToggleFavourite = { viewModel.toggleFavourite(ch.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SectionType.POPULAR_IN_REGION -> {
                        if (section.channels.isNotEmpty()) {
                            item(key = "region_popular_header") {
                                SectionHeader(title = section.title, icon = Icons.Default.Place)
                            }
                            item(key = "region_popular_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(items = section.channels, key = { it.id }) { ch ->
                                        LiveChannelCard(
                                            channel = ch.toSummary(),
                                            onClick = { onChannelClick(ch.id) },
                                            modifier = Modifier.width(160.dp),
                                            isFavourite = favIds.contains(ch.id),
                                            onToggleFavourite = { viewModel.toggleFavourite(ch.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SectionType.TOP_NEWS -> {
                        if (section.programmes.isNotEmpty() || section.headlines.isNotEmpty()) {
                            item(key = "news_header") {
                                SectionHeader(title = section.title, icon = Icons.Default.Newspaper)
                            }
                            item(key = "news_row") {
                                NewsCentreRow(
                                    headlines = section.headlines,
                                    programmes = section.programmes,
                                    onChannelClick = onChannelClick,
                                )
                            }
                        }
                    }

                    SectionType.TOP_SPORTS -> {
                        if (section.programmes.isNotEmpty()) {
                            item(key = "sports_header") {
                                SectionHeader(title = section.title, icon = Icons.Default.EmojiEvents)
                            }
                            item(key = "sports_row") {
                                SportsCentreRow(
                                    scores = section.scores,
                                    programmes = section.programmes,
                                    onChannelClick = onChannelClick,
                                )
                            }
                        }
                    }

                    SectionType.BECAUSE_YOU_WATCH,
                    SectionType.POPULAR_WORLDWIDE,
                    SectionType.TOP_ENTERTAINMENT,
                    SectionType.TOP_MOVIES,
                    SectionType.TOP_DOCUMENTARIES,
                    SectionType.KIDS,
                    SectionType.MUSIC,
                    SectionType.RECOMMENDATIONS -> {
                        if (section.channels.isNotEmpty()) {
                            val icon = when (section.type) {
                                SectionType.BECAUSE_YOU_WATCH -> Icons.Default.Visibility
                                SectionType.POPULAR_WORLDWIDE -> Icons.Default.Language
                                SectionType.TOP_ENTERTAINMENT -> Icons.Default.TheaterComedy
                                SectionType.TOP_MOVIES -> Icons.Default.Movie
                                SectionType.TOP_DOCUMENTARIES -> Icons.Default.Videocam
                                SectionType.KIDS -> Icons.Default.Face
                                SectionType.MUSIC -> Icons.Default.MusicNote
                                SectionType.RECOMMENDATIONS -> Icons.Default.AutoAwesome
                                else -> null
                            }
                            item(key = "${section.id}_header") {
                                SectionHeader(title = section.title, icon = icon)
                            }
                            item(key = "${section.id}_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                items(items = section.channels, key = { it.id }) { ch ->
                                        LiveChannelCard(
                                            channel = ch.toSummary(),
                                            onClick = { onChannelClick(ch.id) },
                                            modifier = Modifier.width(160.dp),
                                            isFavourite = favIds.contains(ch.id),
                                            onToggleFavourite = { viewModel.toggleFavourite(ch.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SectionType.RECENTLY_ADDED -> {
                        if (section.channels.isNotEmpty()) {
                            item(key = "new_header") {
                                SectionHeader(title = section.title, icon = Icons.Default.NewReleases)
                            }
                            item(key = "new_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(items = section.channels, key = { it.id }) { ch ->
                                        LiveChannelCard(
                                            channel = ch.toSummary(),
                                            onClick = { onChannelClick(ch.id) },
                                            modifier = Modifier.width(140.dp),
                                            isFavourite = favIds.contains(ch.id),
                                            onToggleFavourite = { viewModel.toggleFavourite(ch.id) },
                                            isNew = true,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SectionType.FAVOURITES -> {
                        if (section.channels.isNotEmpty()) {
                            item(key = "favourites_header") {
                                SectionHeader(title = section.title, icon = Icons.Default.Favorite)
                            }
                            item(key = "favourites_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(items = section.channels, key = { it.id }) { ch ->
                                        LiveChannelCard(
                                            channel = ch.toSummary(),
                                            onClick = { onChannelClick(ch.id) },
                                            modifier = Modifier.width(140.dp),
                                            isFavourite = true,
                                            onToggleFavourite = { viewModel.toggleFavourite(ch.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SectionType.HERO_BANNER -> {}
                    SectionType.CATEGORY_BROWSING -> {}
                    else -> {}
                }
            }

            // TV Guide entry
            item(key = "guide_header") {
                SectionHeader(title = "TV Guide", icon = Icons.Default.Schedule)
            }
            item(key = "guide_entry") {
                TvGuideEntry(onGuideClick = onGuideClick)
            }

            // Category/Region/Alphabetical browsing
            when (state.sortMode) {
                SortMode.ALPHABETICAL -> {
                    for ((label, chs) in letterRows) {
                        item(key = "section_az_$label") {
                            SectionHeaderWithSeeAll(title = label, count = chs.size,
                                onSeeAll = { onSeeAllClick("az", label) })
                        }
                        item(key = "row_az_$label") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(items = chs, key = { it.id }) { ch ->
                                    LiveChannelCard(
                                        channel = ch, onClick = { onChannelClick(ch.id) },
                                        modifier = Modifier.width(140.dp),
                                        isFavourite = favIds.contains(ch.id),
                                        onToggleFavourite = { viewModel.toggleFavourite(ch.id) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                SortMode.CATEGORY, SortMode.REGION -> {
                    val categories = when (state.sortMode) {
                        SortMode.CATEGORY -> state.categories.ifEmpty { listOf("General") }
                        SortMode.REGION -> state.categories
                        else -> emptyList()
                    }
                    val sectionChannels: (String) -> List<ChannelSummary> = when (state.sortMode) {
                        SortMode.CATEGORY -> { s -> channelsByCategory[s] ?: emptyList() }
                        SortMode.REGION -> { s ->
                            if (s == "All Regions") state.channels
                            else channelsByCountry[s] ?: emptyList()
                        }
                        else -> { _ -> emptyList() }
                    }
                    categories.forEach { section ->
                        val chs = sectionChannels(section)
                        if (chs.isNotEmpty()) {
                            item(key = "section_header_$section") {
                                SectionHeaderWithSeeAll(
                                    title = section, count = chs.size,
                                    onSeeAll = { onSeeAllClick(
                                        if (state.sortMode == SortMode.REGION) "region" else "category", section) },
                                )
                            }
                            item(key = "cat_row_$section") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(items = chs.take(20), key = { it.id }) { ch ->
                                        LiveChannelCard(
                                            channel = ch, onClick = { onChannelClick(ch.id) },
                                            modifier = Modifier.width(140.dp),
                                            isFavourite = favIds.contains(ch.id),
                                            onToggleFavourite = { viewModel.toggleFavourite(ch.id) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            // Radio
            val radioChannels = channelsByCategory[CategoryNormalizer.C.RADIO] ?: emptyList()
            if (radioChannels.isNotEmpty()) {
                item(key = "radio_header") {
                    SectionHeaderWithSeeAll(title = "Radio", count = radioChannels.size,
                        onSeeAll = { onSeeAllClick("category", CategoryNormalizer.C.RADIO) })
                }
                item(key = "radio_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = radioChannels.take(20), key = { it.id }) { ch ->
                            ChannelCard(
                                channel = ch, onClick = { onChannelClick(ch.id) },
                                modifier = Modifier.width(140.dp),
                                isFavorite = favIds.contains(ch.id),
                                onToggleFavorite = { viewModel.toggleFavourite(ch.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            item(key = "footer") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ── 1. HERO BANNER ────────────────────────────────────────────────────────────

@Composable
private fun HeroBanner(
    channels: List<Channel>,
    onChannelClick: (String) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { channels.size.coerceAtLeast(1) })

    if (channels.size > 1) {
        LaunchedEffect(pagerState) {
            while (true) {
                delay(7000)
                if (!pagerState.isScrollInProgress) {
                    val next = (pagerState.currentPage + 1) % channels.size
                    pagerState.animateScrollToPage(next)
                }
            }
        }
    }

    Box(Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 420.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            val ch = channels.getOrNull(page) ?: return@HorizontalPager
            HeroCard(channel = ch, onClick = { onChannelClick(ch.id) })
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            channels.take(8).forEachIndexed { idx, ch ->
                val active = pagerState.currentPage == idx
                val color = accentColors(ch.displayName).first
                Box(
                    modifier = Modifier
                        .width(if (active) 28.dp else 8.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active) color else Color.White.copy(alpha = 0.25f)),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    channel: Channel,
    onClick: () -> Unit,
) {
    val (c1, c2) = accentColors(channel.displayName)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(c1.copy(alpha = 0.6f), c2.copy(alpha = 0.6f)))),
        )
        ChannelLogo(
            channel = channel,
            modifier = Modifier.fillMaxSize().alpha(0.15f),
            logoPadding = 0.dp,
        )

        Box(Modifier.matchParentSize().background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent, Color.Black.copy(alpha = 0.85f)))
        ))

        Box(
            Modifier.fillMaxWidth().height(3.dp)
                .align(Alignment.TopCenter)
                .background(c1),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                channel.category?.let { cat ->
                    Text(
                        text = cat.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = c1,
                        letterSpacing = 2.sp,
                        fontSize = 10.sp,
                    )
                }
                val isLive = channel.id in LocalLiveChannels.current
                if (isLive) HeroLiveBadge()
                channel.quality?.let { QualityBadge(quality = it, modifier = Modifier) }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = channel.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            channel.description?.let { desc ->
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .clickable(onClick = onClick)
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text(
                        "Watch Now",
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        fontSize = 13.sp,
                    )
                }
                channel.country?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp) }
                channel.language?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun HeroLiveBadge() {
    val infinite = rememberInfiniteTransition(label = "hero_live")
    val alpha by infinite.animateFloat(
        initialValue = 1f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "hero_live_alpha",
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFFFF3B30).copy(alpha = alpha))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("LIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 9.sp)
    }
}

// ── 2. LIVE CHANNEL CARD (no EPG programme overlay) ───────────────────────────

@Composable
private fun LiveChannelCard(
    channel: ChannelSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFavourite: Boolean = false,
    onToggleFavourite: (() -> Unit)? = null,
    isNew: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else if (isFocused) 1.06f else 1f,
        tween(150), label = "card_scale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isFocused) 8.dp else 0.dp,
        tween(200), label = "card_elevation",
    )

    Card(
        modifier = modifier.scale(scale),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = NavyCard),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        interactionSource = interactionSource,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0A0A0A))) {
            ChannelLogo(channel = channel, modifier = Modifier.matchParentSize())

            // Badges row
            Row(
                Modifier.align(Alignment.TopStart).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (channel.id in LocalLiveChannels.current) Box { LiveBadge() }
                channel.quality?.let { QualityBadge(quality = it) }
            }

            // New badge
            if (isNew) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF22C55E))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text("NEW", color = Color.White, fontWeight = FontWeight.Black, fontSize = 8.sp)
                }
            }

            if (onToggleFavourite != null) {
                IconButton(
                    onClick = onToggleFavourite,
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                ) {
                    Icon(
                        if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isFavourite) Color(0xFFF43F5E) else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Play button on focus
            if (isFocused) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(36.dp)
                            .background(Color.White.copy(alpha = 0.9f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Channel info overlay at bottom
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Column {
                    channel.category?.let { cat ->
                        Text(
                            cat, color = Color.White.copy(alpha = 0.7f),
                            fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        channel.country?.let {
                            Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 8.sp)
                        }
                        channel.language?.let {
                            Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 8.sp)
                        }
                    }
                }
            }
        }
        Text(
            channel.displayName,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFCCCCCC),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── 3. FEATURED EVENTS ROW ───────────────────────────────────────────────────

@Composable
private fun FeaturedEventsRow(
    events: List<LiveEvent>,
    onEventClick: (LiveEvent) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = events.take(8), key = { it.id }) { event ->
            EventCard(event = event, onClick = { onEventClick(event) })
        }
    }
}

@Composable
private fun EventCard(event: LiveEvent, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f, tween(150), label = "event_scale",
    )

    Card(
        modifier = Modifier.width(260.dp).scale(scale),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NavyCard),
        interactionSource = interactionSource,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.linearGradient(listOf(
                            eventColor(event.eventType).first.copy(alpha = 0.5f),
                            eventColor(event.eventType).second.copy(alpha = 0.5f),
                        ))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        eventIcon(event.eventType),
                        null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (event.isLive) Color(0xFFFF3B30) else Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    if (event.isLive) "● LIVE" else if (event.isUpcoming) formatCountdown(event.timeUntilStartMillis) else "Finished",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp,
                )
            }

            Column(
                Modifier.align(Alignment.BottomStart).padding(10.dp),
            ) {
                Text(event.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                event.competition?.let {
                    Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                }
                event.score?.let {
                    Text(it, color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

private fun eventIcon(type: EventType): ImageVector = when (type) {
    EventType.FOOTBALL -> Icons.Default.SportsSoccer
    EventType.BASKETBALL -> Icons.Default.SportsBasketball
    EventType.FORMULA1 -> Icons.Default.DirectionsCar
    EventType.CRICKET -> Icons.Default.SportsCricket
    EventType.TENNIS -> Icons.Default.SportsTennis
    EventType.CONCERT -> Icons.Default.MusicNote
    EventType.BREAKING_NEWS -> Icons.Default.Newspaper
    EventType.AWARDS -> Icons.Default.Star
    EventType.POLITICS -> Icons.Default.Gavel
    EventType.RELIGIOUS -> Icons.Default.Church
    EventType.ESPORTS -> Icons.Default.VideogameAsset
    EventType.GENERAL -> Icons.Default.LiveTv
}

private fun eventColor(type: EventType): Pair<Color, Color> = when (type) {
    EventType.FOOTBALL -> Color(0xFF059669) to Color(0xFF065F46)
    EventType.BASKETBALL -> Color(0xFFD97706) to Color(0xFF92400E)
    EventType.FORMULA1 -> Color(0xFFDC2626) to Color(0xFF991B1B)
    EventType.CRICKET -> Color(0xFF0891B2) to Color(0xFF155E75)
    EventType.TENNIS -> Color(0xFF7C3AED) to Color(0xFF5B21B6)
    EventType.CONCERT -> Color(0xFFEC4899) to Color(0xFFBE185D)
    EventType.BREAKING_NEWS -> Color(0xFFEF4444) to Color(0xFFB91C1C)
    EventType.AWARDS -> Color(0xFFF59E0B) to Color(0xFFB45309)
    EventType.POLITICS -> Color(0xFF6366F1) to Color(0xFF4338CA)
    EventType.RELIGIOUS -> Color(0xFFD97706) to Color(0xFF92400E)
    EventType.ESPORTS -> Color(0xFF8B5CF6) to Color(0xFF6D28D9)
    EventType.GENERAL -> Color(0xFF64748B) to Color(0xFF475569)
}

// ── 4. NEWS CENTRE ROW ───────────────────────────────────────────────────────

@Composable
private fun NewsCentreRow(
    headlines: List<NewsHeadline>,
    programmes: List<ChannelProgramme>,
    onChannelClick: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = headlines.take(5), key = { it.id }) { hl ->
            NewsHeadlineCard(headline = hl, onClick = {
                hl.channelId?.let(onChannelClick)
            })
        }
        items(items = programmes.take(4), key = { "news_${it.channel.id}" }) { cp ->
            OnNowCard(cp = cp, onClick = { onChannelClick(cp.channel.id) })
        }
    }
}

@Composable
private fun NewsHeadlineCard(headline: NewsHeadline, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(180.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (headline.isBreaking) Color(0xFF7F1D1D) else NavyCard
        ),
        onClick = onClick,
    ) {
        Column(Modifier.padding(12.dp)) {
            if (headline.isBreaking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val transition = rememberInfiniteTransition(label = "breaking")
                    val alpha by transition.animateFloat(1f, 0.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "breaking_alpha")
                    Box(Modifier.size(6.dp).background(Color(0xFFEF4444).copy(alpha = alpha), CircleShape))
                    Text("BREAKING", color = Color(0xFFEF4444), fontWeight = FontWeight.Black, fontSize = 9.sp)
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(headline.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            headline.summary?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                headline.source?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp) }
                Text(
                    formatTimestamp(headline.timestamp),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun OnNowCard(cp: ChannelProgramme, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        modifier = Modifier.width(200.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = NavyCard),
        interactionSource = interactionSource,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0A0A0A))) {
            ChannelLogo(channel = cp.channel, modifier = Modifier.matchParentSize())

            if (cp.channel.id in LocalLiveChannels.current) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFFF3B30))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text("LIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 9.sp)
                }
            }
        }
        Text(
            cp.channel.displayName,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            color = Color(0xFFCCCCCC), fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── 5. SPORTS CENTRE ROW ─────────────────────────────────────────────────────

@Composable
private fun SportsCentreRow(
    scores: List<LiveScore>,
    programmes: List<ChannelProgramme>,
    onChannelClick: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = scores.take(8), key = { it.eventId }) { score ->
            LiveScoreCard(score = score)
        }
        items(items = programmes.take(4), key = { "sports_${it.channel.id}" }) { cp ->
            OnNowCard(cp = cp, onClick = { onChannelClick(cp.channel.id) })
        }
    }
}

@Composable
private fun LiveScoreCard(score: LiveScore) {
    Card(
        modifier = Modifier.width(180.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = NavyCard),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(score.competition, color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(score.homeTeam, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(score.awayTeam, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (score.homeScore != null && score.awayScore != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${score.homeScore}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("${score.awayScore}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (score.status == "Live") {
                    val t = rememberInfiniteTransition(label = "score_live")
                    val a by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "score_a")
                    Box(Modifier.size(5.dp).background(Color(0xFF4ADE80).copy(alpha = a), CircleShape))
                }
                Text(score.status, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                score.minute?.let { Text(it, color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp) }
            }
        }
    }
}

// ── 6. TRENDING ROW ──────────────────────────────────────────────────────────

@Composable
private fun TrendingRow(
    trending: List<TrendingChannel>,
    onChannelClick: (String) -> Unit,
    isFavourite: (String) -> Boolean,
    onToggleFavourite: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = trending.take(15), key = { it.channel.id }) { tc ->
            TrendingCard(
                trending = tc,
                isFavourite = isFavourite(tc.channel.id),
                onToggleFavourite = { onToggleFavourite(tc.channel.id) },
                onClick = { onChannelClick(tc.channel.id) },
            )
        }
    }
}

@Composable
private fun TrendingCard(
    trending: TrendingChannel,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier.width(150.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = NavyCard),
        interactionSource = interactionSource,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0A0A0A))) {
            ChannelLogo(channel = trending.channel, modifier = Modifier.matchParentSize())
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text("#${trending.rank}", color = Color(0xFF22D3EE), fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(trending.channel.displayName, color = Color(0xFFCCCCCC), fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trending.reason, color = Color(0xFF22D3EE), fontSize = 9.sp)
        }
    }
}

// ── 7. TV GUIDE ENTRY ────────────────────────────────────────────────────────

@Composable
private fun TvGuideEntry(onGuideClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onGuideClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NavyCard),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CyberCyan.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Full TV Guide",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Text(
                    "Browse all channels with real-time listings",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── TIME-AWARE BACKGROUND ────────────────────────────────────────────────────

@Composable
private fun TimeAwareBackground(
    timeOfDay: TimeOfDay,
    featuredChannels: List<Channel>,
) {
    val accent = Color(timeOfDay.accentColor)
    val dominantColor = remember(featuredChannels) {
        featuredChannels.firstOrNull()?.let { ch ->
            val (c1, _) = accentColors(ch.displayName)
            c1
        } ?: accent
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black)
        )
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            dominantColor.copy(alpha = 0.08f),
                            Color.Black.copy(alpha = 0f),
                            Color.Black,
                        ),
                        startY = 0f,
                        endY = 1200f,
                    )
                )
        )
    }
}

// ── TOP BAR ───────────────────────────────────────────────────────────────────

@Composable
private fun PremiumTopBar(timeOfDay: TimeOfDay, onSearchClick: () -> Unit) {
    val accent = Color(timeOfDay.accentColor)
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(accent, CircleShape))
                Text(
                    "STREAMVERSE",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp,
                    fontSize = 14.sp,
                )
            }
            Text(
                timeOfDay.label.uppercase(),
                color = Color.White.copy(alpha = 0.5f),
                letterSpacing = 1.sp,
                fontSize = 9.sp,
            )
        }
        IconButton(onClick = onSearchClick, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Search, "Search", tint = Color(0xFF999999), modifier = Modifier.size(22.dp))
        }
    }
}

// ── SHARED COMPONENTS ─────────────────────────────────────────────────────────

@Composable
private fun SortChipRow(currentSort: SortMode, onSortSelected: (SortMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SortMode.entries.forEach { mode ->
            val selected = currentSort == mode
            val bgColor = if (selected) Color(0xFF22D3EE) else Color(0xFF1E293B)
            val textColor = if (selected) Color.Black else Color(0xFF94A3B8)
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bgColor)
                    .clickable { onSortSelected(mode) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    when (mode) {
                        SortMode.CATEGORY -> "Category"
                        SortMode.ALPHABETICAL -> "A-Z"
                        SortMode.REGION -> "Region"
                    },
                    color = textColor,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String, icon: ImageVector? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let {
            Icon(it, null, tint = Color(0xFF22D3EE), modifier = Modifier.size(16.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun SectionHeaderWithSeeAll(title: String, count: Int, onSeeAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(
            "$count channels",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
        )
        TextButton(onClick = onSeeAll) {
            Text("See All", color = Color(0xFF22D3EE), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Something went wrong", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22D3EE))) {
            Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

// ── UTILITY FUNCTIONS ─────────────────────────────────────────────────────────

private fun buildLetterRows(channels: List<ChannelSummary>): List<Pair<String, List<ChannelSummary>>> {
    if (channels.isEmpty()) return emptyList()
    val grouped = channels.groupBy { it.displayName.first().uppercase() }
    val order = listOf("0-9") + ('A'..'Z').map { it.toString() }
    return order.mapNotNull { letter ->
        val chs = when (letter) {
            "0-9" -> grouped.filterKeys { it[0].isDigit() }.values.flatten()
            else -> grouped[letter] ?: return@mapNotNull null
        }.ifEmpty { return@mapNotNull null }
        letter to chs
    }
}

private fun formatTimestamp(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val mins = diff / 60_000
    return when {
        mins < 1 -> "Just now"
        mins < 60 -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}

private fun formatCountdown(ms: Long): String {
    if (ms <= 0) return "Now"
    val mins = ms / 60_000
    if (mins < 60) return "${mins}m"
    val hrs = mins / 60
    val remMins = mins % 60
    return "${hrs}h ${remMins}m"
}
