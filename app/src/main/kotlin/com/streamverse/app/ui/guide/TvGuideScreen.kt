@file:OptIn(ExperimentalLayoutApi::class)

package com.streamverse.app.ui.guide

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamverse.app.ui.components.ChannelLogo
import com.streamverse.app.ui.theme.CyberCyan
import com.streamverse.app.ui.theme.NavyCard
import com.streamverse.app.ui.theme.SpaceNavy
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.EpgEntry
import com.streamverse.core.domain.model.Programme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val HOUR_WIDTH_DP = 130
private const val CHANNEL_RAIL_WIDTH_DP = 72
private const val NOW_LINE_WIDTH_DP = 2
private const val MIN_PROGRAMME_WIDTH_DP = 6
private const val CHANNEL_ROW_HEIGHT_DP = 52

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvGuideScreen(
    onChannelClick: (String) -> Unit,
    onBackClick: () -> Unit,
    viewModel: TvGuideViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val horizontalScrollState = rememberScrollState()
    var guideWidthPx by remember { mutableIntStateOf(0) }

    val filteredChannels = remember(state.channels, state.selectedCategory) {
        if (state.selectedCategory == null) state.channels
        else state.channels.filter { it.category == state.selectedCategory }
    }

    val dayStartMillis = remember { dayStart() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(guideWidthPx) {
        if (guideWidthPx > 0) {
            val nowOffset = ((System.currentTimeMillis() - dayStartMillis) / 3600000f * HOUR_WIDTH_DP)
            val halfScreen = (guideWidthPx / 2) / density.density
            val scrollTarget = (nowOffset - halfScreen).coerceAtLeast(0f).roundToInt()
            horizontalScrollState.scrollTo(scrollTarget)
        }
    }

    Scaffold(
        containerColor = SpaceNavy,
        topBar = {
            TvGuideTopBar(
                onBackClick = onBackClick,
                onNowClick = {
                    val nowOffset = ((System.currentTimeMillis() - dayStartMillis) / 3600000f * HOUR_WIDTH_DP)
                    val halfScreen = (guideWidthPx / 2) / density.density
                    val target = (nowOffset - halfScreen).coerceAtLeast(0f).roundToInt()
                    scope.launch {
                        horizontalScrollState.animateScrollTo(target)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .onSizeChanged { guideWidthPx = it.width },
        ) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyberCyan)
                }
                return@Scaffold
            }

            val errorMsg = state.error
            if (errorMsg != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Something went wrong",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            errorMsg,
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                }
                return@Scaffold
            }

            if (filteredChannels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No TV Listings Available",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Check your internet connection and source settings",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                }
                return@Scaffold
            }

            CategoryFilterRow(
                categories = state.categories,
                selectedCategory = state.selectedCategory,
                onCategorySelected = viewModel::selectCategory,
            )

            GuideGrid(
                channels = filteredChannels,
                epgData = state.epgData,
                currentTimeMillis = state.currentTimeMillis,
                dayStartMillis = dayStartMillis,
                horizontalScrollState = horizontalScrollState,
                onChannelClick = onChannelClick,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvGuideTopBar(
    onBackClick: () -> Unit,
    onNowClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("TV Guide", fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        },
        actions = {
            TextButton(onClick = onNowClick) {
                Text("Now", color = CyberCyan, fontWeight = FontWeight.SemiBold)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = SpaceNavy),
    )
}

@Composable
private fun CategoryFilterRow(
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedCategory == null,
            onClick = { onCategorySelected(null) },
            label = { Text("All", fontSize = 12.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
                selectedLabelColor = CyberCyan,
                containerColor = NavyCard,
                labelColor = Color.White.copy(alpha = 0.7f),
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selectedCategory == null,
                borderColor = CyberCyan.copy(alpha = 0.3f),
                selectedBorderColor = CyberCyan,
            ),
        )
        categories.forEach { cat ->
            FilterChip(
                selected = selectedCategory == cat,
                onClick = { onCategorySelected(if (selectedCategory == cat) null else cat) },
                label = { Text(cat, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
                    selectedLabelColor = CyberCyan,
                    containerColor = NavyCard,
                    labelColor = Color.White.copy(alpha = 0.7f),
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedCategory == cat,
                    borderColor = NavyCard,
                    selectedBorderColor = CyberCyan,
                ),
            )
        }
    }
}

@Composable
private fun GuideGrid(
    channels: List<Channel>,
    epgData: Map<String, List<EpgEntry>>,
    currentTimeMillis: Long,
    dayStartMillis: Long,
    horizontalScrollState: ScrollState,
    onChannelClick: (String) -> Unit,
) {
    val dayEndMillis = dayStartMillis + 24 * 3600_000L
    val totalWidthDp = (24 * HOUR_WIDTH_DP).dp

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Time header
            TimeHeaderRow(
                dayStartMillis = dayStartMillis,
                totalWidthDp = totalWidthDp,
                horizontalScrollState = horizontalScrollState,
            )

            // Channel rows
            channels.forEach { channel ->
                val programmes = epgData[channel.id]?.filter { epg ->
                    epg.programme.startTimeMillis in dayStartMillis until dayEndMillis
                            || epg.programme.endTimeMillis in dayStartMillis until dayEndMillis
                }?.sortedBy { it.programme.startTimeMillis } ?: emptyList()

                ChannelRow(
                    channel = channel,
                    programmes = programmes,
                    totalWidthDp = totalWidthDp,
                    dayStartMillis = dayStartMillis,
                    currentTimeMillis = currentTimeMillis,
                    horizontalScrollState = horizontalScrollState,
                    onClick = { onChannelClick(channel.id) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // Now line — positioned at the current time, synced with horizontal scroll
        NowLine(
            currentTimeMillis = currentTimeMillis,
            dayStartMillis = dayStartMillis,
            horizontalScrollState = horizontalScrollState,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TimeHeaderRow(
    dayStartMillis: Long,
    totalWidthDp: Dp,
    horizontalScrollState: ScrollState,
) {
    val density = LocalDensity.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(horizontalScrollState)
            .background(SpaceNavy)
            .padding(start = CHANNEL_RAIL_WIDTH_DP.dp, top = 4.dp, bottom = 2.dp),
    ) {
        Box(Modifier.width(totalWidthDp).height(24.dp)) {
            for (hour in 0 until 24) {
                val leftDp = (hour * HOUR_WIDTH_DP).dp
                val timeStr = String.format("%02d:00", hour)
                Row(
                    Modifier.offset(x = leftDp).width(HOUR_WIDTH_DP.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        timeStr,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (hour > 0) {
                    VerticalDivider(
                        modifier = Modifier.offset(x = leftDp).height(24.dp),
                        thickness = 0.5.dp,
                        color = Color.White.copy(alpha = 0.08f),
                    )
                }
            }
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
}

@Composable
private fun ChannelRow(
    channel: Channel,
    programmes: List<EpgEntry>,
    totalWidthDp: Dp,
    dayStartMillis: Long,
    currentTimeMillis: Long,
    horizontalScrollState: ScrollState,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val rowModifier = Modifier
        .fillMaxWidth()
        .height(CHANNEL_ROW_HEIGHT_DP.dp)
        .clickable(onClick = onClick)

    Row(
        rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed left rail: channel logo + name
        Box(
            Modifier
                .width(CHANNEL_RAIL_WIDTH_DP.dp)
                .fillMaxHeight()
                .background(SpaceNavy),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                ChannelLogo(
                    channel = channel,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    channel.displayName,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Programme area — horizontal scroll synced with the header
        Box(
            Modifier
                .fillMaxHeight()
                .horizontalScroll(horizontalScrollState),
        ) {
            Box(Modifier.width(totalWidthDp).fillMaxHeight()) {
                programmes.forEach { epg ->
                    val prog = epg.programme
                    val left = ((prog.startTimeMillis - dayStartMillis) / 3600000f * HOUR_WIDTH_DP).dp
                    val widthDp = maxOf(
                        ((prog.endTimeMillis - prog.startTimeMillis) / 3600000f * HOUR_WIDTH_DP).dp,
                        MIN_PROGRAMME_WIDTH_DP.dp,
                    )
                    ProgrammeCard(
                        programme = prog,
                        isNow = epg.isNow,
                        modifier = Modifier
                            .offset(x = left)
                            .width(widthDp)
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp, vertical = 4.dp),
                        onClick = onClick,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.05f), thickness = 0.5.dp)
}

@Composable
private fun ProgrammeCard(
    programme: Programme,
    isNow: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bg = if (isNow) Color(0xFF1A3A4A) else NavyCard
    val border = if (isNow) CyberCyan.copy(alpha = 0.3f) else Color.Transparent
    val progress = if (isNow && programme.endTimeMillis > programme.startTimeMillis) {
        (System.currentTimeMillis() - programme.startTimeMillis).toFloat() /
                (programme.endTimeMillis - programme.startTimeMillis)
    } else 0f

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = BorderStroke(if (isNow) 1.dp else 0.dp, border),
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
                if (isNow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "LIVE",
                            color = CyberCyan,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            formatTime(programme.startTimeMillis),
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 6.sp,
                        )
                    }
                } else {
                    Text(
                        formatTime(programme.startTimeMillis),
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 6.sp,
                    )
                }
                Text(
                    programme.title,
                    color = if (isNow) Color.White else Color.White.copy(alpha = 0.85f),
                    fontSize = if (isNow) 9.sp else 8.sp,
                    fontWeight = if (isNow) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isNow && progress in 0f..1f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress)
                        .height(2.dp)
                        .background(CyberCyan.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
}

@Composable
private fun NowLine(
    currentTimeMillis: Long,
    dayStartMillis: Long,
    horizontalScrollState: ScrollState,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val offsetHours = (currentTimeMillis - dayStartMillis) / 3600000f
    val scrollOffsetDp = with(density) { horizontalScrollState.value.toDp() }
    val lineOffset = (offsetHours * HOUR_WIDTH_DP).dp + CHANNEL_RAIL_WIDTH_DP.dp - scrollOffsetDp

    Box(modifier) {
        Box(
            Modifier
                .offset(x = lineOffset)
                .width(NOW_LINE_WIDTH_DP.dp)
                .fillMaxHeight()
                .background(CyberCyan),
        )
    }
}

private fun dayStart(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}


