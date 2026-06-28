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
import com.streamverse.app.ui.components.QualityBadge
import com.streamverse.app.ui.components.accentColors
import com.streamverse.app.ui.player.LocalMiniPlayerInset
import com.streamverse.app.ui.theme.*
import com.streamverse.core.data.repository.ProgrammeRepository
import com.streamverse.core.domain.model.*
import com.streamverse.core.util.CategoryNormalizer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Main Home Screen ──────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    onChannelClick: (String) -> Unit,
    onScheduleClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSeeAllClick: (type: String, value: String) -> Unit = { _, _ -> },
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val favIds by viewModel.favouriteIds.collectAsStateWithLifecycle()
    val recentlyWatched by viewModel.recentlyWatched.collectAsStateWithLifecycle()

    val channelsByCategory = remember(state.channels) { state.channels.groupBy { it.category } }
    val channelsByCountry = remember(state.channels) { state.channels.groupBy { it.country } }
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
        // Dynamic background
        TimeAwareBackground(
            timeOfDay = state.timeOfDay,
            featuredChannels = state.featured,
            featuredProgrammes = state.featuredProgrammes,
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

            // 1. DYNAMIC HERO BANNER
            if (state.featured.isNotEmpty()) {
                item(key = "hero") {
                    HeroBanner(
                        channels = state.featured,
                        programmes = state.featuredProgrammes,
                        onChannelClick = onChannelClick,
                    )
                }
            }

            // 2. CONTINUE WATCHING
            if (recentlyWatched.isNotEmpty()) {
                item(key = "continue_header") {
                    SectionHeader(title = "Continue Watching", icon = Icons.Default.History)
                }
                item(key = "continue_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = recentlyWatched, key = { "recent_${it.id}" }) { ch ->
                            LiveChannelCard(
                                channel = ch,
                                programme = state.featuredProgrammes.firstOrNull { it.channel.id == ch.id },
                                onClick = { onChannelClick(ch.id) },
                                modifier = Modifier.width(160.dp),
                                isFavourite = favIds.contains(ch.id),
                                onToggleFavourite = { viewModel.toggleFavourite(ch) },
                            )
                        }
                    }
                }
            }

            // 3. FEATURED LIVE EVENTS
            if (state.liveEvents.isNotEmpty()) {
                item(key = "events_header") {
                    SectionHeader(title = "Live Events", icon = Icons.Default.LiveTv)
                }
                item(key = "events_row") {
                    FeaturedEventsRow(
                        events = state.liveEvents,
                        onEventClick = { event ->
                            event.channelIds.firstOrNull()?.let(onChannelClick)
                        },
                    )
                }
            }

            // 4. ON NOW — LIVE PROGRAMME TIMELINE
            if (state.onNow.isNotEmpty()) {
                item(key = "onnow_header") {
                    SectionHeader(title = "What's On Now", icon = Icons.Default.LiveTv)
                }
                item(key = "onnow_row") {
                    WhatsOnNowRow(
                        programmes = state.onNow,
                        onChannelClick = onChannelClick,
                    )
                }
            }

            // 5. NEWS CENTRE
            if (state.newsChannels.isNotEmpty()) {
                item(key = "news_header") {
                    SectionHeader(title = "News Centre", icon = Icons.Default.Newspaper)
                }
                item(key = "news_row") {
                    NewsCentreRow(
                        headlines = state.headlines,
                        programmes = state.newsChannels,
                        onChannelClick = onChannelClick,
                    )
                }
            }

            // 6. SPORTS CENTRE
            if (state.sportsChannels.isNotEmpty()) {
                item(key = "sports_header") {
                    SectionHeader(title = "Sports Centre", icon = Icons.Default.EmojiEvents)
                }
                item(key = "sports_row") {
                    SportsCentreRow(
                        scores = state.liveScores,
                        programmes = state.sportsChannels,
                        onChannelClick = onChannelClick,
                    )
                }
            }

            // 7. TRENDING LIVE CHANNELS
            if (state.trending.isNotEmpty()) {
                item(key = "trending_header") {
                    SectionHeader(title = "Trending Live", icon = Icons.Default.Whatshot)
                }
                item(key = "trending_row") {
                    TrendingRow(
                        trending = state.trending,
                        onChannelClick = onChannelClick,
                        isFavourite = { favIds.contains(it) },
                        onToggleFavourite = viewModel::toggleFavourite,
                    )
                }
            }

            // 8. MINI EPG GUIDE
            if (state.miniGuide.isNotEmpty()) {
                item(key = "miniguide_header") {
                    SectionHeader(title = "TV Guide", icon = Icons.Default.CalendarMonth)
                }
                item(key = "miniguide") {
                    MiniGuide(
                        entries = state.miniGuide,
                        onChannelClick = onChannelClick,
                    )
                }
            }

            // 9. KIDS
            if (state.kidsChannels.isNotEmpty()) {
                item(key = "kids_header") {
                    SectionHeader(title = "Kids", icon = Icons.Default.Face)
                }
                item(key = "kids_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = state.kidsChannels.take(15), key = { it.id }) { ch ->
                            LiveChannelCard(
                                channel = ch,
                                onClick = { onChannelClick(ch.id) },
                                modifier = Modifier.width(140.dp),
                                isFavourite = favIds.contains(ch.id),
                                onToggleFavourite = { viewModel.toggleFavourite(ch) },
                            )
                        }
                    }
                }
            }

            // 10. MUSIC
            if (state.musicChannels.isNotEmpty()) {
                item(key = "music_header") {
                    SectionHeader(title = "Music", icon = Icons.Default.MusicNote)
                }
                item(key = "music_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = state.musicChannels.take(15), key = { it.id }) { ch ->
                            LiveChannelCard(
                                channel = ch,
                                onClick = { onChannelClick(ch.id) },
                                modifier = Modifier.width(140.dp),
                                isFavourite = favIds.contains(ch.id),
                                onToggleFavourite = { viewModel.toggleFavourite(ch) },
                            )
                        }
                    }
                }
            }

            // 11. RECENTLY ADDED
            if (state.recentlyAdded.isNotEmpty()) {
                item(key = "new_header") {
                    SectionHeader(title = "Recently Added", icon = Icons.Default.NewReleases)
                }
                item(key = "new_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = state.recentlyAdded.take(15), key = { it.id }) { ch ->
                            LiveChannelCard(
                                channel = ch,
                                onClick = { onChannelClick(ch.id) },
                                modifier = Modifier.width(140.dp),
                                isFavourite = favIds.contains(ch.id),
                                onToggleFavourite = { viewModel.toggleFavourite(ch) },
                                isNew = true,
                            )
                        }
                    }
                }
            }

            // 12. REGIONAL HUB
            if (state.regionalChannels.isNotEmpty()) {
                item(key = "regional_header") {
                    SectionHeader(title = "Regional TV", icon = Icons.Default.Public)
                }
                for ((region, chs) in state.regionalChannels.entries.take(8)) {
                    item(key = "region_${region}_header") {
                        SectionHeaderWithSeeAll(
                            title = region,
                            count = chs.size,
                            onSeeAll = { onSeeAllClick("region", region) },
                        )
                    }
                    item(key = "region_${region}_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(items = chs.take(10), key = { it.id }) { ch ->
                                LiveChannelCard(
                                    channel = ch,
                                    onClick = { onChannelClick(ch.id) },
                                    modifier = Modifier.width(140.dp),
                                    isFavourite = favIds.contains(ch.id),
                                    onToggleFavourite = { viewModel.toggleFavourite(ch) },
                                )
                            }
                        }
                    }
                }
            }

            // 13. FAVOURITES
            val favChannels = state.channels.filter { favIds.contains(it.id) }
            if (favChannels.isNotEmpty()) {
                item(key = "favourites_header") {
                    SectionHeader(title = "My Favourites", icon = Icons.Default.Favorite)
                }
                item(key = "favourites_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = favChannels.take(20), key = { it.id }) { ch ->
                            LiveChannelCard(
                                channel = ch,
                                programme = state.featuredProgrammes.firstOrNull { it.channel.id == ch.id },
                                onClick = { onChannelClick(ch.id) },
                                modifier = Modifier.width(140.dp),
                                isFavourite = true,
                                onToggleFavourite = { viewModel.toggleFavourite(ch) },
                            )
                        }
                    }
                }
            }

            // 14. RECOMMENDATIONS
            if (state.recommendations.isNotEmpty()) {
                item(key = "recs_header") {
                    SectionHeader(title = "Recommended For You", icon = Icons.Default.AutoAwesome)
                }
                item(key = "recs_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = state.recommendations.take(15), key = { it.id }) { ch ->
                            LiveChannelCard(
                                channel = ch,
                                programme = state.featuredProgrammes.firstOrNull { it.channel.id == ch.id },
                                onClick = { onChannelClick(ch.id) },
                                modifier = Modifier.width(140.dp),
                                isFavourite = favIds.contains(ch.id),
                                onToggleFavourite = { viewModel.toggleFavourite(ch) },
                            )
                        }
                    }
                }
            }

            // 15. CATEGORY BROWSING
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
                                        onToggleFavourite = { viewModel.toggleFavourite(ch) },
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
                    val sectionChannels: (String) -> List<Channel> = when (state.sortMode) {
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
                                            onToggleFavourite = { viewModel.toggleFavourite(ch) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            // 16. RADIO
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
                                onToggleFavorite = { viewModel.toggleFavourite(ch) },
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
    programmes: List<ChannelProgramme>,
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
            val prog = programmes.getOrNull(page)
            HeroCard(channel = ch, programme = prog, onClick = { onChannelClick(ch.id) })
        }

        // Channel indicator bar
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
    programme: ChannelProgramme?,
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
        // Background artwork
        Box(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(c1.copy(alpha = 0.6f), c2.copy(alpha = 0.6f)))),
        )
        ChannelLogo(
            channel = channel,
            modifier = Modifier.fillMaxSize().alpha(0.15f),
            logoPadding = 0.dp,
        )

        // Scrim overlays
        Box(Modifier.matchParentSize().background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent, Color.Black.copy(alpha = 0.85f)))
        ))

        // Gradient accent bar at top
        Box(
            Modifier.fillMaxWidth().height(3.dp)
                .align(Alignment.TopCenter)
                .background(c1),
        )

        // Content
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            // Category + Live badge
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
                HeroLiveBadge()
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

            // Programme info
            programme?.currentProgramme?.let { p ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = p.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                p.synopsis?.let { syn ->
                    if (syn.isNotBlank()) {
                        Text(
                            text = syn,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Progress bar
                ProgrammeProgressBar(progress = p.progressFraction, accent = c1)

                // Row: time remaining & next programme
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val remMin = (p.remainingMillis / 60_000).toInt()
                    Text(
                        text = "${remMin}m remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    programme.nextProgramme?.let { next ->
                        Text(
                            text = "Up Next: ${next.title}",
                            style = MaterialTheme.typography.labelSmall,
                            color = c1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Meta row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Watch Now button
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
                // Channel info
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

@Composable
private fun ProgrammeProgressBar(progress: Float, accent: Color) {
    Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(fraction = progress.coerceIn(0f, 1f)).fillMaxHeight().background(accent))
    }
}

// ── 2. LIVE CHANNEL CARD (with programme overlay) ────────────────────────────

@Composable
private fun LiveChannelCard(
    channel: Channel,
    programme: ChannelProgramme? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFavourite: Boolean = false,
    onToggleFavourite: ((Channel) -> Unit)? = null,
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

            // Programme overlay at bottom
            programme?.currentProgramme?.let { p ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Column {
                        Text(
                            p.title, color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        ProgrammeProgressBar(progress = p.progressFraction, accent = CyberCyan)
                    }
                }
            }

            // Badges
            Row(
                Modifier.align(Alignment.TopStart).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box { LiveBadge() }
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

            // Favourite
            if (onToggleFavourite != null) {
                IconButton(
                    onClick = { onToggleFavourite(channel) },
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

            // Status badge
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

            // Content
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

// ── 4. WHAT'S ON NOW ROW ─────────────────────────────────────────────────────

@Composable
private fun WhatsOnNowRow(
    programmes: List<ChannelProgramme>,
    onChannelClick: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = programmes.take(15), key = { it.channel.id }) { cp ->
            OnNowCard(cp = cp, onClick = { onChannelClick(cp.channel.id) })
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

            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFF3B30))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text("LIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 9.sp)
            }

            cp.currentProgramme?.let { p ->
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(8.dp),
                ) {
                    Column {
                        Text(p.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        ProgrammeProgressBar(progress = p.progressFraction, accent = CyberCyan)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${(p.remainingMillis / 60_000).toInt()}m left",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 9.sp,
                        )
                    }
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

// ── 5. NEWS CENTRE ROW ───────────────────────────────────────────────────────

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
        // Breaking headlines
        items(items = headlines.take(5), key = { it.id }) { hl ->
            NewsHeadlineCard(headline = hl, onClick = {
                hl.channelId?.let(onChannelClick)
            })
        }
        // Live news channels
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

// ── 6. SPORTS CENTRE ROW ─────────────────────────────────────────────────────

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

// ── 7. TRENDING ROW ──────────────────────────────────────────────────────────

@Composable
private fun TrendingRow(
    trending: List<TrendingChannel>,
    onChannelClick: (String) -> Unit,
    isFavourite: (String) -> Boolean,
    onToggleFavourite: (Channel) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = trending.take(15), key = { it.channel.id }) { tc ->
            TrendingCard(
                trending = tc,
                isFavourite = isFavourite(tc.channel.id),
                onToggleFavourite = { onToggleFavourite(tc.channel) },
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
            // Rank badge
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

// ── 8. MINI EPG GUIDE ────────────────────────────────────────────────────────

@Composable
private fun MiniGuide(
    entries: Map<String, List<EpgEntry>>,
    onChannelClick: (String) -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Header
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp)) {
            Text("Channel", Modifier.width(80.dp), color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text("Now", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("Next", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
        entries.entries.take(6).forEach { (chId, epgList) ->
            val nowEntry = epgList.find { it.isNow }
            val nextEntry = epgList.find { it.isNext }
            if (nowEntry != null) {
                MiniGuideRow(
                    channelId = chId,
                    nowEntry = nowEntry,
                    nextEntry = nextEntry,
                    onClick = { onChannelClick(chId) },
                )
            }
        }
    }
}

@Composable
private fun MiniGuideRow(
    channelId: String,
    nowEntry: EpgEntry,
    nextEntry: EpgEntry?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(NavyCard)
            .clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Channel id short name
        Box(
            Modifier.width(74.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val label = channelId.replace("dlhd_", "").replace("iptv_", "").replace("radio_", "")
            Text(label.take(12), color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Now programme
        Column(Modifier.weight(1f)) {
            Text(
                nowEntry.programme.title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProgrammeProgressBar(progress = nowEntry.programme.progressFraction, accent = CyberCyan)
        }
        Spacer(Modifier.width(4.dp))
        // Next programme
        if (nextEntry != null) {
            Text(
                nextEntry.programme.title,
                Modifier.width(80.dp),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── TIME-AWARE BACKGROUND ────────────────────────────────────────────────────

@Composable
private fun TimeAwareBackground(
    timeOfDay: TimeOfDay,
    featuredChannels: List<Channel>,
    featuredProgrammes: List<ChannelProgramme>,
) {
    val accent = Color(timeOfDay.accentColor)
    val dominantColor = remember(featuredChannels, featuredProgrammes) {
        featuredProgrammes.firstOrNull()?.let { cp ->
            val (c1, _) = accentColors(cp.channel.displayName)
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
private fun SectionHeader(title: String, icon: ImageVector? = null) {
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
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "See All", style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, color = Color(0xFF999999),
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onSeeAll)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SortChipRow(currentSort: SortMode, onSortSelected: (SortMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SortMode.entries.forEach { mode ->
            val selected = mode == currentSort
            Box(
                Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (selected) Color.White else Color.White.copy(alpha = 0.1f))
                    .clickable { onSortSelected(mode) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mode.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) Color.Black else Color(0xFF999999),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Could not connect", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(message, style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                Icon(Icons.Default.Refresh, null, tint = Color.Black)
                Spacer(Modifier.width(6.dp))
                Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── UTILITY FUNCTIONS ─────────────────────────────────────────────────────────

private fun buildLetterRows(channels: List<Channel>): List<Pair<String, List<Channel>>> = buildList {
    val digitCh = channels.filter { ch ->
        val first = ch.displayName.lowercase().firstOrNull()
        first != null && first in '0'..'9'
    }
    if (digitCh.isNotEmpty()) add("0-9" to digitCh)
    for (letter in 'A'..'Z') {
        val lc = letter.lowercase()[0]
        val lcCh = channels.filter { ch ->
            val first = ch.displayName.lowercase().firstOrNull()
            first != null && first == lc
        }
        if (lcCh.isNotEmpty()) add(letter.toString() to lcCh)
    }
}

private fun formatCountdown(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    if (totalSec <= 0) return "Starting..."
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatTimestamp(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    val min = (diff / 60_000).toInt()
    return when {
        min < 1 -> "Just now"
        min < 60 -> "${min}m ago"
        min < 1440 -> "${min / 60}h ago"
        else -> "${min / 1440}d ago"
    }
}
