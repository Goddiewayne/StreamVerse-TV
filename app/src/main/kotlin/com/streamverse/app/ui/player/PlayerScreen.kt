package com.streamverse.app.ui.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamverse.core.util.AdBlocker
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.streamverse.app.ui.components.ChannelLogo
import com.streamverse.app.ui.components.LiveBadge
import com.streamverse.app.ui.components.LocalLiveChannels
import com.streamverse.app.ui.components.QualityBadge
import com.streamverse.app.ui.components.SignalLossOverlay
import com.streamverse.app.ui.components.TvStatic
import com.streamverse.core.data.VideoResizeMode
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.ui.SignalMessages
import com.streamverse.core.ui.TvStaticView
import org.json.JSONArray
import org.json.JSONObject


/**
 * The persistent player surface. It lives ABOVE the navigation graph (scoped to the Activity via a
 * shared [PlayerViewModel]) so playback continues across navigation:
 *
 *  • [expanded] == true  → full player page (top bar, 16∶9 video, details, source picker).
 *  • [expanded] == false → a minimized "now playing" bar docked above the bottom navigation that
 *    keeps playing; tapping it re-opens the full page. Picking another channel from anywhere
 *    seamlessly swaps the stream (handled by the shared ViewModel).
 *
 * Crucially, the video itself is a single [movableContentOf] surface that is MOVED between the full,
 * minimized and fullscreen layouts rather than torn down and rebuilt — so minimizing, expanding or
 * toggling fullscreen never re-creates ExoPlayer and never flashes the "tuning" TV static.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun PlayerHost(
    viewModel: PlayerViewModel,
    expanded: Boolean,
    bottomInset: Dp,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isFullscreen by remember { mutableStateOf(false) }
    // Fullscreen only makes sense on the full page; leaving it (back to mini) always exits fullscreen.
    LaunchedEffect(expanded) { if (!expanded) isFullscreen = false }
    val isRadio = state.channel?.sources?.keys?.any { it == SourceType.RADIO } == true

    LaunchedEffect(state.customTabUrl) {
        val url = state.customTabUrl ?: return@LaunchedEffect
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.launchUrl(context, Uri.parse(url))
        viewModel.onCustomTabLaunched()
    }

    LaunchedEffect(state.firefoxUrl) {
        val url = state.firefoxUrl ?: return@LaunchedEffect
        val firefoxIntent = context.packageManager.getLaunchIntentForPackage("org.mozilla.firefox")
        if (firefoxIntent != null) {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("org.mozilla.firefox")
            }
            context.startActivity(intent)
        } else {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        }
        viewModel.onFirefoxLaunched()
    }

    val activity = remember {
        // Unwrap the context chain UNTIL we hit the Activity — an Activity is itself a
        // ContextWrapper, so we must stop ON it, not unwrap past it (which returned null and
        // silently broke keep-screen-on and fullscreen window handling).
        var ctx = context
        while (ctx is ContextWrapper && ctx !is ComponentActivity) { ctx = ctx.baseContext }
        ctx as? ComponentActivity
    }
    val window = remember(activity) { activity?.window }
    // Route the hardware volume keys to the media stream so VOL+/− adjust playback volume.
    DisposableEffect(activity) {
        val previous = activity?.volumeControlStream
        activity?.volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        onDispose { if (previous != null) activity.volumeControlStream = previous }
    }
    LaunchedEffect(isFullscreen) {
        if (window != null) {
            if (isFullscreen) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val keepScreenOnPref = remember {
        // Defaults to true — live TV should keep the display awake unless the user opts out.
        context.getSharedPreferences("playback_prefs", 0)
            .getBoolean("keep_screen_on", true)
    }
    // Keep the screen awake for the whole time the player is open (not just while ExoPlayer
    // reports isPlaying — that is never true for WebView/YouTube channels). Cleared on exit so
    // the flag doesn't leak to other screens.
    DisposableEffect(keepScreenOnPref) {
        if (keepScreenOnPref) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // "Continue playing when screen is off" — default on. When the user opts out, pause playback as
    // soon as the app leaves the foreground (screen off / recents / home) and resume on return. When
    // on, audio keeps playing in the background (video just stops rendering) — no action needed.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val allow = context.getSharedPreferences("playback_prefs", 0)
                        .getBoolean("background_playback", true)
                    viewModel.onEnterBackground(allow)
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> viewModel.onEnterForeground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Picture-in-Picture: PiP-eligible only while an ExoPlayer video is loaded (full or minimized).
    val inPip by PipController.inPip.collectAsStateWithLifecycle()
    DisposableEffect(state.mode, state.channel?.id) {
        PipController.eligible = state.mode == PlayerStreamMode.EXOPLAYER && state.channel != null
        onDispose { PipController.eligible = false }
    }

    // The ONE video surface, shared across every layout. movableContentOf preserves its ExoPlayer
    // (and "first frame seen" state) when it is moved between full / mini / fullscreen, so the
    // transitions are gapless and never re-tune static. `minimized` is passed in (not captured) so
    // the controller chrome can be hidden in the mini bar.
    val videoSurface = remember {
        movableContentOf<Boolean> { minimized ->
            PlayerFrame(
                state = state,
                viewModel = viewModel,
                isFullscreen = isFullscreen,
                minimized = minimized,
                onToggleFullscreen = { isFullscreen = !isFullscreen },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            inPip -> {
                // Floating PiP window: just the video, no chrome.
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    videoSurface(false)
                }
            }

            isFullscreen -> {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    videoSurface(false)
                    IconButton(
                        onClick = { isFullscreen = false },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Exit fullscreen", tint = Color.White)
                    }
                }
            }

            expanded -> {
                ExpandedPlayer(
                    state = state,
                    viewModel = viewModel,
                    isRadio = isRadio,
                    onBack = onCollapse,
                    video = { videoSurface(false) },
                )
            }

            else -> {
                MiniPlayer(
                    state = state,
                    bottomInset = bottomInset,
                    onExpand = onExpand,
                    onClose = onClose,
                    video = { videoSurface(true) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** The full player page: top bar, 16∶9 video, channel details + source picker. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun ExpandedPlayer(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    isRadio: Boolean,
    onBack: () -> Unit,
    video: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = state.channel?.displayName ?: "Player",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Minimize")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isRadio) Modifier.height(200.dp)
                    else Modifier.aspectRatio(16f / 9f)
                )
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            video()
        }

        if (state.channel != null) {
            val channel = state.channel
            val guide = state.guideChannels

            // ── Pinned header: channel metadata + source picker. Always visible (does NOT scroll
            // away with the guide) so switching source / surfing is always one tap away. ──
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                // No implementation banners (WebView / ad-block / extraction) — every channel
                // presents as plain television regardless of the underlying pipeline.
                Text(
                    text = channel.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    if (isRadio) {
                        Text(
                            text = "Radio",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    channel.quality?.let { quality ->
                        Text(
                            text = when (quality) {
                                Quality._4K -> "4K"
                                Quality.FHD -> "FHD"
                                Quality.HD -> "HD"
                                Quality.SD -> "SD"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    channel.category?.let { cat ->
                        Text(
                            text = cat,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.canSurf) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ChannelSurfControls(
                        onPrev = { viewModel.channelSurf(-1) },
                        onNext = { viewModel.channelSurf(1) },
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                SourcePicker(
                    sources = state.availableSources,
                    selected = state.selectedSource,
                    onSelect = { viewModel.selectSource(it) },
                )
            }

            // ── Channel guide: a cable-style list that fills the rest and scrolls to the current
            // channel (highlighted). The pinned header above stays put. ──
            if (guide.size > 1) {
                val listState = rememberLazyListState()
                LaunchedEffect(channel.id, guide.size) {
                    val idx = guide.indexOfFirst { it.id == channel.id }
                    if (idx >= 0) listState.scrollToItem(idx)
                }
                Text(
                    text = "Channels",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(guide, key = { it.id }) { ch ->
                        ChannelGuideRow(
                            channel = ch,
                            isCurrent = ch.id == channel.id,
                            onClick = { viewModel.open(ch.id) },
                        )
                    }
                }
            }
        }
    }
}

/** One row of the in-player channel guide. The playing channel is highlighted. */
@Composable
private fun ChannelGuideRow(
    channel: Channel,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(channel = channel, modifier = Modifier.fillMaxSize())
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            channel.category?.takeIf { !it.contains(",") && it.length <= 30 }?.let { cat ->
                Text(
                    text = cat,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Now Playing",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (LocalLiveChannels.current.contains(channel.id)) LiveBadge()
                channel.quality?.let { QualityBadge(quality = it) }
            }
        }
    }
}

/** Fixed height of the minimized player bar (video + labels), excluding the gap below it. */
val MiniPlayerHeight: Dp = 72.dp
/** Gap between the mini bar and whatever sits below it (bottom nav or system nav). */
val MiniPlayerGap: Dp = 8.dp

/**
 * The vertical space the minimized player occupies above the bottom navigation. Screens with
 * scrolling content read this (via [LocalMiniPlayerInset]) and add it to their bottom content
 * padding so the last row clears the floating bar instead of hiding behind it.
 */
val LocalMiniPlayerInset = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/** The minimized "now playing" bar docked above the bottom navigation; tap to re-open. */
@UnstableApi
@Composable
private fun MiniPlayer(
    state: PlayerUiState,
    bottomInset: Dp,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    video: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(bottom = bottomInset + MiniPlayerGap)
            .height(MiniPlayerHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onExpand)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
        ) {
            video()
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.channel?.displayName ?: "Loading…",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (state.error != null) "Tap to retry" else "Now playing",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onExpand) {
            Icon(Icons.Default.Fullscreen, contentDescription = "Expand player", tint = MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close player", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Channel surfing — flip to the previous/next channel in the same category in-place,
 * like pressing channel up/down on a TV remote.  Mirrors the TV app's ▲/▼ zapping.
 */
@Composable
private fun ChannelSurfControls(onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SurfButton(label = "◀  Prev", onClick = onPrev, modifier = Modifier.weight(1f))
        SurfButton(label = "Next  ▶", onClick = onNext, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SurfButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Lets the viewer choose which source to watch a multi-source channel from. The best source is
 * already playing; tapping a chip switches in place. A single-source channel just shows its label.
 */
@Composable
private fun SourcePicker(
    sources: List<SourceOption>,
    selected: SourceType?,
    onSelect: (SourceType) -> Unit,
) {
    if (sources.isEmpty()) return
    Column {
        Text(
            text = if (sources.size > 1) "Watch from" else "Source",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sources.forEach { option ->
                val isSelected = option.type == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { onSelect(option.type) }
                        .semantics {
                            contentDescription =
                                (if (isSelected) "Now watching from " else "Watch from ") + option.label
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerFrame(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    minimized: Boolean = false,
) {
    val context = LocalContext.current
    // No-signal static preferences (mirrors the keep-screen-on/resize-mode SharedPreferences
    // pattern used elsewhere on this screen, so no Hilt plumbing into composition is needed).
    val staticPrefs = remember { context.getSharedPreferences("playback_prefs", 0) }
    val staticIntensity = remember {
        TvStaticView.Intensity.fromKey(staticPrefs.getString("static_intensity", "medium"))
    }
    val staticAudio = remember { staticPrefs.getBoolean("static_audio", false) }
    val channelBurst = remember { staticPrefs.getBoolean("static_channel_burst", true) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Crossfade between the live content and the no-signal static so failures fade IN over the
        // dying frame and recovery fades the snow OUT as the new video fades up — never a black or
        // white flash, never an abrupt cut.
        Crossfade(
            targetState = state.error != null,
            animationSpec = tween(durationMillis = 450),
            label = "signalLoss",
        ) { signalLost ->
            if (signalLost) {
                SignalLossOverlay(
                    message = SignalMessages.forError(state.error),
                    intensity = staticIntensity,
                    audioEnabled = staticAudio,
                    onRetry = viewModel::retry,
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        state.isLoading -> {
                            // A faint "tuning" snow under the spinner makes a channel change feel
                            // like turning a TV dial, not waiting on an app. Configurable.
                            if (channelBurst) {
                                TvStatic(
                                    intensity = TvStaticView.Intensity.LOW,
                                    audioEnabled = false,
                                )
                            }
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        state.mode == PlayerStreamMode.EXTRACTING && state.extractUrl != null -> {
                            StreamExtractor(
                                pageUrl = state.extractUrl!!,
                                onExtracted = { url, referer -> viewModel.onStreamExtracted(url, referer) },
                                onFailed = { viewModel.onExtractionFailed() },
                            )
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        state.streamUrl != null && state.mode == PlayerStreamMode.EXOPLAYER -> {
                            ExoPlayerPlayer(
                                url = state.streamUrl!!,
                                referer = state.streamReferer,
                                headers = state.streamHeaders,
                                drmKeyId = state.drmKeyId,
                                drmKey = state.drmKey,
                                drmLicenseUrl = state.drmLicenseUrl,
                                isFullscreen = isFullscreen,
                                onToggleFullscreen = onToggleFullscreen,
                                controlsEnabled = !minimized,
                                playWhenReady = state.playWhenReady,
                                onError = { msg -> viewModel.onPlayerError(msg) },
                                onPlayingChanged = { viewModel.onPlaybackStateChanged(it) },
                            )
                        }
                        state.streamUrl != null && state.mode == PlayerStreamMode.CUSTOM_TAB -> {
                            CustomTabPlaceholder(
                                streamUrl = state.streamUrl!!,
                                context = context,
                            )
                        }
                        state.streamUrl != null && state.mode == PlayerStreamMode.FIREFOX -> {
                            FirefoxPlaceholder(streamUrl = state.streamUrl!!, context = context)
                        }
                        state.streamUrl != null && state.mode == PlayerStreamMode.WEBVIEW -> {
                            AdBlockWebViewPlayer(
                                url = state.streamUrl!!,
                                isLoading = state.webViewLoading,
                                onLoadingChanged = viewModel::onWebViewLoadingChanged,
                                loginJs = state.loginJs,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomTabPlaceholder(streamUrl: String, context: android.content.Context) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.OpenInBrowser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "Stream opened in browser",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = {
                    val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
                    intent.launchUrl(context, Uri.parse(streamUrl))
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                Text("Reopen", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun FirefoxPlaceholder(streamUrl: String, context: android.content.Context) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.OpenInBrowser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "Stream opened in Firefox",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(streamUrl)).apply {
                        setPackage("org.mozilla.firefox")
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        val fallback = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(streamUrl))
                        context.startActivity(fallback)
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                Text("Reopen in Firefox", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AdBlockWebViewPlayer(
    url: String,
    isLoading: Boolean,
    onLoadingChanged: (Boolean) -> Unit,
    loginJs: String? = null,
) {
    val context = LocalContext.current

    val webViewClient = remember(url) {
        AdBlockWebViewClient(
            onPageStarted = { onLoadingChanged(true) },
            onPageFinished = { onLoadingChanged(false) },
            loginJs = loginJs,
        )
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                webViewClient.setupWebView(this)
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { webView ->
            if (!isLoading) {
                webView.visibility = android.view.View.VISIBLE
            }
        },
    )
}

private fun isMediaStreamUrl(url: String): Boolean {
    val l = url.lowercase()
    // Ignore obvious non-stream assets even if they contain a keyword
    if (l.endsWith(".js") || l.endsWith(".css") || l.endsWith(".png") || l.endsWith(".jpg") ||
        l.endsWith(".woff") || l.endsWith(".woff2") || l.endsWith(".svg")) return false
    return l.contains(".m3u8") || l.contains(".mpd") ||
        l.contains("/playlist.m3u") || l.contains("/master.m3u") ||
        l.contains("/chunklist") || l.contains("/index.m3u8") ||
        l.contains("mime=video") || l.contains("/manifest")
}

/**
 * Loads [pageUrl] in a 1×1 offscreen WebView and watches network traffic for the real
 * media manifest (.m3u8/.mpd). The first match is handed back via [onExtracted] so the
 * stream plays in ExoPlayer — the WebView is never shown. Times out via [onFailed].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun StreamExtractor(
    pageUrl: String,
    onExtracted: (String, String?) -> Unit,
    onFailed: () -> Unit,
) {
    val done = remember(pageUrl) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    DisposableEffect(pageUrl) {
        val timeout = Runnable {
            if (done.compareAndSet(false, true)) onFailed()
        }
        mainHandler.postDelayed(timeout, 20_000)
        onDispose { mainHandler.removeCallbacks(timeout) }
    }

    // Hook fetch/XHR so any manifest URL the page requests is reported, even if the
    // network layer never actually loads it (ad-blocked embeds, late init, etc.).
    val hookJs = """
        (function(){
          if(window.__svhook) return; window.__svhook=1;
          function rep(u){ try{ if(u && /\.(m3u8|mpd)(\?|${'$'}|#)/i.test(''+u)) SVExtract.onFound(''+u); }catch(e){} }
          try{ var of=window.fetch; if(of){ window.fetch=function(){ try{ var a=arguments[0]; rep(a&&(a.url||a)); }catch(e){} return of.apply(this,arguments); }; } }catch(e){}
          try{ var oo=XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open=function(m,u){ rep(u); return oo.apply(this,arguments); }; }catch(e){}
        })();
    """.trimIndent()

    // Start playback and scan the DOM/scripts for a manifest URL.
    val scanJs = """
        (function(){
          try {
            document.querySelectorAll('video').forEach(function(v){ v.muted=true; v.autoplay=true; var p=v.play(); if(p&&p.catch)p.catch(function(){}); });
            ['.plyr__control--overlaid','button[data-plyr="play"]','.jw-icon-display','.vjs-big-play-button']
              .forEach(function(s){ var el=document.querySelector(s); if(el){ try{ el.click(); }catch(e){} } });
            document.querySelectorAll('video,source').forEach(function(v){ if(v.src) SVExtract.onFound(v.src); });
            var html=document.documentElement.innerHTML;
            var re=/https?:\/\/[^"'\\\s<>()]+\.(m3u8|mpd)[^"'\\\s<>()]*/ig, m;
            while((m=re.exec(html))!==null){ SVExtract.onFound(m[0]); }
            // YouTube live: the master HLS playlist is embedded in the player response. Pull it
            // straight out so the channel plays in ExoPlayer (no YouTube UI).
            var ym=html.match(/"hlsManifestUrl":"([^"]+)"/);
            if(ym&&ym[1]){ SVExtract.onFound(ym[1].split('\\u0026').join('&').split('\\/').join('/')); }
          } catch(e){}
        })();
    """.trimIndent()

    val capture: (String, String?) -> Unit = remember(pageUrl) {
        { u, ref ->
            if (isMediaStreamUrl(u) && done.compareAndSet(false, true)) {
                android.util.Log.d("StreamExtractor", "captured stream=$u referer=$ref")
                mainHandler.post { onExtracted(u, ref) }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            // Pre-accept YouTube/Google consent so the "Before you continue" wall never blocks the
            // hidden extractor from reaching the player response (and its HLS manifest).
            runCatching {
                val cm = android.webkit.CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setCookie("https://www.youtube.com", "SOCS=CAISNewABA; Domain=.youtube.com; Path=/; Max-Age=31536000; Secure")
                cm.setCookie("https://www.youtube.com", "CONSENT=YES+1; Domain=.youtube.com; Path=/")
            }
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
                }
                @SuppressLint("AddJavascriptInterface")
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun onFound(url: String) { capture(url, pageUrl) }
                }, "SVExtract")
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val u = request.url.toString()
                        // Capture the real media manifest if it does hit the network.
                        if (isMediaStreamUrl(u)) {
                            val ref = request.requestHeaders["Referer"] ?: view.url
                            mainHandler.post { capture(u, ref) }
                            return super.shouldInterceptRequest(view, request)
                        }
                        if (AdBlocker.isAdUrl(u)) {
                            return WebResourceResponse("text/plain", "UTF-8", null)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view.evaluateJavascript(hookJs, null)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(hookJs, null)
                        // Players initialise late and the stream URL appears after an
                        // admin-ajax call — re-scan/auto-play a few times.
                        for (delay in longArrayOf(200, 1200, 2800, 5000, 8000, 12000)) {
                            mainHandler.postDelayed({
                                if (!done.get()) view.evaluateJavascript(scanJs, null)
                            }, delay)
                        }
                    }
                }
                loadUrl(pageUrl)
            }
        },
        // Full-size so the page's player initialises, but invisible behind the spinner.
        modifier = Modifier.fillMaxSize().alpha(0f),
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

private val CLEARKEY_UUID = androidx.media3.common.C.CLEARKEY_UUID
private val WIDEVINE_UUID = androidx.media3.common.C.WIDEVINE_UUID

// Errors worth retrying indefinitely — connection blips, timeouts, CDN token expiry (4xx
// re-fetches the playlist URL fresh), live-window shifts.  Only format/codec errors that
// can never be fixed by retrying are excluded.
private val RETRIABLE_ERROR_CODES = setOf(
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
)
// Exponential backoff: 2 s → 4 s → 8 s → 16 s → 30 s (held forever)
private const val RETRY_BASE_MS = 2_000L
private const val RETRY_MAX_MS  = 30_000L
// After this many local retries, give up on the source (triggers source fallback).
private const val MAX_RETRIES = 5
// How long a seamless channel swap waits for the incoming stream to buffer a frame before
// swapping anyway. The OUTGOING channel keeps playing on screen until then — so a flip is gapless
// when the new stream is quick, and degrades to "tuning static over the buffer" only if it's slow.
private const val SEAMLESS_READY_TIMEOUT_MS = 6_000L

/** Suspends until this player reaches STATE_READY (a frame is buffered and ready to show).
 *  Must be awaited on the player's application thread (the Compose Main dispatcher is). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private suspend fun ExoPlayer.awaitReady(): Unit =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        if (playbackState == androidx.media3.common.Player.STATE_READY) {
            if (cont.isActive) cont.resumeWith(Result.success(Unit))
            return@suspendCancellableCoroutine
        }
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    removeListener(this)
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
            }
        }
        addListener(l)
        // Cancellation (e.g. the swap timeout) can fire on a background thread; ExoPlayer must only
        // be touched on its application thread, so hop back to it before removing the listener.
        cont.invokeOnCancellation {
            android.os.Handler(applicationLooper).post { removeListener(l) }
        }
    }

/**
 * Builds a fully-configured ExoPlayer for [url] (OkHttp data source, per-stream headers/Referer/
 * Origin, ClearKey/Widevine DRM, load control, track selector, audio focus) and prepares it.
 * `playWhenReady` is left false so the caller decides when it starts — a seamless-swap target then
 * buffers silently (never grabbing audio focus from the still-playing channel) until promoted. The
 * same retry policy as before (live-edge re-seek, capped exponential backoff on transient errors,
 * surface permanent errors) is attached.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun buildExoPlayer(
    context: android.content.Context,
    url: String,
    referer: String?,
    headers: Map<String, String>,
    drmKeyId: String?,
    drmKey: String?,
    drmLicenseUrl: String?,
    onError: (String) -> Unit,
): ExoPlayer {
    val defaultUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    val ua = headers["User-Agent"] ?: defaultUa
    val okClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
    val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okClient)
        .setUserAgent(ua)
    val reqProps = mutableMapOf<String, String>()
    headers.forEach { (k, v) -> if (!k.equals("User-Agent", ignoreCase = true)) reqProps[k] = v }
    if (!referer.isNullOrBlank()) reqProps["Referer"] = referer
    reqProps["Referer"]?.let { ref ->
        if (!reqProps.containsKey("Origin")) runCatching {
            val u = java.net.URI(ref)
            if (u.scheme != null && u.host != null) reqProps["Origin"] = "${u.scheme}://${u.host}"
        }
    }
    if (reqProps.isNotEmpty()) dataSourceFactory.setDefaultRequestProperties(reqProps)

    val hasClearKey = drmKeyId != null && drmKey != null
    val hasWidevine = drmLicenseUrl != null
    val jwkBytes = if (hasClearKey) buildClearKeyLicense(drmKeyId!!, drmKey!!) else null

    val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
    if (hasClearKey) {
        val drmSessionManager = androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(CLEARKEY_UUID) { androidx.media3.exoplayer.drm.FrameworkMediaDrm.newInstance(CLEARKEY_UUID) }
            .build(object : androidx.media3.exoplayer.drm.MediaDrmCallback {
                override fun executeKeyRequest(uuid: java.util.UUID, request: androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest): ByteArray = jwkBytes!!
                override fun executeProvisionRequest(uuid: java.util.UUID, request: androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest): ByteArray = ByteArray(0)
            })
        mediaSourceFactory.setDrmSessionManagerProvider(object : androidx.media3.exoplayer.drm.DrmSessionManagerProvider {
            override fun get(mediaItem: androidx.media3.common.MediaItem): androidx.media3.exoplayer.drm.DrmSessionManager = drmSessionManager
        })
    }

    val mediaItem = when {
        hasWidevine -> MediaItem.Builder()
            .setUri(android.net.Uri.parse(url))
            .setDrmConfiguration(MediaItem.DrmConfiguration.Builder(WIDEVINE_UUID).setLicenseUri(drmLicenseUrl!!).build())
            .build()
        hasClearKey -> MediaItem.Builder()
            .setUri(android.net.Uri.parse(url))
            .setDrmConfiguration(MediaItem.DrmConfiguration.Builder(CLEARKEY_UUID).build())
            .build()
        else -> MediaItem.fromUri(android.net.Uri.parse(url))
    }

    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(com.streamverse.core.util.StreamLoadControl.build())
        .setTrackSelector(com.streamverse.core.util.StreamTrackSelector.build(context))
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build().apply {
            setMediaItem(mediaItem)
            prepare()
            val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
            var retryCount = 0
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_READY) retryCount = 0
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.w("ExoPlayerPlayer", "error code=${error.errorCode} retry=$retryCount")
                    if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        seekToDefaultPosition(); prepare(); return
                    }
                    if (error.errorCode in RETRIABLE_ERROR_CODES) {
                        retryCount++
                        if (retryCount > MAX_RETRIES) {
                            onError(error.localizedMessage ?: "Max retries exceeded")
                            return
                        }
                        val delay = minOf(RETRY_BASE_MS * (1L shl minOf(retryCount - 1, 4)), RETRY_MAX_MS)
                        retryHandler.postDelayed({ prepare() }, delay)
                        return
                    }
                    onError(error.localizedMessage ?: "Playback failed")
                }
            })
        }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ExoPlayerPlayer(
    url: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
    drmKeyId: String? = null,
    drmKey: String? = null,
    drmLicenseUrl: String? = null,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    controlsEnabled: Boolean = true,
    playWhenReady: Boolean = true,
    onError: (String) -> Unit,
    onPlayingChanged: ((Boolean) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // The stable on-screen surface — created once and reused; only its `player` is swapped, so a
    // channel change never tears the video view down.
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }
    var activePlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    // Gates the "tuning" static: true once a frame is on screen. A seamless-swap target is already
    // buffered when promoted, so it is marked visible immediately (no static flash).
    var videoVisible by remember { mutableStateOf(false) }

    // Build the first player, or SEAMLESSLY swap to a new stream on a channel/source change: build
    // the next player muted-and-buffering, wait until it has a frame ready, then promote it onto the
    // SAME PlayerView and release the old one. The outgoing channel stays on screen until the
    // incoming one is ready — no black gap, no audio overlap — exactly like the TV app's
    // preload/performSwap. Any slowness degrades gracefully to "tuning static over the buffer".
    LaunchedEffect(url, referer, headers, drmKeyId, drmKey, drmLicenseUrl) {
        val previous = activePlayer
        val next = buildExoPlayer(context, url, referer, headers, drmKeyId, drmKey, drmLicenseUrl, onError)
        if (previous == null) {
            // First channel on this screen — tuning static until the first frame, as before.
            videoVisible = false
            next.playWhenReady = playWhenReady
            activePlayer = next
            return@LaunchedEffect
        }
        var swapped = false
        try {
            val ready = kotlinx.coroutines.withTimeoutOrNull(SEAMLESS_READY_TIMEOUT_MS) {
                next.awaitReady(); true
            } == true
            next.playWhenReady = playWhenReady
            videoVisible = ready                       // ready → gapless; timed-out → static covers buffer
            playerViewRef.value?.player = next         // attach BEFORE releasing old → no blank frame
            activePlayer = next
            swapped = true
            runCatching { previous.release() }
        } finally {
            if (!swapped) runCatching { next.release() }   // superseded by a newer surf mid-wait
        }
    }

    // Release the active player when the screen leaves (a swap releases the outgoing player itself).
    DisposableEffect(Unit) {
        onDispose { runCatching { activePlayer?.release() } }
    }

    // Apply background pause / resume requests to whichever player is currently on screen.
    LaunchedEffect(playWhenReady, activePlayer) {
        activePlayer?.let { if (it.playWhenReady != playWhenReady) it.playWhenReady = playWhenReady }
    }

    // Per-active-player wiring — re-attaches to whichever player is on screen after a swap:
    // first-frame tracking, keep-screen-on, network reconnect, and the MediaSession.
    DisposableEffect(activePlayer) {
        val p = activePlayer
        if (p == null) {
            onDispose { }
        } else {
            val frameListener = object : androidx.media3.common.Player.Listener {
                override fun onRenderedFirstFrame() { videoVisible = true }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) videoVisible = true
                    onPlayingChanged?.invoke(isPlaying)
                }
            }
            fun refreshKeepScreenOn() {
                playerViewRef.value?.keepScreenOn = p.playWhenReady &&
                    p.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                    p.playbackState != androidx.media3.common.Player.STATE_ENDED
            }
            val keepAwakeListener = object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = refreshKeepScreenOn()
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshKeepScreenOn()
                override fun onPlaybackStateChanged(state: Int) = refreshKeepScreenOn()
            }
            p.addListener(frameListener)
            p.addListener(keepAwakeListener)
            refreshKeepScreenOn()

            // Reconnect when connectivity returns: re-prepare a stalled (idle) player.
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    android.os.Handler(p.applicationLooper).post {
                        if (p.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                            p.prepare(); p.playWhenReady = true
                        }
                    }
                }
            }
            runCatching { cm?.registerDefaultNetworkCallback(netCallback) }

            // MediaSession: surface what's playing / route media keys to the active player.
            val session = runCatching {
                androidx.media3.session.MediaSession.Builder(context, p)
                    .setId("streamverse_player_${System.identityHashCode(p)}")
                    .build()
            }.getOrNull()

            onDispose {
                p.removeListener(frameListener)
                p.removeListener(keepAwakeListener)
                runCatching { cm?.unregisterNetworkCallback(netCallback) }
                session?.release()
            }
        }
    }

    val videoResizeMode = remember {
        context.getSharedPreferences("playback_prefs", 0)
            .getString("resize_mode", "FIT")?.uppercase() ?: "FIT"
    }
    val resizeModeInt = when (videoResizeMode) {
        "ZOOM" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "FILL" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    // Tuning static covers a buffering player (first load, or a swap that didn't finish buffering
    // in time); it fades out the instant a video frame is on screen.
    val tuningStaticEnabled = remember {
        context.getSharedPreferences("playback_prefs", 0).getBoolean("static_channel_burst", true)
    }
    val tuningAlpha by animateFloatAsState(
        targetValue = if (tuningStaticEnabled && !videoVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "tuningStatic",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = controlsEnabled
                    resizeMode = resizeModeInt
                    player = activePlayer
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    playerViewRef.value = this
                }
            },
            update = { view ->
                view.resizeMode = resizeModeInt
                // Minimized mini-player shows no transport controls; the full page does.
                view.useController = controlsEnabled
                if (!controlsEnabled) view.hideController()
                if (view.player !== activePlayer) view.player = activePlayer
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Tuning static over the buffering player — disappears as soon as video is on screen.
        if (tuningAlpha > 0.01f) {
            TvStatic(
                modifier = Modifier.alpha(tuningAlpha),
                intensity = TvStaticView.Intensity.LOW,
                audioEnabled = false,
            )
        }

        if (!isFullscreen && controlsEnabled) {
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "Enter fullscreen",
                    tint = Color.White,
                )
            }
        }
    }
}

private fun buildClearKeyLicense(keyIdHex: String, keyHex: String): ByteArray {
    val kidBytes = hexStringToByteArray(keyIdHex)
    val keyBytes = hexStringToByteArray(keyHex)
    val jwk = JSONObject().apply {
        put("keys", JSONArray().apply {
            put(JSONObject().apply {
                put("kty", "oct")
                put("kid", android.util.Base64.encodeToString(kidBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
                put("k", android.util.Base64.encodeToString(keyBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
            })
        })
        put("type", "temporary")
    }
    android.util.Log.d("ExoPlayerPlayer", "ClearKey JWK: $jwk")
    return jwk.toString().encodeToByteArray()
}

private fun hexStringToByteArray(s: String): ByteArray {
    val len = s.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        i += 2
    }
    return data
}


