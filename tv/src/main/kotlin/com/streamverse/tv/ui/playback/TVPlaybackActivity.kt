package com.streamverse.tv.ui.playback

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamverse.tv.ui.browse.TVChannelPresenter
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.ChannelNavigationEngine
import com.streamverse.core.data.PlaybackSessionManager
import com.streamverse.core.data.PlaybackStateMachine
import com.streamverse.core.data.SourceHealth
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.numberedDisplayName
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.SourceResolutionEngine
import com.streamverse.core.util.SourceSelector
import com.streamverse.core.util.StreamInfo
import com.streamverse.core.util.StreamLoadControl
import com.streamverse.core.util.StreamPreResolver
import com.streamverse.core.util.StreamResolver
import com.streamverse.core.util.StreamTrackSelector
import com.streamverse.tv.R
import com.streamverse.core.util.AdBlocker
import java.util.concurrent.atomic.AtomicBoolean
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen, immersive TV playback.
 *
 * Design principles:
 * - True cinematic fullscreen: system bars are hidden via WindowInsetsController and restored
 *   only if the user swipes from the edge (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE).
 * - Now-playing overlay: channel name + LIVE badge appear for 4 s on start and on every DPAD
 *   press, then auto-hide — same pattern as YouTube TV.
 * - Source bar: surfaces on DPAD ←/→ (single-source channels get the now-playing info only).
 * - Live channel surfing: DPAD ▲/▼ (or the remote's CHANNEL_UP/DOWN) flip through the
 *   current category's channels in-place — like a real TV remote — with a brief guide card
 *   that peeks at the channels above and below on the dial.
 * - Resilience: a dropped stream retries the same source indefinitely (see startPlayback);
 *   the viewer is never forced to act and can switch source manually at any time.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class TVPlaybackActivity : ComponentActivity() {

    @Inject lateinit var repository: ChannelRepository
    @Inject lateinit var resolver: StreamResolver
    @Inject lateinit var streamPreResolver: StreamPreResolver
    @Inject lateinit var sourcePreferences: SourcePreferences
    @Inject lateinit var navigationEngine: ChannelNavigationEngine
    @Inject lateinit var channelHealthEngine: ChannelHealthEngine
    @Inject lateinit var sourceSelector: SourceSelector
    @Inject lateinit var sessionManager: PlaybackSessionManager
    @Inject lateinit var sourceResolutionEngine: SourceResolutionEngine

    private val stateMachine = PlaybackStateMachine()

    private var player: ExoPlayer? = null
    // Secondary player for seamless channel switching — pre-loads the next channel
    // while the current one is still playing. Swapped atomically on tune.
    private var nextPlayer: ExoPlayer? = null
    private var nextPlayerChannelId = ""
    private var nextPlayerType: SourceType? = null
    // True once nextPlayer has buffered to STATE_READY — only then is a swap truly seamless.
    private var nextPlayerReady = false
    private var nextPlayerListener: Player.Listener? = null
    // Set when the user commits to a channel that's still preloading: the moment its preload is
    // ready we swap automatically, keeping the CURRENT channel on screen until then (no spinner).
    private var pendingSwapChannelId: String? = null
    // Debounced preload target — avoids building a player for every channel skimmed past.
    private var pendingPreloadChannel: Channel? = null
    private val preloadRunnable = Runnable { pendingPreloadChannel?.let { preloadChannel(it) } }
    // Media session: surfaces transport state to the system so FireTV Alexa / Android TV
    // Assistant voice commands ("pause", "play", "stop") and system media controls work.
    private var mediaSession: MediaSession? = null
    private lateinit var playerView: PlayerView
    private lateinit var progress: ProgressBar
    // No-signal analogue-static overlay (replaces the old plain error text + black screen).
    private lateinit var noSignalOverlay: FrameLayout
    private lateinit var tvStatic: com.streamverse.core.ui.TvStaticView
    private lateinit var signalOsd: View
    private lateinit var signalHeadline: TextView
    private lateinit var signalDetail: TextView
    private val osdRecedeRunnable = Runnable { signalOsd.animate().alpha(0.12f).setDuration(600).start() }
    private lateinit var nowPlayingBar: View
    private lateinit var channelNameView: TextView
    private lateinit var channelMetaView: TextView
    private lateinit var sourceBar: LinearLayout
    private lateinit var sourceChips: LinearLayout
    private lateinit var logoHelper: TVChannelPresenter

    private var channel: Channel? = null
    private var selectedType: SourceType? = null

    // Ordered list of options for the current channel; we walk this on auto-fallback.
    private var sourceOptions: List<Pair<SourceType, String>> = emptyList()
    private var currentOptionIndex = 0

    // Source chooser navigation: ◄/► move a highlight, OK confirms the switch — so changing source
    // is always deliberate (never an accidental auto-switch on a stray DPAD press).
    private var sourceBarActive = false
    private var pendingSourceIndex = 0
    private var healthJob: kotlinx.coroutines.Job? = null
    // Visible WebView fallback — swapped in when no direct/browser-extracted stream is available
    // (e.g. YouTube embed).  Cleared on every tuneToChannel call.
    private var webViewPlaceholder: WebView? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideOverlaysRunnable = Runnable { hideOverlays() }

    // ── Playback state machine ──────────────────────────────────────────────────────
    // Gates the TV static overlay: visible ONLY when state == UNAVAILABLE.
    private val staticFadeInRunnable = Runnable { fadeInStaticInternal() }
    private val staticFadeOutRunnable = Runnable { fadeOutStaticInternal() }

    init {
        stateMachine.onStateChanged = { s ->
            android.util.Log.d("TVPlayback", "State: $s")
            when (s) {
                PlaybackStateMachine.State.UNAVAILABLE -> showStaticInternal()
                PlaybackStateMachine.State.IDLE -> {}
                else -> hideStaticInternal()
            }
            updateProgressVisibility()
        }
    }

    // ── Connect watchdog ────────────────────────────────────────────────────────────────
    // If a source hasn't started playing within CONNECT_TIMEOUT_MS after startPlayback,
    // treat it as dead and try the next source. Cancelled on STATE_READY.
    private val connectWatchdogRunnable = Runnable { connectWatchdogFire() }
    private fun connectWatchdogFire() {
        android.util.Log.w("TVPlayback", "connect watchdog fired — advancing to next source")
        tryNextSource()
    }
    private val connectTimeoutMs = 15_000L

    // ── Validation recovery ────────────────────────────────────────────────────────────
    // If a manually selected source fails validation within this timeout, auto-recover
    // to the default verified source.
    private val validationRecoveryRunnable = Runnable { validationRecoveryFire() }
    private val validationTimeoutMs = 8_000L
    private fun validationRecoveryFire() {
        if (sessionManager.getSelectionMode() == PlaybackSessionManager.SourceSelectionMode.MANUAL &&
            !sessionManager.isSourceValidated()) {
            android.util.Log.w("TVPlayback", "validation recovery: manual source failed, recovering to default")
            recoverToDefault()
        }
    }

    // ── Live channel surfing ("zapping") ─────────────────────────────────────────────
    // D-Pad ↑/↓ flips through this list in-place, like a real TV remote.  Built from the
    // current channel's category-mates (falls back to the full catalogue).
    private var zapChannels: List<Channel> = emptyList()
    private var zapIndex = 0
    private val zapRunnable = Runnable { tuneToZapTarget() }

    // ── Channel guide ────────────────────────────────────────────────────────────────
    // DPAD → opens a side panel listing channels; ▲/▼ moves the highlight, OK tunes,
    // ← / BACK closes. Selection is managed manually (not view focus) for predictable,
    // snappy navigation while the video keeps playing underneath.
    private var channelGuide: FrameLayout? = null
    private lateinit var guideList: RecyclerView
    private var guideVisible = false
    private var guideIndex = 0
    // The guide lists the full playable catalogue, independent of the category-scoped
    // ▲/▼ surf list.
    private var guideChannels: List<Channel> = emptyList()
    private val hideGuideRunnable = Runnable { closeGuide() }

    // -----------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge before setContentView so the initial layout pass is already fullscreen.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Route the remote/TV hardware volume keys to the media stream so VOL+/−/MUTE adjust
        // playback volume even on devices without HDMI-CEC pass-through.
        volumeControlStream = AudioManager.STREAM_MUSIC

        setContentView(R.layout.activity_tv_playback)

        playerView = findViewById(R.id.tv_player_view)
        progress = findViewById(R.id.tv_player_progress)
        noSignalOverlay = findViewById(R.id.tv_no_signal)
        tvStatic = findViewById(R.id.tv_static)
        signalOsd = findViewById(R.id.tv_signal_osd)
        signalHeadline = findViewById(R.id.tv_signal_headline)
        signalDetail = findViewById(R.id.tv_signal_detail)
        tvStatic.intensity = com.streamverse.core.ui.TvStaticView.Intensity.fromKey(
            getSharedPreferences("playback_prefs", MODE_PRIVATE).getString("static_intensity", "medium"),
        )
        nowPlayingBar = findViewById(R.id.tv_now_playing)
        channelNameView = findViewById(R.id.tv_channel_name)
        channelMetaView = findViewById(R.id.tv_channel_meta)
        sourceBar = findViewById(R.id.tv_source_bar)
        sourceChips = findViewById(R.id.tv_source_chips)
        logoHelper = TVChannelPresenter()
        buildGuide()

        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        if (channelId.isNullOrBlank()) { finish(); return }

        lifecycleScope.launch {
            val allChannels = repository.getCachedChannels()
            navigationEngine.rebuild(allChannels)
            if (repository.getChannelById(channelId) == null) {
                repository.load()
            }
            val ch = repository.getChannelById(channelId)
            if (ch == null) {
                stateMachine.transition(PlaybackStateMachine.Event.LOAD)
                stateMachine.transition(PlaybackStateMachine.Event.ALL_SOURCES_EXHAUSTED)
                return@launch
            }
            channel = ch
            sourceOptions = buildSourceOptions(ch)
            stateMachine.setHasFallbackSource(sourceOptions.size > 1)
            if (sourceOptions.isEmpty()) {
                stateMachine.transition(PlaybackStateMachine.Event.LOAD)
                stateMachine.transition(PlaybackStateMachine.Event.ALL_SOURCES_EXHAUSTED)
                return@launch
            }
            stateMachine.transition(PlaybackStateMachine.Event.LOAD)
            // Build the channel-surf list from the canonical navigation engine.
            val playableIds = allChannels.filter { buildSourceOptions(it).isNotEmpty() }.map { it.id }.toSet()
            zapChannels = navigationEngine.buildPlayableDial(playableIds, channelId)
            zapIndex = zapChannels.indexOfFirst { it.id == channelId }.coerceAtLeast(0)
            // Persist to "Continue Watching" — same SharedPreferences that TVBrowseFragment reads
            recordWatchHistory(channelId)
            buildSourceChips(ch)
            // Intelligent default: use verified-playable source first, then fallback.
            val selection = sourceSelector.selectDefaultVerifiedSource(ch)
            sessionManager.startSession(ch, selection.type)
            val startIdx = sourceOptions.indexOfFirst { it.first == selection.type }.coerceAtLeast(0)
            observeSourceHealth(channelId)
            playFromIndex(startIdx)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onStop() {
        super.onStop()
        removeWebViewPlaceholder()
        healthJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        releaseNextPlayer()
        nextPlayerType = null
        pendingSwapChannelId = null
        pendingPreloadChannel = null
        playerView.player = null
        stateMachine.reset()
        sessionManager.clearSession()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // -----------------------------------------------------------------------------------------
    // Immersive fullscreen
    // -----------------------------------------------------------------------------------------

    private fun enterImmersiveMode() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // -----------------------------------------------------------------------------------------
    // DPAD input — any directional key reveals overlays; BACK exits
    // -----------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            // While the channel guide is open it owns directional input.
            if (guideVisible) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP,
                    android.view.KeyEvent.KEYCODE_CHANNEL_UP -> { moveGuide(-1); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> { moveGuide(+1); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        guideChannels.getOrNull(guideIndex)?.let { target ->
                            closeGuide(); tuneToChannel(target)
                        }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_BACK -> { closeGuide(); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { resetGuideHideTimer(); return true }
                }
            }
            // While the source chooser is open it owns directional input: ◄/► move the highlight,
            // OK switches, ▲/▼/BACK dismiss — nothing changes source until OK is pressed.
            if (sourceBarActive) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { moveSourceSelection(-1); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { moveSourceSelection(+1); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> { commitSourceSelection(); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_BACK -> { closeSourceBar(); return true }
                }
            }
            when (event.keyCode) {
                // ── Fire TV remote media buttons ──────────────────────────────────
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    player?.let { it.seekTo((it.currentPosition + 10_000).coerceAtMost(it.duration)) }
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
                    return true
                }
                // ── DPAD ↑/↓: live channel surfing (zap) ──────────────────────────
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_CHANNEL_UP -> {
                    // If the source chooser is up, ↑ dismisses it rather than zapping.
                    if (sourceBar.visibility == View.VISIBLE) { hideOverlays(); return true }
                    zapBy(-1); return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (sourceBar.visibility == View.VISIBLE) { hideOverlays(); return true }
                    zapBy(+1); return true
                }
                // ── DPAD ←: open the source chooser (navigate with ◄/►, OK to switch) ──
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    openSourceBar()
                    return true
                }
                // ── DPAD →: open the channel guide (always available, even while loading) ──
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    openGuide()
                    return true
                }
                // ── DPAD center / OK: the single "show me my options" gesture ──────
                // Standardised like a TV OS: OK opens the source chooser FOCUSED, so ◄/► then
                // pick a source and OK switches — it never leaks into the channel guide (that
                // stays on ► at rest). On a no-signal screen OK retries; single-source channels
                // just toggle the now-playing card.
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    when {
                        // Multi-source: OK always focuses the source chooser — including on a
                        // no-signal screen, where picking another source is usually the fix.
                        // (OK on the already-selected source then just re-tunes = retry.)
                        sourceOptions.size > 1 -> openSourceBar()
                        // Single-source on static: OK retries the only source there is.
                        stateMachine.shouldShowStatic -> playFromIndex(currentOptionIndex)
                        nowPlayingBar.visibility == View.VISIBLE -> hideOverlays()
                        else -> showOverlays()
                    }
                    return true
                }
                // ── Digit keys (0-9): reserved for future use ──────────────
                android.view.KeyEvent.KEYCODE_0, android.view.KeyEvent.KEYCODE_1,
                android.view.KeyEvent.KEYCODE_2, android.view.KeyEvent.KEYCODE_3,
                android.view.KeyEvent.KEYCODE_4, android.view.KeyEvent.KEYCODE_5,
                android.view.KeyEvent.KEYCODE_6, android.view.KeyEvent.KEYCODE_7,
                android.view.KeyEvent.KEYCODE_8, android.view.KeyEvent.KEYCODE_9 -> {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // -----------------------------------------------------------------------------------------
    // Live channel surfing ("zapping") — ▲/▼ flip channels in-place, like a TV remote
    // -----------------------------------------------------------------------------------------

    /** Surf [delta] channels up (−1) or down (+1).  Debounced: the card flips instantly,
     *  the stream tunes once the viewer settles — exactly how channel-surfing feels. */
    private fun zapBy(delta: Int) {
        if (zapChannels.size < 2) { showOverlays(); return }
        val n = zapChannels.size
        zapIndex = ((zapIndex + delta) % n + n) % n
        showZapPreview(zapChannels[zapIndex])
        handler.removeCallbacks(zapRunnable)
        handler.postDelayed(zapRunnable, ZAP_TUNE_DELAY_MS)
    }

    /** Commit the surf: switch to the channel the viewer landed on. */
    private fun tuneToZapTarget() {
        val target = zapChannels.getOrNull(zapIndex) ?: return
        if (target.id == channel?.id) {
            // Surfed back to the channel already playing — just restore its card, no re-tune.
            if (player?.isPlaying == true) progress.visibility = View.GONE
            updateNowPlayingOverlay()
            return
        }
        requestTune(target)
    }

    /** Flip the now-playing card to [target] without yet tuning — shows a peek of the
     *  channels above and below on the dial, giving a live "mini guide" feel. */
    private fun showZapPreview(target: Channel) {
        nowPlayingBar.visibility = View.VISIBLE
        sourceBar.visibility = View.GONE
        hideStaticInternal()
        progress.visibility = View.VISIBLE
        channelNameView.text = target.numberedDisplayName()
        val n = zapChannels.size
        val upIdx = (zapIndex - 1 + n) % n
        val downIdx = (zapIndex + 1) % n
        val up   = zapChannels[upIdx].numberedDisplayName()
        val down = zapChannels[downIdx].numberedDisplayName()
        val q = channelQualityLabel(target)
        channelMetaView.text = buildString {
            target.category?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (q.isNotEmpty()) { if (isNotEmpty()) append("  ·  "); append(q) }
            append("      ▲ ").append(up).append("    ▼ ").append(down)
        }
        resetHideTimer()

        // SEAMLESS: pre-load the previewed channel so committing to it swaps instantly.
        schedulePreload(target)
        // Also preload neighbors so rapid up/down zaps are instant.
        schedulePreload(zapChannels[upIdx])
        schedulePreload(zapChannels[downIdx])
    }

    /**
     * Commit to [target]: switch the channel. If [target] was pre-loaded and is already buffered,
     * the swap is INSTANT (no spinner). If it's pre-loaded but not yet ready, the CURRENT channel
     * keeps playing and we swap the moment it becomes ready — exactly like a satellite/analogue
     * TV tune. Only when nothing was pre-loaded do we fall back to a normal (spinner) load.
     */
    private fun requestTune(target: Channel) {
        if (target.id == channel?.id) return
        removeWebViewPlaceholder()
        channel = target
        selectedType = null
        sourceOptions = buildSourceOptions(target)
        if (sourceOptions.isEmpty()) { showStaticInternal("This channel isn't available on TV yet"); return }
        val selection = sourceSelector.selectDefaultVerifiedSource(target)
        sessionManager.startSession(target, selection.type)
        currentOptionIndex = sourceOptions.indexOfFirst { it.first == selection.type }.coerceAtLeast(0)
        observeSourceHealth(target.id)
        zapIndex = zapChannels.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        recordWatchHistory(target.id)
        buildSourceChips(target)
        updateNowPlayingOverlay()
        showOverlays()
        pendingSwapChannelId = null

        if (nextPlayer != null && nextPlayerChannelId == target.id) {
            if (nextPlayerReady) {
                performSwap()                       // already buffered → instant, seamless
            } else {
                pendingSwapChannelId = target.id    // keep current playing; swap when ready
                progress.visibility = View.GONE     // NO spinner — current channel stays on screen
            }
        } else {
            playFromIndex(currentOptionIndex)       // smart-selected source
        }
    }

    /** Debounced preload — builds a player only once the highlight/preview settles, so skimming
     *  quickly past channels doesn't spin up a player for each one. */
    private fun schedulePreload(target: Channel) {
        if (target.id == channel?.id) return
        if (sourcePreferences.isDataSaverEnabled()) return
        pendingPreloadChannel = target
        handler.removeCallbacks(preloadRunnable)
        handler.postDelayed(preloadRunnable, PRELOAD_DEBOUNCE_MS)
    }

    /**
     * Pre-loads [target] into [nextPlayer] (prepared, not playing) so a later tune can swap
     * instantly. Resolves the stream off the main thread, then attaches a readiness listener that
     * (a) marks it ready and (b) auto-swaps if the user has already committed to this channel.
     */
    private fun preloadChannel(target: Channel) {
        if (target.id == channel?.id) return
        if (sourcePreferences.isDataSaverEnabled()) return  // no pre-buffering on metered
        if (nextPlayer != null && nextPlayerChannelId == target.id) return  // already preloading it
        val selection = sourceSelector.selectBestSource(target)
        val type = sourceOptions.firstOrNull { it.first == selection.type }?.first
            ?: buildSourceOptions(target).firstOrNull()?.first ?: return

        releaseNextPlayer()          // drop any previous (different-channel) preload
        nextPlayerChannelId = target.id
        nextPlayerType = type

        lifecycleScope.launch {
            // Single resolve pass: try cache first, then fresh resolve if empty.
            val allStreams = (streamPreResolver.getCached(target.id, type)
                ?: target.sources[type]?.let { resolver.resolveAll(it) })
                ?: emptyList()
            // Only direct ExoPlayer-compatible streams can be preloaded.
            val stream = allStreams.firstOrNull { !it.requiresBrowser && !it.forceWebView }
            // Abandon if the user surfed away while we were resolving.
            if (stream == null || nextPlayerChannelId != target.id) return@launch

            val dsf = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT).setAllowCrossProtocolRedirects(true)
            if (stream.headers.isNotEmpty()) dsf.setDefaultRequestProperties(stream.headers)

            val ds = sourcePreferences.isDataSaverEnabled()
            val exo = ExoPlayer.Builder(this@TVPlaybackActivity)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
                .setLoadControl(StreamLoadControl.build(ds))
                .setTrackSelector(StreamTrackSelector.build(this@TVPlaybackActivity, ds))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(),
                    false,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()

            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && nextPlayerChannelId == target.id) {
                        nextPlayerReady = true
                        if (pendingSwapChannelId == target.id) performSwap()
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    // Preload failed — drop it; if the user was waiting on it, fall back to a load.
                    if (nextPlayerChannelId == target.id) {
                        val wasPending = pendingSwapChannelId == target.id
                        releaseNextPlayer()
                        if (wasPending) { pendingSwapChannelId = null; playFromIndex(0) }
                    }
                }
            }
            exo.addListener(listener)
            exo.setMediaItem(
                MediaItem.Builder().setUri(stream.url).setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(target.numberedDisplayName()).setStation(target.numberedDisplayName())
                        .setArtist(target.category)
                        .setArtworkUri(target.logoUrl?.let { android.net.Uri.parse(it) })
                        .build(),
                ).build(),
            )
            exo.playWhenReady = false   // buffer only — don't play until promoted
            exo.prepare()
            nextPlayer = exo
            nextPlayerListener = listener
            nextPlayerReady = false
        }
    }

    /** Promote the (ready, matching) [nextPlayer] to be the active player — the seamless swap. */
    private fun performSwap() {
        val np = nextPlayer ?: return
        if (!nextPlayerReady || nextPlayerChannelId != channel?.id) return
        pendingSwapChannelId = null
        nextPlayerListener?.let { np.removeListener(it) }
        nextPlayerListener = null
        nextPlayer = null
        nextPlayerReady = false
        nextPlayerChannelId = ""

        val old = player
        val oldSession = mediaSession
        handler.removeCallbacks(connectWatchdogRunnable)

        player = np
        selectedType = sourceOptions.firstOrNull()?.first
        np.addListener(activeListener)
        playerView.player = np
        np.playWhenReady = true
        mediaSession = MediaSession.Builder(this, np)
            .setId("streamverse_tv_${System.currentTimeMillis()}").build()

        oldSession?.release()
        old?.release()

        progress.visibility = View.GONE
        hideStaticInternal()
        highlightSelectedChip()
        updateNowPlayingOverlay()
        refreshKeepScreenOn()
    }

    private fun releaseNextPlayer() {
        nextPlayerListener?.let { nextPlayer?.removeListener(it) }
        nextPlayerListener = null
        nextPlayer?.release()
        nextPlayer = null
        nextPlayerReady = false
        nextPlayerChannelId = ""
    }

    private fun channelQualityLabel(ch: Channel) = when (ch.quality) {
        com.streamverse.core.domain.model.Quality._4K -> "4K"
        com.streamverse.core.domain.model.Quality.FHD -> "FHD"
        com.streamverse.core.domain.model.Quality.HD  -> "HD"
        com.streamverse.core.domain.model.Quality.SD  -> "SD"
        null -> ""
    }

    /** Switch to a specific channel (used by the guide and any direct tune) — seamless when the
     *  channel was pre-loaded by the guide highlight. */
    private fun tuneToChannel(target: Channel) = requestTune(target)

    // -----------------------------------------------------------------------------------------
    // Source chooser cycling (DPAD ←, or → while the source bar is up)
    // -----------------------------------------------------------------------------------------

    /** Open the source chooser for navigation. Highlight starts on the current source; nothing
     *  switches until the user presses OK. Single-source channels just reveal the now-playing card. */
    private fun openSourceBar() {
        if (sourceOptions.size <= 1) { showOverlays(); return }
        nowPlayingBar.visibility = View.VISIBLE
        sourceBar.visibility = View.VISIBLE
        sourceBarActive = true
        pendingSourceIndex = currentOptionIndex
        (sourceBar.getChildAt(0) as? TextView)?.text = "Watch from   ◄ ►  choose · OK to switch"
        highlightSelectedChip()
        resetHideTimer()
    }

    /** Move the (uncommitted) source highlight. Playback stays on the current source. */
    private fun moveSourceSelection(delta: Int) {
        val n = sourceOptions.size
        if (n <= 1) return
        pendingSourceIndex = ((pendingSourceIndex + delta) % n + n) % n
        highlightSelectedChip()
        resetHideTimer()
    }

    /** OK pressed in the chooser: switch to the highlighted source. On a no-signal screen, OK on
     *  the same source re-tunes it (retry); otherwise an unchanged pick just closes the chooser.
     *  Manual selections are tracked in the session manager and protected by validation recovery. */
    private fun commitSourceSelection() {
        sourceBarActive = false
        if (pendingSourceIndex != currentOptionIndex || stateMachine.shouldShowStatic) {
            val type = sourceOptions.getOrNull(pendingSourceIndex)?.first ?: return
            sessionManager.markSourceSelected(type, PlaybackSessionManager.SourceSelectionMode.MANUAL)
            startValidationRecovery()
            playFromIndex(pendingSourceIndex)
        }
        else hideOverlays()
    }

    private fun closeSourceBar() {
        sourceBarActive = false
        hideOverlays()
    }

    // -----------------------------------------------------------------------------------------
    // Channel guide — a side panel of channels surfed with ▲/▼, tuned with OK
    // -----------------------------------------------------------------------------------------

    private fun buildGuide() {
        val root = playerView.parent as? FrameLayout ?: return
        val d = resources.displayMetrics.density
        val panelW = (380 * d).toInt()

        val panel = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#00060A12"), Color.parseColor("#E6080B12"), Color.parseColor("#F50A0E16")),
            )
            visibility = View.GONE
            elevation = 24 * d
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setPadding((22 * d).toInt(), (30 * d).toInt(), (14 * d).toInt(), (18 * d).toInt())
        }

        val header = TextView(this).apply {
            text = "Channels"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            letterSpacing = 0.04f
            setPadding((6 * d).toInt(), 0, 0, (4 * d).toInt())
        }
        col.addView(header)

        val hint = TextView(this).apply {
            text = "▲ ▼ browse   ·   OK to watch"
            textSize = 11f
            setTextColor(Color.parseColor("#7C8A99"))
            setPadding((6 * d).toInt(), 0, 0, (12 * d).toInt())
        }
        col.addView(hint)

        guideList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TVPlaybackActivity)
            adapter = GuideAdapter()
            isFocusable = false
            setHasFixedSize(true)
            // Don't clip the elevated/expanded highlighted row's shadow.
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        col.addView(guideList)
        panel.addView(col)
        root.addView(panel)
        channelGuide = panel
    }

    private fun openGuide() {
        val panel = channelGuide ?: return
        // Rebuild each open so the list always reflects all available channels,
        // including IPTV/FreeTv/FAST TV/Radio/Independent sources (not just premium).
        guideChannels = orderedForGuide(
            repository.getAllChannels().filter { buildSourceOptions(it).isNotEmpty() },
        )
        if (guideChannels.size < 2) { showOverlays(); return }
        hideOverlays()
        guideIndex = guideChannels.indexOfFirst { it.id == channel?.id }.coerceAtLeast(0)
        guideList.adapter?.notifyDataSetChanged()
        centerGuideOn(guideIndex)
        val w = if (panel.width > 0) panel.width.toFloat() else 380 * resources.displayMetrics.density
        panel.visibility = View.VISIBLE
        panel.translationX = w
        panel.animate().translationX(0f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        guideVisible = true
        resetGuideHideTimer()
    }

    private fun closeGuide() {
        guideVisible = false
        handler.removeCallbacks(hideGuideRunnable)
        val panel = channelGuide ?: return
        val w = if (panel.width > 0) panel.width.toFloat() else 380 * resources.displayMetrics.density
        panel.animate().translationX(w).setDuration(170)
            .withEndAction { panel.visibility = View.GONE }.start()
    }

    private fun moveGuide(delta: Int) {
        val n = guideChannels.size
        if (n < 2) return
        val old = guideIndex
        guideIndex = ((guideIndex + delta) % n + n) % n
        guideList.adapter?.notifyItemChanged(old)
        guideList.adapter?.notifyItemChanged(guideIndex)
        centerGuideOn(guideIndex)
        resetGuideHideTimer()
        // SEAMLESS: pre-load the highlighted channel so pressing OK swaps instantly.
        guideChannels.getOrNull(guideIndex)?.let { schedulePreload(it) }
    }

    /** Orders the guide using the canonical [ChannelNavigationEngine] ordering. */
    private fun orderedForGuide(channels: List<Channel>): List<Channel> {
        val ids = channels.mapTo(java.util.HashSet()) { it.id }
        return navigationEngine.canonical.filter { it.id in ids }
    }

    private fun centerGuideOn(position: Int) {
        (guideList.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(position, (150 * resources.displayMetrics.density).toInt())
    }

    private fun resetGuideHideTimer() {
        handler.removeCallbacks(hideGuideRunnable)
        handler.postDelayed(hideGuideRunnable, GUIDE_TIMEOUT_MS)
    }

    private inner class GuideVH(
        val row: LinearLayout,
        val accent: View,
        val logo: ImageView,
        val name: TextView,
        val meta: TextView,
    ) : RecyclerView.ViewHolder(row)

    private inner class GuideAdapter : RecyclerView.Adapter<GuideVH>() {
        override fun getItemCount() = guideChannels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuideVH {
            val ctx = this@TVPlaybackActivity
            val d = resources.displayMetrics.density
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, (66 * d).toInt(),
                ).also { it.bottomMargin = (4 * d).toInt() }
            }
            val accent = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((4 * d).toInt(), (40 * d).toInt())
            }
            row.addView(accent)
            val logo = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams((60 * d).toInt(), (40 * d).toInt()).also {
                    it.marginStart = (12 * d).toInt(); it.marginEnd = (14 * d).toInt()
                }
            }
            row.addView(logo)
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val name = TextView(ctx).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val meta = TextView(ctx).apply {
                textSize = 11f
                setTextColor(Color.parseColor("#8593A2"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textCol.addView(name)
            textCol.addView(meta)
            row.addView(textCol)
            return GuideVH(row, accent, logo, name, meta)
        }

        override fun onBindViewHolder(h: GuideVH, position: Int) {
            val ch = guideChannels[position]
            val selected = position == guideIndex
            val d = resources.displayMetrics.density
            h.name.text = ch.numberedDisplayName()
            h.name.setTextColor(if (selected) Color.WHITE else Color.parseColor("#C8D2DC"))
            val isLive = channelHealthEngine.isLive(ch.id)
            h.meta.text = buildString {
                if (isLive) append("● LIVE")
                ch.category?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("  ·  "); append(it)
                }
                val q = channelQualityLabel(ch)
                if (q.isNotEmpty()) { if (isNotEmpty()) append("  ·  "); append(q) }
            }
            h.meta.setTextColor(if (isLive) Color.parseColor("#4ADE80") else Color.parseColor("#8593A2"))
            h.row.background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(if (selected) Color.parseColor("#2622D3EE") else Color.TRANSPARENT)
                if (selected) setStroke((1.5f * d).toInt(), Color.parseColor("#5522D3EE"))
            }
            // Highlighted row expands slightly and casts a shadow so it clearly stands out.
            h.row.elevation = if (selected) 10 * d else 0f
            h.row.scaleX = if (selected) 1.04f else 1f
            h.row.scaleY = if (selected) 1.04f else 1f
            h.accent.setBackgroundColor(if (selected) Color.parseColor("#22D3EE") else Color.TRANSPARENT)
            val fb = logoHelper.letterDrawable(this@TVPlaybackActivity, ch.displayName)
            val model = com.streamverse.core.util.ChannelLogoResolver.model(ch)
            if (!model.isNullOrBlank()) {
                Glide.with(h.logo).load(model).placeholder(fb).error(fb).fitCenter().into(h.logo)
            } else {
                Glide.with(h.logo).clear(h.logo)
                h.logo.setImageDrawable(fb)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Overlay management — now-playing info + source bar appear/disappear together
    // -----------------------------------------------------------------------------------------

    private fun showOverlays() {
        nowPlayingBar.visibility = View.VISIBLE
        // Show source bar for multi-source channels, or when static is visible (retry).
        if (sourceOptions.size > 1 || stateMachine.shouldShowStatic) {
            sourceBar.visibility = View.VISIBLE
            highlightSelectedChip()
        }
        resetHideTimer()
    }

    private fun hideOverlays() {
        nowPlayingBar.visibility = View.GONE
        sourceBar.visibility = View.GONE
        sourceBarActive = false   // auto-hide / dismiss also exits source-chooser navigation mode
    }

    private fun resetHideTimer() {
        handler.removeCallbacks(hideOverlaysRunnable)
        handler.postDelayed(hideOverlaysRunnable, OVERLAY_TIMEOUT_MS)
    }

    private fun updateNowPlayingOverlay() {
        val ch = channel ?: return
        val option = sourceOptions.getOrNull(currentOptionIndex)
        channelNameView.text = ch.numberedDisplayName()
        channelMetaView.text = buildString {
            ch.category?.takeIf { it.isNotBlank() }?.let { append(it).append("  ·  ") }
            option?.let { append(it.second) }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Source chip bar (multi-source channels only)
    // -----------------------------------------------------------------------------------------

    private fun buildSourceChips(channel: Channel) {
        sourceChips.removeAllViews()
        val density = resources.displayMetrics.density
        val perSourceHealth = channelHealthEngine.sourceHealthForChannel(channel.id)
        sourceOptions.forEachIndexed { index, (type, label) ->
            val chip = Button(this).apply {
                text     = label
                isAllCaps = false
                tag      = Pair(type, index)
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(
                    (18 * density).toInt(), (8 * density).toInt(),
                    (18 * density).toInt(), (8 * density).toInt(),
                )
                // Chooser navigation is managed by the activity (◄/► + OK), so chips don't take DPAD
                // focus. Touch still works: tapping a chip IS an explicit confirm → switch.
                isFocusable = false
                // Health state indicator drawable
                val health = perSourceHealth[type]
                val stateDrawable = if (health != null) {
                    val color = if (health.consecutiveFailures >= 3) "#EF5350" else "#4CAF50"
                    createCircleDrawable(android.graphics.Color.parseColor(color), density)
                } else null
                if (stateDrawable != null) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(stateDrawable, null, null, null)
                    compoundDrawablePadding = (6 * density).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 40 * density
                    setColor(Color.parseColor("#33FFFFFF"))
                }
                setOnClickListener {
                    sourceBarActive = false
                    playFromIndex(index)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = (12 * density).toInt() }
            sourceChips.addView(chip, lp)
        }
    }

    /** Renders the chips: the PLAYING source is filled cyan; while the chooser is being navigated,
     *  the HIGHLIGHTED (not-yet-confirmed) source gets a white ring so it's clearly "press OK". */
    private fun highlightSelectedChip() {
        val d = resources.displayMetrics.density
        for (i in 0 until sourceChips.childCount) {
            val chip = sourceChips.getChildAt(i) as? Button ?: continue
            val isPlaying = i == currentOptionIndex
            val isCursor = sourceBarActive && i == pendingSourceIndex
            chip.background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(if (isPlaying) Color.parseColor("#22D3EE") else Color.parseColor("#33FFFFFF"))
                if (isCursor) setStroke((3 * d).toInt(), Color.WHITE)
            }
            chip.setTextColor(if (isPlaying) Color.BLACK else Color.WHITE)
            chip.scaleX = if (isCursor) 1.08f else 1f
            chip.scaleY = if (isCursor) 1.08f else 1f
        }
    }

    // -----------------------------------------------------------------------------------------
    // Playback
    // -----------------------------------------------------------------------------------------

    /**
     * Try the next source option for the current channel — safe failover.
     * Uses [SourceSelector.selectForFailover] which prefers verified-playable sources.
     * If no verified source remains, falls back to the next ranked source.
     * If ALL sources exhausted, signals ALL_SOURCES_EXHAUSTED (→ TV static).
     */
    private fun tryNextSource(): Boolean {
        val failing = selectedType
        val chId = channel?.id
        val ch = channel
        if (chId != null && failing != null) {
            channelHealthEngine.recordPlaybackFailure(chId, failing, "tryNextSource")
        }
        // Safe failover: prefer verified-playable sources.
        if (ch != null && failing != null) {
            val tried = mutableSetOf<SourceType>()
            tried.add(failing)
            // Add all sources before currentOptionIndex as tried
            for (i in 0 until currentOptionIndex) {
                sourceOptions.getOrNull(i)?.first?.let { tried.add(it) }
            }
            val failover = sourceSelector.selectForFailover(ch, failing, tried) { streamPreResolver.hasCached(ch.id, it) }
            if (failover != null) {
                val nextIdx = sourceOptions.indexOfFirst { it.first == failover.type }
                if (nextIdx >= 0) {
                    currentOptionIndex = nextIdx
                    sessionManager.markSourceSelected(failover.type, PlaybackSessionManager.SourceSelectionMode.FAILOVER)
                    sessionManager.recordFailover(failing, failover.type, "playback_failure")
                    handler.removeCallbacks(connectWatchdogRunnable)
                    refreshKeepScreenOn()
                    playFromIndex(currentOptionIndex)
                    return true
                }
            }
        }
        // All sources exhausted → TV static
        stateMachine.transition(PlaybackStateMachine.Event.ALL_SOURCES_EXHAUSTED)
        return false
    }

    /** Recover from a failed manual source selection back to the default verified source.
     *  Shows a brief OSD message explaining the switch. */
    private fun recoverToDefault() {
        val defaultSource = sessionManager.recoverToDefault()
        if (defaultSource != null) {
            val ch = channel ?: return
            val defaultIdx = sourceOptions.indexOfFirst { it.first == defaultSource }
            if (defaultIdx >= 0) {
                sessionManager.recordFailover(
                    sessionManager.getCurrentPlaybackSource() ?: SourceType.BROADCASTER,
                    defaultSource,
                    "manual_source_failure",
                    wasUserSelection = true,
                )
                currentOptionIndex = defaultIdx
                handler.removeCallbacks(connectWatchdogRunnable)
                refreshKeepScreenOn()
                showToastMessage("Source unavailable — switched back")
                playFromIndex(currentOptionIndex)
            }
        }
    }

    /** Start validation recovery timer for a manually selected source. */
    private fun startValidationRecovery() {
        handler.removeCallbacks(validationRecoveryRunnable)
        handler.postDelayed(validationRecoveryRunnable, validationTimeoutMs)
    }

    /** Cancel the validation recovery timer — source validated successfully. */
    private fun cancelValidationRecovery() {
        handler.removeCallbacks(validationRecoveryRunnable)
    }

    /** Show a brief unobtrusive toast message. */
    private fun showToastMessage(message: String) {
        val toast = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT)
        toast.duration = android.widget.Toast.LENGTH_SHORT
        toast.show()
    }

    private fun playFromIndex(index: Int) {
        val ch = channel ?: return
        currentOptionIndex = index
        val (type, _) = sourceOptions[index]
        selectedType = type
        stateMachine.transition(PlaybackStateMachine.Event.CHANNEL_CHANGE)
        if (stateMachine.state != PlaybackStateMachine.State.LOADING) {
            stateMachine.transition(PlaybackStateMachine.Event.LOAD)
        }
        highlightSelectedChip()
        updateNowPlayingOverlay()
        showOverlays()
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Single resolve pass: try cache first, then fresh resolve if empty.
            val source = ch.sources[type]
            val allStreams = (source?.let { streamPreResolver.getCached(ch.id, type) }
                ?: source?.let { resolver.resolveAll(it) })
                ?: emptyList()

            // Try direct ExoPlayer‑compatible streams first.
            val direct = allStreams.firstOrNull { !it.requiresBrowser && !it.forceWebView }
            if (direct != null) {
                stateMachine.transition(PlaybackStateMachine.Event.URL_RESOLVED)
                startPlayback(direct)
                return@launch
            }

            // No direct stream — try extraction from browser‑required URLs.
            val browser = allStreams.firstOrNull { it.requiresBrowser }
            if (browser != null) {
                stateMachine.transition(PlaybackStateMachine.Event.URL_RESOLVED)
                startStreamExtraction(browser.url, source?.headers ?: emptyMap())
                return@launch
            }

            // Last resort: force‑WebView mode (visible WebView player).
            val webViewUrl = allStreams.firstOrNull { it.forceWebView }
            if (webViewUrl != null) {
                stateMachine.transition(PlaybackStateMachine.Event.URL_RESOLVED)
                startWebViewPlayback(webViewUrl.url, source?.headers ?: emptyMap())
                return@launch
            }

            tryNextSource()
        }
    }

    /** Launch a hidden WebView that loads [pageUrl] and extracts an HLS/MPD manifest URL,
     *  then starts ExoPlayer playback with the result.  On failure, falls to the next source. */
    private fun startStreamExtraction(pageUrl: String, headers: Map<String, String>) {
        val done = AtomicBoolean(false)
        val mainHandler = handler

        val webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            }
            val capture: (String) -> Unit = { url ->
                if (isMediaStreamUrl(url) && done.compareAndSet(false, true)) {
                    mainHandler.post {
                        val root = playerView.parent as? FrameLayout
                        root?.removeView(this)
                        startPlayback(StreamInfo(url = url, headers = headers))
                    }
                }
            }
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onFound(url: String) { capture(url) }
            }, "SVExtract")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest,
                ): WebResourceResponse? {
                    val u = request.url.toString()
                    if (isMediaStreamUrl(u)) {
                        mainHandler.post { capture(u) }
                    }
                    if (AdBlocker.isAdUrl(u)) {
                        return WebResourceResponse("text/plain", "UTF-8", null)
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    val hook = """
                        (function(){
                          if(window.__svhook) return; window.__svhook=1;
                          function rep(u){ try{ if(u && /\.(m3u8|mpd)(\?|${'$'}|#)/i.test(''+u)) SVExtract.onFound(''+u); }catch(e){} }
                          try{ var of=window.fetch; if(of){ window.fetch=function(){ try{ var a=arguments[0]; rep(a&&(a.url||a)); }catch(e){} return of.apply(this,arguments); }; } }catch(e){}
                          try{ var oo=XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open=function(m,u){ rep(u); return oo.apply(this,arguments); }; }catch(e){}
                        })();
                    """.trimIndent()
                    val scan = """
                        (function(){
                          try {
                            document.querySelectorAll('video').forEach(function(v){ v.muted=true; v.autoplay=true; var p=v.play(); if(p&&p.catch)p.catch(function(){}); });
                            document.querySelectorAll('video,source').forEach(function(v){ if(v.src) SVExtract.onFound(v.src); });
                            var html=document.documentElement.innerHTML;
                            var re=/https?:\/\/[^"'\\\s<>()]+\.(m3u8|mpd)[^"'\\\s<>()]*/ig, m;
                            while((m=re.exec(html))!==null){ SVExtract.onFound(m[0]); }
                          } catch(e){}
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(hook, null)
                    view.evaluateJavascript(scan, null)
                }
            }
            // Timeout: if extraction takes > 20 s, give up and try the next source.
            mainHandler.postDelayed({
                if (done.compareAndSet(false, true)) {
                    playerView.parent?.let { (it as? FrameLayout)?.removeView(this) }
                    tryNextSource()
                }
            }, 20_000)
            loadUrl(pageUrl)
        }
        // Attach the hidden WebView so JavaScript execution is allowed.
        (playerView.parent as? FrameLayout)?.addView(webView)
    }

    /**
     * Fall back to a visible WebView when no other playback mode is available
     * (e.g. a YouTube embed or custom page that must run JavaScript).  This
     * mirrors the phone app's WEBVIEW [PlayerStreamMode] handling.
     */
    private fun startWebViewPlayback(pageUrl: String, headers: Map<String, String>) {
        val webView = WebView(this).apply {
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
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (AdBlocker.isAdUrl(request.url.toString())) {
                        return WebResourceResponse("text/plain", "UTF-8", null)
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    // Let the WebView handle its own fullscreen if needed.
                }
            }
        }
        // Replace player‑surface area with the WebView.
        progress.visibility = View.GONE
        playerView.visibility = View.GONE
        val container = playerView.parent as? FrameLayout
        container?.addView(webView)
        // Remember so we can tear it down on next tune.
        webViewPlaceholder = webView
        webView.loadUrl(pageUrl)
    }

    /** Tear down any visible WebView left from [startWebViewPlayback]. */
    private fun removeWebViewPlaceholder() {
        webViewPlaceholder?.let { wv ->
            val container = wv.parent as? FrameLayout
            container?.removeView(wv)
            webViewPlaceholder = null
        }
    }

    private fun isMediaStreamUrl(url: String): Boolean =
        Regex("\\.(m3u8|mpd)(\\?|#|\$)").containsMatchIn(url)

    /** Hold the screen awake while playing or any active loading/recovery state. */
    private fun refreshKeepScreenOn() {
        val active = stateMachine.state in setOf(
            PlaybackStateMachine.State.LOADING,
            PlaybackStateMachine.State.BUFFERING,
            PlaybackStateMachine.State.RECOVERING,
            PlaybackStateMachine.State.FAILED,
        ) || (player?.let { it.playWhenReady && it.playbackState == Player.STATE_READY } == true)
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateProgressVisibility() {
        progress.visibility = when (stateMachine.state) {
            PlaybackStateMachine.State.LOADING,
            PlaybackStateMachine.State.BUFFERING,
            PlaybackStateMachine.State.RECOVERING,
            PlaybackStateMachine.State.SWITCHING_SOURCES,
            PlaybackStateMachine.State.FAILED -> View.VISIBLE
            else -> View.GONE
        }
    }

    /**
     * Listener for whichever ExoPlayer is currently ACTIVE. All state transitions route through
     * the [PlaybackStateMachine] so static gating, progress visibility, and screen-on are
     * handled centrally via the [stateMachine.onStateChanged] callback.
     */
    private val activeListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            playerView.visibility = View.VISIBLE
            refreshKeepScreenOn()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) playerView.visibility = View.VISIBLE
            refreshKeepScreenOn()
            if (isPlaying) {
                val chId = channel?.id ?: return
                val srcType = selectedType ?: return
                channelHealthEngine.recordPlaybackSuccess(chId, srcType)
                // Complete validation in session manager — source confirmed working.
                sessionManager.markValidationComplete(success = true)
                cancelValidationRecovery()
                // Recalculate default source (health may have changed), but never interrupt
                // a working manual selection.
                val ch = channel
                if (ch != null) {
                    val updated = sourceSelector.recalculateDefault(
                        ch,
                        sessionManager.getCurrentPlaybackSource(),
                        sessionManager.getSelectionMode() == PlaybackSessionManager.SourceSelectionMode.MANUAL,
                        true,
                    )
                    if (updated != null) {
                        sessionManager.updateDefaultSource(updated.type)
                    }
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshKeepScreenOn()

        override fun onPlaybackStateChanged(state: Int) {
            refreshKeepScreenOn()
            when (state) {
                Player.STATE_BUFFERING -> Unit
                Player.STATE_READY -> {
                    stateMachine.transition(PlaybackStateMachine.Event.PLAYBACK_READY)
                    handler.removeCallbacks(connectWatchdogRunnable)
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handler.removeCallbacks(connectWatchdogRunnable)
            val p = player ?: return
            val chId = channel?.id
            val srcType = selectedType

            // Live-window drift: seek to live edge and resume (always allowed).
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition(); p.prepare(); return
            }
            // Record playback failure for health tracking.
            if (chId != null && srcType != null) {
                channelHealthEngine.recordPlaybackFailure(chId, srcType, error.localizedMessage ?: "player_error")
            }
            // Mark validation as failed in session manager.
            sessionManager.markValidationComplete(success = false)
            cancelValidationRecovery()
            // If manual selection failed before ever validating (never played), recover to default.
            if (sessionManager.getSelectionMode() == PlaybackSessionManager.SourceSelectionMode.MANUAL &&
                !sessionManager.isSourceValidated()) {
                recoverToDefault()
                return
            }
            stateMachine.transition(PlaybackStateMachine.Event.PLAYBACK_ERROR)
            if (stateMachine.state == PlaybackStateMachine.State.RECOVERING) {
                tryNextSource()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPlayback(stream: StreamInfo) {
        pendingSwapChannelId = null
        handler.removeCallbacks(preloadRunnable)
        releaseNextPlayer()
        player?.release()
        mediaSession?.release()
        mediaSession = null

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        if (stream.headers.isNotEmpty()) {
            dataSourceFactory.setDefaultRequestProperties(stream.headers)
        }

        val hasClearKey = stream.hasDrm
        val jwkBytes = if (hasClearKey) buildClearKeyLicense(stream.drmKeyId!!, stream.drmKey!!) else null

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        if (hasClearKey) {
            val drmSessionManager = androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID) {
                    androidx.media3.exoplayer.drm.FrameworkMediaDrm.newInstance(C.CLEARKEY_UUID)
                }
                .build(object : androidx.media3.exoplayer.drm.MediaDrmCallback {
                    override fun executeKeyRequest(
                        uuid: java.util.UUID,
                        request: androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest,
                    ): ByteArray = jwkBytes!!

                    override fun executeProvisionRequest(
                        uuid: java.util.UUID,
                        request: androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest,
                    ): ByteArray = ByteArray(0)
                })
            mediaSourceFactory.setDrmSessionManagerProvider(
                object : androidx.media3.exoplayer.drm.DrmSessionManagerProvider {
                    override fun get(mediaItem: MediaItem): androidx.media3.exoplayer.drm.DrmSessionManager = drmSessionManager
                }
            )
        }

        val ds = sourcePreferences.isDataSaverEnabled()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(StreamLoadControl.build(ds))
            .setTrackSelector(com.streamverse.core.util.StreamTrackSelector.build(this, ds))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        exo.addListener(activeListener)

        val ch = channel
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(stream.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ch?.numberedDisplayName() ?: "StreamVerse TV")
                    .setStation(ch?.numberedDisplayName())
                    .setArtist(ch?.category)
                    .setArtworkUri(ch?.logoUrl?.let { android.net.Uri.parse(it) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
        if (hasClearKey) {
            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build()
            )
        }
        val mediaItem = mediaItemBuilder.build()
        exo.setMediaItem(mediaItem)
        exo.playWhenReady = true
        exo.prepare()
        player = exo
        playerView.player = exo
        // Hide the surface until the first frame renders, preventing the decoder's
        // uninitialised buffer (black / noise) from being visible to the user.
        playerView.visibility = View.INVISIBLE

        // Publish a media session so Alexa (FireTV) / Assistant (Android TV) voice commands
        // and the system media transport controls drive playback.
        mediaSession = MediaSession.Builder(this, exo)
            .setId("streamverse_tv_${System.currentTimeMillis()}")
            .build()

        // Start connect watchog: if this source doesn't start playing within CONNECT_TIMEOUT_MS,
        // advance to the next source. Cancelled on STATE_READY or onPlayerError.
        handler.removeCallbacks(connectWatchdogRunnable)
        handler.postDelayed(connectWatchdogRunnable, connectTimeoutMs)
    }

    private fun buildClearKeyLicense(keyIdHex: String, keyHex: String): ByteArray {
        val kidBytes = hexStringToByteArray(keyIdHex)
        val keyBytes = hexStringToByteArray(keyHex)
        val jwk = org.json.JSONObject().apply {
            put("keys", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("kty", "oct")
                    put("kid", android.util.Base64.encodeToString(kidBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
                    put("k", android.util.Base64.encodeToString(keyBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
                })
            })
            put("type", "temporary")
        }
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

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** One option per provider, friendly-labelled, best-first (curated → premium → scraped). */
    private fun buildSourceOptions(channel: Channel): List<Pair<SourceType, String>> {
        val enabled = sourcePreferences.enabled()
        val seen = LinkedHashSet<SourceProvider>()
        return sourceResolutionEngine.rankSources(channel).mapNotNull { type ->
            val provider = SourceProvider.forType(type)
            if (channel.sources.containsKey(type) && seen.add(provider) && enabled[provider] != false) type to provider.displayName
            else null
        }
    }

    /**
     * Show the no-signal screen — authentic TV snow plus an elegant OSD that fades in then
     * recedes — instead of a black screen or an error dialog. [raw] is any technical error/status;
     * it is mapped to television-style copy so the user reads "lost signal", never a stack trace.
     */
    private fun showStaticInternal(raw: String? = null) {
        val msg = com.streamverse.core.ui.SignalMessages.forError(raw)
        progress.visibility = View.GONE
        nowPlayingBar.visibility = View.GONE
        sourceBar.visibility = View.GONE
        restoreConfiguredIntensity()
        handler.removeCallbacks(staticFadeOutRunnable)
        handler.removeCallbacks(osdRecedeRunnable)

        signalHeadline.text = msg.headline.uppercase()
        signalDetail.text = msg.detail
        signalOsd.visibility = View.VISIBLE

        if (noSignalOverlay.visibility != View.VISIBLE) {
            noSignalOverlay.alpha = 0f
            noSignalOverlay.visibility = View.VISIBLE
            noSignalOverlay.animate().alpha(1f).setDuration(450).start()
        }
        signalOsd.alpha = 0f
        signalOsd.animate().alpha(1f).setDuration(500).start()
        handler.postDelayed(osdRecedeRunnable, 4500)
    }

    /** Fade the snow away (on recovery, or whenever real video is taking over) and reset it. */
    private fun hideStaticInternal() {
        handler.removeCallbacks(staticFadeInRunnable)
        handler.removeCallbacks(osdRecedeRunnable)
        if (noSignalOverlay.visibility != View.VISIBLE) return
        noSignalOverlay.animate().alpha(0f).setDuration(350).withEndAction {
            noSignalOverlay.visibility = View.GONE
            restoreConfiguredIntensity()
        }.start()
    }

    /** Fade the snow in (delayed — used by [staticFadeInRunnable]). */
    private fun fadeInStaticInternal() {
        if (noSignalOverlay.visibility == View.VISIBLE) return
        noSignalOverlay.alpha = 0f
        noSignalOverlay.visibility = View.VISIBLE
        noSignalOverlay.animate().alpha(1f).setDuration(450).start()
    }

    /** Fade the snow out (delayed — used by [staticFadeOutRunnable]). */
    private fun fadeOutStaticInternal() {
        if (noSignalOverlay.visibility != View.VISIBLE) return
        noSignalOverlay.animate().alpha(0f).setDuration(350).withEndAction {
            noSignalOverlay.visibility = View.GONE
            restoreConfiguredIntensity()
        }.start()
    }

    /**
     * A very brief snow flash under the spinner on a fresh load — the "tuning" feel of turning a
     * dial. Seamless preloaded swaps have no gap, so they never call this. When the burst
     * preference is off (or we were already showing a no-signal error), this just clears the snow.
     */
    private fun showTuningBurst() {
        handler.removeCallbacks(osdRecedeRunnable)
        handler.removeCallbacks(staticFadeInRunnable)
        val burst = getSharedPreferences("playback_prefs", MODE_PRIVATE)
            .getBoolean("static_channel_burst", true)
        if (!burst) {
            noSignalOverlay.animate().cancel()
            noSignalOverlay.visibility = View.GONE
            restoreConfiguredIntensity()
            return
        }
        signalOsd.visibility = View.GONE
        tvStatic.intensity = com.streamverse.core.ui.TvStaticView.Intensity.LOW
        noSignalOverlay.animate().cancel()
        noSignalOverlay.alpha = 1f
        noSignalOverlay.visibility = View.VISIBLE
    }

    private fun restoreConfiguredIntensity() {
        tvStatic.intensity = com.streamverse.core.ui.TvStaticView.Intensity.fromKey(
            getSharedPreferences("playback_prefs", MODE_PRIVATE).getString("static_intensity", "medium"),
        )
    }

    /** Prepends channelId to the "Continue Watching" history list (max 12 entries). */
    private fun recordWatchHistory(channelId: String) {
        val prefs    = getSharedPreferences("sv_tv_history", MODE_PRIVATE)
        val existing = prefs.getString("recent_channels", "")!!
            .split(",").filter { it.isNotBlank() }
        val updated  = (listOf(channelId) + existing.filter { it != channelId }).take(12)
        prefs.edit().putString("recent_channels", updated.joinToString(",")).apply()
    }

    private fun observeSourceHealth(channelId: String) {
        healthJob?.cancel()
        val perSource = channelHealthEngine.sourceHealthForChannel(channelId)
        if (perSource.isNotEmpty()) {
            runOnUiThread { refreshChipHealthIndicators(perSource) }
        }
    }

    private fun refreshChipHealthIndicators(health: Map<SourceType, SourceHealth>) {
        val d = resources.displayMetrics.density
        for (i in 0 until sourceChips.childCount) {
            val chip = sourceChips.getChildAt(i) as? Button ?: continue
            val (type, _) = sourceOptions.getOrNull(i) ?: continue
            val sh = health[type]
            val drawable = if (sh != null) {
                val color = if (sh.consecutiveFailures >= 3) "#EF5350" else "#4CAF50"
                createCircleDrawable(android.graphics.Color.parseColor(color), d)
            } else null
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
            chip.compoundDrawablePadding = if (drawable != null) (6 * d).toInt() else 0
        }
    }

    private fun createCircleDrawable(color: Int, density: Float): android.graphics.drawable.GradientDrawable {
        val size = (8 * density).toInt()
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setSize(size, size)
            setColor(color)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------------------------

    companion object {
        const val EXTRA_CHANNEL_ID = "channelId"
        private const val OVERLAY_TIMEOUT_MS = 3_000L
        // Channel guide auto-dismiss after inactivity.
        private const val GUIDE_TIMEOUT_MS = 5_000L
        // Idle time after the last ▲/▼ press before the surfed-to channel actually tunes.
        private const val ZAP_TUNE_DELAY_MS = 200L
        // Settle time before a highlighted/previewed channel begins pre-loading for a seamless swap.
        private const val PRELOAD_DEBOUNCE_MS = 100L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
