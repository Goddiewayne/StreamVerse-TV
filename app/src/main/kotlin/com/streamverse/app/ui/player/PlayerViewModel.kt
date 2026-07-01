package com.streamverse.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.ChannelHealthEngine
import com.streamverse.core.data.PlaybackSessionManager
import com.streamverse.core.data.SourceHealth
import com.streamverse.core.data.SourceHealthPreferences
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.WatchHistoryPreferences
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.PlaybackPreloader
import com.streamverse.core.util.SourceResolutionEngine
import com.streamverse.core.util.SourceSelector
import com.streamverse.core.util.StreamInfo
import com.streamverse.core.util.StreamPreResolver
import com.streamverse.core.util.StreamResolver
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import android.app.Application
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class PlayerStreamMode {
    EXOPLAYER,
    EXTRACTING,   // hidden WebView is extracting the real stream URL for in-app playback
    WEBVIEW,
    CUSTOM_TAB,
    FIREFOX,
}

/** A user-selectable source for a channel, labelled with its friendly provider name. */
data class SourceOption(
    val type: SourceType,
    val label: String,
)

data class PlayerUiState(
    // True while a channel is loaded/playing — drives whether the persistent player surface (full
    // or minimized) is shown at all. The activity-scoped ViewModel keeps playing across navigation,
    // so this stays true (and audio/video continue) after the user backs out to a mini player.
    val active: Boolean = false,
    val channel: Channel? = null,
    val availableSources: List<SourceOption> = emptyList(),
    val selectedSource: SourceType? = null,
    val streamUrl: String? = null,
    val streamReferer: String? = null,
    val streamHeaders: Map<String, String> = emptyMap(),
    val extractUrl: String? = null,
    val drmKeyId: String? = null,
    val drmKey: String? = null,
    val drmLicenseUrl: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val canSurf: Boolean = false,
    // Whether ExoPlayer should be playing. Normally true; flipped to false only when the app is
    // backgrounded and "continue when screen off" is disabled, so playback pauses cleanly.
    val playWhenReady: Boolean = true,
    // The full nearby-channels list for the in-player channel guide (portrait), ordered like Home.
    val guideChannels: List<Channel> = emptyList(),
    val mode: PlayerStreamMode = PlayerStreamMode.EXOPLAYER,
    val customTabUrl: String? = null,
    val firefoxUrl: String? = null,
    val webViewLoading: Boolean = false,
    val webViewTitle: String = "",
    val loginJs: String? = null,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val application: Application,
    private val repository: ChannelRepository,
    private val streamResolver: StreamResolver,
    private val streamPreResolver: StreamPreResolver,
    private val watchHistory: WatchHistoryPreferences,
    private val sourceHealth: SourceHealthPreferences,
    private val sourcePreferences: SourcePreferences,
    private val sourceResolutionEngine: SourceResolutionEngine,
    private val channelHealthEngine: ChannelHealthEngine,
    private val sourceSelector: SourceSelector,
    private val sessionManager: PlaybackSessionManager,
    private val playbackPreloader: PlaybackPreloader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _sourceHealthStates = MutableStateFlow<Map<SourceType, SourceHealth>>(emptyMap())
    val sourceHealthStates: StateFlow<Map<SourceType, SourceHealth>> = _sourceHealthStates.asStateFlow()

    /** A pre-built ExoPlayer (already at STATE_READY) for the next channel.
     *  Set by [open] when [PlaybackPreloader] has one cached; consumed by
     *  [ExoPlayerPlayer] which takes ownership. */
    private val _takenPlayer = MutableStateFlow<ExoPlayer?>(null)
    val takenPlayer: StateFlow<ExoPlayer?> = _takenPlayer.asStateFlow()

    private var dataSaverEnabled = sourcePreferences.isDataSaverEnabled()

    private var pendingUrls: MutableList<StreamInfo> = mutableListOf()
    private var currentChannelId: String? = null
    @Volatile private var currentChannel: Channel? = null

    // Channel-surf list: category-mates the viewer can flip through with ◀ Prev / Next ▶.
    private var surfChannels: List<Channel> = emptyList()

    // Smart cross-source fallback: the ordered list of source types to try for the current
    // channel and how far down it we are. If a source can't start, we advance to the next; only
    // when every source has failed does the channel resolve to the no-signal static.
    // Every source that has been attempted (including the currently playing one). Reset on
    // explicit channel/source switch. Used to skip already-tried sources when advancing.
    private val triedSources = mutableSetOf<SourceType>()
    // Watchdog: if a source URL doesn't start playing within the timeout, we advance.
    private var connectWatchdog: kotlinx.coroutines.Job? = null
    @Volatile private var currentSourceConnected = false
    // Once the source plays its first frame, auto-source-fallback is disabled — only the user
    // can switch sources. A mid-playback error shows the error/static, never auto-advances.
    @Volatile private var sourceHasEverPlayed = false

    // Validation recovery: if a manually selected source fails validation within the
    // timeout, auto-recover to the default verified source.
    private var validationRecoveryJob: kotlinx.coroutines.Job? = null

    private var healthJob: kotlinx.coroutines.Job? = null

    private fun observeSourceHealth(channelId: String) {
        healthJob?.cancel()
        _sourceHealthStates.value = channelHealthEngine.sourceHealthForChannel(channelId)
        healthJob = viewModelScope.launch {
            channelHealthEngine.sourceHealthUpdates.collect { updates ->
                _sourceHealthStates.value = updates[channelId] ?: emptyMap()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackPreloader.releaseAll()
        _takenPlayer.value?.release()
        _takenPlayer.value = null
    }

    private companion object {
        // How long a source gets to actually start playing before fallback gives up on it. Covers a
        // dead URL that hangs or a source stuck retrying — not just hard errors.
        // Increased from 10s to 25s to give slow CDNs time to respond and prevent premature
        // source switching when the stream starts playing but the watchdog hasn't been cancelled.
        const val CONNECT_TIMEOUT_MS = 25_000L

        // How long a manually selected source gets to start playing before we auto-recover
        // to the default verified source. Separate from connect timeout because it gates the
        // full "validation" window, not just the initial connection.
        const val VALIDATION_TIMEOUT_MS = 15_000L
    }

    /**
     * Open a channel in the (persistent) player. If a channel is already playing in ExoPlayer, the
     * switch is SEAMLESS — the current video stays on screen until the new stream has buffered, so
     * picking another channel from the home/mini player flips channels gaplessly. Re-opening the
     * channel that's already loaded just re-expands the player without reloading.
     */
    fun open(id: String) {
        if (id == currentChannelId && _uiState.value.channel != null) {
            _uiState.value = _uiState.value.copy(active = true)
            return
        }
        _uiState.value = _uiState.value.copy(active = true)
        viewModelScope.launch {
            var channel = repository.getChannelById(id)
            if (channel == null) {
                val refId = id.substringAfter("_", "")
                val refIdFull = if (!refId.startsWith("yt_")) "yt_$refId" else refId
                channel = repository.getChannelBySourceRef(SourceType.YOUTUBE_TV, refIdFull)
                if (channel != null) {
                    android.util.Log.d("PlayerViewModel", "open: resolved by sourceRef id=$id → channel=${channel.id}")
                }
            }
            val preloaded = channel?.let { c ->
                val type = sourceSelector.selectDefaultVerifiedSource(c).type
                playbackPreloader.take(c.id, type)
            }
            if (preloaded != null && channel != null) {
                currentChannel = channel
                currentChannelId = channel.id
                buildSurfList(channel)
                resetFallbackState()
                watchHistory.record(channel.id, channel.displayName, channel.logoUrl)
                val options = buildSourceOptions(channel)
                _uiState.value = _uiState.value.copy(
                    channel = channel,
                    availableSources = options,
                    canSurf = surfChannels.size >= 2,
                    guideChannels = buildGuideList(),
                    isLoading = false,
                    error = null,
                )
                _takenPlayer.value = preloaded.player
                // Pre-load the next batch for the channel surfed to.
                if (!dataSaverEnabled) {
                    streamPreResolver.preResolveAll(channel)
                    preloadNeighborPlayers(application, channel)
                }
                return@launch
            }
            loadChannel(id, seamless = canSwapSeamlessly())
        }
    }

    /** Called by the composable after it takes ownership of [takenPlayer]. */
    fun clearTakenPlayer() {
        _takenPlayer.value = null
    }

    // Tracks whether WE auto-paused for background, so foregrounding only resumes our own pause
    // (never overrides a manual pause).
    private var pausedForBackground = false

    /** App backgrounded / screen off. Pause only when the user has opted out of background play. */
    fun onEnterBackground(allowBackgroundPlayback: Boolean) {
        if (!allowBackgroundPlayback && _uiState.value.playWhenReady) {
            pausedForBackground = true
            _uiState.value = _uiState.value.copy(playWhenReady = false)
        }
    }

    /** App foregrounded. Resume only if we paused it for background. */
    fun onEnterForeground() {
        if (pausedForBackground) {
            pausedForBackground = false
            _uiState.value = _uiState.value.copy(playWhenReady = true)
        }
    }

    /** Fully dismiss the player — stops playback and tears down the surface (mini player closed). */
    fun close() {
        connectWatchdog?.cancel()
        connectWatchdog = null
        cancelValidationRecovery()
        currentChannel = null
        currentChannelId = null
        sessionManager.clearSession()
        _uiState.value = PlayerUiState(active = false, isLoading = false)
    }

    /**
     * Start the validation recovery timer for a manually selected source.
     * If the source doesn't reach "playing" state within VALIDATION_TIMEOUT_MS,
     * auto-recover to the default verified source and show a brief message.
     */
    private fun startValidationRecovery(channel: Channel) {
        cancelValidationRecovery()
        validationRecoveryJob = viewModelScope.launch {
            delay(VALIDATION_TIMEOUT_MS)
            if (sessionManager.getSelectionMode() == PlaybackSessionManager.SourceSelectionMode.MANUAL &&
                !sessionManager.isSourceValidated()) {
                val defaultSource = sessionManager.recoverToDefault()
                if (defaultSource != null) {
                    android.util.Log.d("PlayerViewModel", "validationRecovery: manual source failed, recovering to default $defaultSource")
                    sessionManager.recordFailover(
                        sessionManager.getCurrentPlaybackSource() ?: SourceType.VERIFIED,
                        defaultSource,
                        "validation_timeout",
                        wasUserSelection = true,
                    )
                    resetFallbackState()
                    resolveAndPlay(channel, defaultSource, seamless = canSwapSeamlessly())
                    _uiState.value = _uiState.value.copy(error = "Source unavailable — switched back")
                }
            }
        }
    }

    private fun cancelValidationRecovery() {
        validationRecoveryJob?.cancel()
        validationRecoveryJob = null
    }

    private fun loadChannel(id: String, seamless: Boolean = false) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    // Seamless surf/source-switch keeps the current video on screen (no isLoading
                    // teardown) while the next stream resolves; the player composable then swaps
                    // gaplessly once the new stream has buffered.
                    _uiState.value = if (seamless) {
                        _uiState.value.copy(error = null)
                    } else {
                        _uiState.value.copy(isLoading = true, webViewLoading = false, error = null)
                    }

                    // Fast path: channel is already in memory from the home screen load.
                    // Only trigger a full load if the channel truly cannot be found, so that
                    // a deep‑link arriving before the catalogue is ready still resolves.
                    var channel = repository.getChannelById(id)
                    if (channel == null) {
                        android.util.Log.d("PlayerViewModel", "loadChannel: first lookup id=$id → null")
                        repository.load()
                        android.util.Log.d("PlayerViewModel", "loadChannel: first lookup id=$id → null (still)")
                        channel = repository.getChannelById(id)
                        if (channel != null) {
                            android.util.Log.d("PlayerViewModel", "loadChannel: second lookup id=$id → found=${channel.id}")
                        }
                        if (channel == null) {
                            android.util.Log.d("PlayerViewModel", "loadChannel: need sourceRef lookup for id=$id")
                            val refId = id.substringAfter("_", "")
                            val refIdFull = if (!refId.startsWith("yt_")) "yt_$refId" else refId
                            android.util.Log.d("PlayerViewModel", "loadChannel: sourceRef id=$refIdFull referenceId for YOUTUBE_TV")
                            channel = repository.getChannelBySourceRef(SourceType.YOUTUBE_TV, refIdFull)
                            if (channel != null) {
                                android.util.Log.d("PlayerViewModel", "loadChannel: resolved by sourceRef id=$id → channel=${channel.id}")
                            }
                            if (channel == null) {
                                android.util.Log.d("PlayerViewModel", "loadChannel: fallback to ANY channel")
                                channel = repository.getAllChannels().find { ch ->
                                    android.util.Log.d("PlayerViewModel", "  checking: id=${ch.id} sources=${ch.sources.keys}")
                                    ch.id.contains("nasa") || ch.id.contains("youtube")
                                }
                                if (channel != null) {
                                    android.util.Log.d("PlayerViewModel", "loadChannel: fallback id=$id → channel=${channel.id}")
                                }
                            }
                        }
                    }
                    if (channel == null) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Channel not found")
                        return@withContext
                    }

                    currentChannel = channel
                    currentChannelId = channel.id
                    buildSurfList(channel)
                    resetFallbackState()
                    // Seamless channel surfing: warm the prev/next channels' streams in the
                    // background so a ◀ Prev / Next ▶ flip resolves from cache with no network
                    // stall — the brief switch is then just the analogue "tuning" static.
                    preResolveNeighbors()
                    // Record for the Home "Continue Watching" row.
                    watchHistory.record(channel.id, channel.displayName, channel.logoUrl)
                    android.util.Log.d("PlayerViewModel", "loadChannel: found channel id=${channel.id} name=${channel.displayName} sources=${channel.sources.keys}")

                    val options = buildSourceOptions(channel)
                    if (options.isEmpty()) {
                        _uiState.value = _uiState.value.copy(channel = channel, isLoading = false, error = "No stream available")
                        return@withContext
                    }
                    _uiState.value = _uiState.value.copy(
                        channel = channel,
                        availableSources = options,
                        canSurf = surfChannels.size >= 2,
                        guideChannels = buildGuideList(),
                    )
                    currentSourceConnected = false
                    connectWatchdog?.cancel()
                    connectWatchdog = null
                    // Observe per-source health for reactive UI
                    observeSourceHealth(id)
                    // Pre-resolve ALL sources in parallel so failover is instant.
                    // (Skipped in data-saver mode to avoid background HTTP requests.)
                    if (!dataSaverEnabled) {
                        streamPreResolver.preResolveAll(channel)
                        preloadNeighborPlayers(application, channel)
                    }
                    // Intelligent default: use verified-playable source first, then fallback.
                    val selection = sourceSelector.selectDefaultVerifiedSource(channel)
                    sessionManager.startSession(channel, selection.type)
                    resolveAndPlay(channel, selection.type, seamless)
                }
            } catch (e: Exception) {
                val msg = if (e is kotlinx.coroutines.CancellationException) "Cancelled" else e.message ?: "Failed"
                _uiState.value = _uiState.value.copy(isLoading = false, error = msg)
            }
        }
    }

    /**
     * The in-player channel guide list: the full browsable catalogue in Home order (so the guide
     * mirrors what the user sees on Home), restricted to playable channels. Falls back to the surf
     * list if the home catalogue isn't ready yet.
     */
    private fun buildGuideList(): List<Channel> {
        val home = repository.getCachedChannels().filter { it.sources.isNotEmpty() }
        return if (home.isNotEmpty()) home else surfChannels
    }

    /** Builds the channel-surf list — category-mates first, the full catalogue as fallback. */
    private fun buildSurfList(current: Channel) {
        val all = repository.getCachedChannels()
        val playable = all.filter { it.sources.isNotEmpty() }
        val sameCategory = playable.filter {
            !current.category.isNullOrBlank() && it.category == current.category
        }
        surfChannels = when {
            sameCategory.size >= 2 -> sameCategory
            playable.isNotEmpty()  -> playable
            else                   -> listOf(current)
        }
    }

    /**
     * Pre-resolve the channels immediately above and below the current one on the surf dial, so
     * the next ◀/▶ flip starts from a warm cache (sub-second, no spinner). Mirrors the TV app's
     * focus/zap pre-loading. Best-effort and debounced inside [StreamPreResolver].
     */
    private fun preResolveNeighbors() {
        if (surfChannels.size < 2) return
        if (dataSaverEnabled) return  // no background network activity on metered connections
        val n = surfChannels.size
        val idx = surfChannels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
        streamPreResolver.preResolve(surfChannels[(idx + 1) % n])
        streamPreResolver.preResolve(surfChannels[(idx - 1 + n) % n])
    }

    /**
     * Pre-build and prepare ExoPlayer instances for the surf neighbours so the next
     * ◀/▶ flip is instant — the player is already at STATE_READY with decoders
     * initialised.  Best-effort; failures are silently ignored.
     */
    private fun preloadNeighborPlayers(app: Application, current: Channel) {
        if (surfChannels.size < 2) return
        if (_takenPlayer.value != null) return
        if (dataSaverEnabled) return  // no pre-buffering on metered connections
        val n = surfChannels.size
        val idx = surfChannels.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val next = surfChannels[(idx + 1) % n]
        val prev = surfChannels[(idx - 1 + n) % n]
        viewModelScope.launch {
            val typeNext = sourceSelector.selectDefaultVerifiedSource(next).type
            playbackPreloader.preload(app, next, typeNext)
        }
        viewModelScope.launch {
            val typePrev = sourceSelector.selectDefaultVerifiedSource(prev).type
            playbackPreloader.preload(app, prev, typePrev)
        }
    }

    /** Surf to the previous (−1) or next (+1) channel in-place, like flipping channels. */
    fun channelSurf(delta: Int) {
        if (surfChannels.size < 2) return
        val n = surfChannels.size
        val idx = surfChannels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
        val target = surfChannels[((idx + delta) % n + n) % n]
        if (target.id == currentChannelId) return
        // Seamless only when a video is currently on screen — the player composable stays mounted
        // and swaps to the new stream without a black/loading gap. Otherwise (cold start, error)
        // fall back to the normal load.
        loadChannel(target.id, seamless = canSwapSeamlessly())
    }

    /** Reset cross-source fallback tracking for a fresh channel/source selection. */
    private fun resetFallbackState() {
        triedSources.clear()
        sourceHasEverPlayed = false
        currentSourceConnected = false
        connectWatchdog?.cancel()
        connectWatchdog = null
        cancelValidationRecovery()
    }

    /** User picked a source for the current channel — switch (or reload) in place.
     *  The selection is marked as MANUAL in the session manager. If the source fails
     *  validation (doesn't start playing within the timeout), auto-recover to the
     *  default verified source. */
    fun selectSource(type: SourceType) {
        val channel = currentChannel ?: return
        val seamless = canSwapSeamlessly()
        resetFallbackState()
        sessionManager.markSourceSelected(type, PlaybackSessionManager.SourceSelectionMode.MANUAL)
        startValidationRecovery(channel)
        viewModelScope.launch {
            withContext(NonCancellable) { resolveAndPlay(channel, type, seamless) }
        }
    }

    /** True when a direct video is playing right now, so the next stream can be swapped in place. */
    private fun canSwapSeamlessly(): Boolean {
        val s = _uiState.value
        return s.error == null && s.mode == PlayerStreamMode.EXOPLAYER && s.streamUrl != null
    }

    /**
     * The channel's sources as user-facing options, one per provider (so STMIFY_FREE/PREMIUM
     * collapse into a single "World TV"), ordered best-first: curated → premium → scraped.
     */
    private fun buildSourceOptions(channel: Channel): List<SourceOption> {
        val enabled = sourcePreferences.enabled()
        val seen = LinkedHashSet<com.streamverse.core.data.SourceProvider>()
        val ranked = sourceResolutionEngine.rankSources(channel)
        val options = mutableListOf<SourceOption>()
        for (type in ranked) {
            val provider = com.streamverse.core.data.SourceProvider.forType(type)
            if (channel.sources.containsKey(type) && seen.add(provider) && enabled[provider] != false) {
                options.add(SourceOption(type, provider.displayName))
            }
        }
        return options
    }

    /** Resolve one source's stream(s) and start playback, marking it as the selected source.
     *  When [seamless], the current video keeps playing during resolve (no isLoading teardown) so
     *  the player composable can swap gaplessly. */
    private suspend fun resolveAndPlay(channel: Channel, type: SourceType, seamless: Boolean = false) {
        _uiState.value = if (seamless) {
            _uiState.value.copy(error = null, selectedSource = type)
        } else {
            _uiState.value.copy(isLoading = true, error = null, selectedSource = type)
        }
        triedSources.add(type)
        val info = channel.sources[type]
        if (info == null) {
            if (!advanceToNextSource()) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Source unavailable")
            }
            return
        }
        // Use pre-resolved cache when available (populated by home-screen on-focus pre-warming).
        // On a cache hit the network round-trip is skipped entirely → instant playback start.
        val cached = streamPreResolver.getCached(channel.id, type)
        val resolved = (cached ?: streamResolver.resolveAll(info)).toMutableList()
        // World TV sources can also fall back to their web player page.
        if (type == SourceType.WORLD_TV || type == SourceType.STMIFY_FREE || type == SourceType.STMIFY_PREMIUM) {
            val slug = info.referenceId.lowercase().replace("_", "-")
            resolved.add(StreamInfo("https://cdn.stmify.com/primevideo/live-tv/$slug", requiresBrowser = true))
            resolved.add(StreamInfo("https://stmify.com/live-tv/${info.referenceId}/", requiresBrowser = true))
        }
        val direct = resolved.filter { !it.requiresBrowser && !it.forceWebView }
        val browser = resolved.filter { it.requiresBrowser }
        val webView = resolved.filter { it.forceWebView }
        val urls = (direct + browser + webView).distinct().toMutableList()
        if (urls.isEmpty()) {
            if (!advanceToNextSource()) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "This source is unavailable right now")
            }
            return
        }
        pendingUrls = urls
        startConnectWatchdog()
        playNextUrl()
    }

    /**
     * Advance to the next untried source in the chain — safe failover.
     * Uses [SourceSelector.selectForFailover] which prefers verified-playable sources.
     * Only falls back to unverified sources when no verified source remains.
     * Returns false if no sources remain → channel resolves to no-signal static.
     */
    private suspend fun advanceToNextSource(): Boolean {
        val ch = currentChannel ?: return false
        val failing = _uiState.value.selectedSource
        if (failing != null) {
            sourceHealth.recordFailure(ch.id, failing)
            channelHealthEngine.recordPlaybackFailure(ch.id, failing, "advanceToNextSource")
        }
        val failoverSelection = sourceSelector.selectForFailover(ch, failing ?: SourceType.VERIFIED, triedSources)
        if (failoverSelection == null) return false
        triedSources.add(failoverSelection.type)
        sessionManager.markSourceSelected(failoverSelection.type, PlaybackSessionManager.SourceSelectionMode.FAILOVER)
        if (failing != null) {
            sessionManager.recordFailover(failing, failoverSelection.type, "playback_failure")
        }
        android.util.Log.d("PlayerViewModel", "advanceToNextSource: safe failover to ${failoverSelection.type} confidence=${failoverSelection.confidence}")
        resolveAndPlay(ch, failoverSelection.type, seamless = canSwapSeamlessly())
        return true
    }

    /**
     * Start the connect watchdog: if the source hasn't started playing within
     * [CONNECT_TIMEOUT_MS], treat it as dead and advance to the next source.
     */
    private fun startConnectWatchdog() {
        connectWatchdog?.cancel()
        currentSourceConnected = false
        connectWatchdog = viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!currentSourceConnected) {
                android.util.Log.d("PlayerViewModel", "connectWatchdog: source timed out, advancing")
                withContext(NonCancellable) {
                    if (pendingUrls.isNotEmpty()) {
                        // Still have pending URLs for this source — try the next one
                        playNextUrl()
                    } else if (!advanceToNextSource()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "All sources failed",
                        )
                    }
                }
            }
        }
    }

    private fun playNextUrl() {
        if (pendingUrls.isEmpty()) {
            // No more URLs for this source — advance to the next source in the chain.
            // The coroutine context is already NonCancellable when called from loadChannel,
            // but the watchdog also calls this from viewModelScope.launch.
            viewModelScope.launch {
                if (!advanceToNextSource()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "All sources failed",
                    )
                }
            }
            return
        }
        // removeAt(0), not removeFirst(): removeFirst() compiles to Java 21's
        // SequencedCollection.removeFirst(), which crashes on Android < 35 (most Fire TVs).
        val info = pendingUrls.removeAt(0)
        if (info.forceWebView) {
            android.util.Log.d("PlayerViewModel", "playNextUrl: WEBVIEW ${info.url} remaining=${pendingUrls.size}")
            // WebView playback is terminal — we can't observe ExoPlayer "isPlaying" here, so stop the
            // connect watchdog from advancing away from a perfectly good in-WebView channel.
            currentSourceConnected = true
            sourceHasEverPlayed = true
            connectWatchdog?.cancel()
            connectWatchdog = null
            _uiState.value = _uiState.value.copy(
                mode = PlayerStreamMode.WEBVIEW,
                streamUrl = info.url,
                streamReferer = null,
                extractUrl = null,
                drmKeyId = null,
                drmKey = null,
                drmLicenseUrl = null,
                isLoading = false,
                error = null,
                customTabUrl = null,
                firefoxUrl = null,
            )
            return
        }
        if (info.requiresBrowser) {
            // Force in-app playback: instead of showing a WebView, load the page in a
            // hidden WebView and extract the real .m3u8/.mpd, then play it in ExoPlayer.
            android.util.Log.d("PlayerViewModel", "playNextUrl: EXTRACTING from ${info.url} remaining=${pendingUrls.size}")
            _uiState.value = _uiState.value.copy(
                mode = PlayerStreamMode.EXTRACTING,
                extractUrl = info.url,
                streamUrl = null,
                streamReferer = null,
                drmKeyId = null,
                drmKey = null,
                drmLicenseUrl = null,
                isLoading = false,
                error = null,
                customTabUrl = null,
                firefoxUrl = null,
            )
            return
        }
        android.util.Log.d("PlayerViewModel", "playNextUrl: url=${info.url} mode=EXOPLAYER hasDrm=${info.hasDrm} remaining=${pendingUrls.size}")
        _uiState.value = _uiState.value.copy(
            streamUrl = info.url,
            streamReferer = null,
            streamHeaders = info.headers,
            extractUrl = null,
            drmKeyId = info.drmKeyId,
            drmKey = info.drmKey,
            drmLicenseUrl = info.drmLicenseUrl,
            isLoading = false,
            mode = PlayerStreamMode.EXOPLAYER,
            error = null,
            customTabUrl = null,
            firefoxUrl = null,
        )
    }

    /** Hidden extractor found a direct stream URL — switch to ExoPlayer. */
    fun onStreamExtracted(streamUrl: String, referer: String?) {
        android.util.Log.d("PlayerViewModel", "onStreamExtracted: url=$streamUrl referer=$referer")
        _uiState.value = _uiState.value.copy(
            streamUrl = streamUrl,
            streamReferer = referer,
            extractUrl = null,
            drmLicenseUrl = null,
            mode = PlayerStreamMode.EXOPLAYER,
            isLoading = false,
            error = null,
        )
    }

    /** Extraction failed/timed out — try the next URL or source, else surface static. */
    fun onExtractionFailed() {
        android.util.Log.d("PlayerViewModel", "onExtractionFailed remaining=${pendingUrls.size}")
        _uiState.value = _uiState.value.copy(extractUrl = null)
        connectWatchdog?.cancel()
        connectWatchdog = null
        if (pendingUrls.isNotEmpty()) {
            startConnectWatchdog()
            playNextUrl()
        } else {
            viewModelScope.launch {
                if (!advanceToNextSource()) {
                    _uiState.value = _uiState.value.copy(
                        error = "No playable stream found",
                        mode = PlayerStreamMode.EXOPLAYER,
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun onPlaybackStateChanged(isPlaying: Boolean) {
        _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
        if (isPlaying) {
            sourceHasEverPlayed = true
            currentSourceConnected = true
            connectWatchdog?.cancel()
            connectWatchdog = null
            // Complete validation in session manager — source is confirmed working.
            sessionManager.markValidationComplete(success = true)
            cancelValidationRecovery()
            // Remember this source as the known-good default for this channel next time.
            val id = currentChannelId
            val type = _uiState.value.selectedSource
            if (id != null && type != null) {
                sourceHealth.recordSuccess(id, type)
                channelHealthEngine.recordPlaybackSuccess(id, type)
            }
            // Recalculate default source (health may have changed), but never interrupt
            // a working manual selection.
            val channel = currentChannel
            if (channel != null) {
                val updated = sourceSelector.recalculateDefault(
                    channel,
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

    fun onPlayerError(errorMessage: String) {
        android.util.Log.d("PlayerViewModel", "onPlayerError: $errorMessage")
        connectWatchdog?.cancel()
        connectWatchdog = null
        // Mark validation as failed in session manager.
        sessionManager.markValidationComplete(success = false)
        cancelValidationRecovery()
        // If the source previously played successfully, don't auto-advance — the user decides
        // whether to switch sources or retry.
        if (sourceHasEverPlayed) {
            _uiState.value = _uiState.value.copy(error = errorMessage, isLoading = false)
            return
        }
        // If this was a manual selection that failed before playing, recover to default.
        if (sessionManager.getSelectionMode() == PlaybackSessionManager.SourceSelectionMode.MANUAL &&
            !sessionManager.isSourceValidated()) {
            val channel = currentChannel
            if (channel != null) {
                val defaultSource = sessionManager.recoverToDefault()
                if (defaultSource != null) {
                    android.util.Log.d("PlayerViewModel", "onPlayerError: manual source failed before play, recovering to $defaultSource")
                    sessionManager.recordFailover(
                        sessionManager.getCurrentPlaybackSource() ?: SourceType.VERIFIED,
                        defaultSource,
                        "manual_source_error",
                        wasUserSelection = true,
                    )
                    resetFallbackState()
                    viewModelScope.launch {
                        resolveAndPlay(channel, defaultSource, seamless = canSwapSeamlessly())
                        _uiState.value = _uiState.value.copy(error = "Source unavailable — switched back")
                    }
                    return
                }
            }
        }
        // Initial-connection phase: try the next source before surrendering.
        viewModelScope.launch {
            if (!advanceToNextSource()) {
                _uiState.value = _uiState.value.copy(error = errorMessage, isLoading = false)
            }
        }
    }

    fun onCustomTabLaunched() {
        _uiState.value = _uiState.value.copy(customTabUrl = null)
    }

    fun onFirefoxLaunched() {
        _uiState.value = _uiState.value.copy(firefoxUrl = null)
    }

    fun onWebViewLoadingChanged(loading: Boolean) {
        _uiState.value = _uiState.value.copy(webViewLoading = loading)
    }

    fun onWebViewTitleChanged(title: String) {
        _uiState.value = _uiState.value.copy(webViewTitle = title)
    }

    fun retry() {
        currentChannelId?.let { loadChannel(it) }
    }
}
