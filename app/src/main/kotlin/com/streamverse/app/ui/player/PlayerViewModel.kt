package com.streamverse.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamverse.core.data.SourceHealthPreferences
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.WatchHistoryPreferences
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.util.StreamInfo
import com.streamverse.core.util.StreamPreResolver
import com.streamverse.core.util.StreamResolver
import com.streamverse.core.util.SourceResolutionEngine
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val repository: ChannelRepository,
    private val streamResolver: StreamResolver,
    private val streamPreResolver: StreamPreResolver,
    private val watchHistory: WatchHistoryPreferences,
    private val sourceHealth: SourceHealthPreferences,
    private val sourcePreferences: SourcePreferences,
    private val sourceResolutionEngine: SourceResolutionEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var pendingUrls: MutableList<StreamInfo> = mutableListOf()
    private var currentChannelId: String? = null
    @Volatile private var currentChannel: Channel? = null

    // Channel-surf list: category-mates the viewer can flip through with ◀ Prev / Next ▶.
    private var surfChannels: List<Channel> = emptyList()

    // Smart cross-source fallback: the ordered list of source types to try for the current
    // channel and how far down it we are. If a source can't start, we advance to the next; only
    // when every source has failed does the channel resolve to the no-signal static.
    private var sourceChain: List<SourceType> = emptyList()
    private var sourceChainPos: Int = 0
    // Every source that has been attempted (including the currently playing one). Reset on
    // explicit channel/source switch. Used to skip already-tried sources when advancing.
    private val triedSources = mutableSetOf<SourceType>()
    // Watchdog: if a source URL doesn't start playing within the timeout, we advance.
    private var connectWatchdog: kotlinx.coroutines.Job? = null
    @Volatile private var currentSourceConnected = false
    // Once the source plays its first frame, auto-source-fallback is disabled — only the user
    // can switch sources. A mid-playback error shows the error/static, never auto-advances.
    @Volatile private var sourceHasEverPlayed = false

    private companion object {
        // Default play order when a channel has multiple sources: curated/verified first, then
        // premium, then the scraped open sources.
        val SOURCE_PRIORITY = listOf(
            SourceType.VERIFIED, SourceType.INDEPENDENT,
            SourceType.BROADCASTER,
            SourceType.SPORTS_EVENTS, SourceType.DLHD,
            SourceType.WORLD_TV, SourceType.STMIFY_FREE, SourceType.STMIFY_PREMIUM,
            SourceType.IPTV, SourceType.FREE_TV, SourceType.FAST_TV,
            SourceType.FREE_CHANNEL,
            SourceType.PREMIUM,
            SourceType.RADIO,
        )

        // How long a source gets to actually start playing before fallback gives up on it. Covers a
        // dead URL that hangs or a source stuck retrying — not just hard errors.
        // Increased from 10s to 25s to give slow CDNs time to respond and prevent premature
        // source switching when the stream starts playing but the watchdog hasn't been cancelled.
        const val CONNECT_TIMEOUT_MS = 25_000L
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
        loadChannel(id, seamless = canSwapSeamlessly())
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
        currentChannel = null
        currentChannelId = null
        _uiState.value = PlayerUiState(active = false, isLoading = false)
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
                        repository.load()
                        channel = repository.getChannelById(id)
                    }
                    if (channel == null) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Channel not found")
                        return@withContext
                    }

                    currentChannel = channel
                    currentChannelId = id
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
                    // Initialise the smart fallback chain
                    sourceChain = options.map { it.type }
                    sourceChainPos = 0
                    currentSourceConnected = false
                    connectWatchdog?.cancel()
                    connectWatchdog = null
                    // Smart default: open the source that last actually PLAYED this channel (avoids
                    // "dead on arrival" launches). Fall back to top-priority when there's no memory.
                    // Any failure still triggers the watchdog's cross-source failover below.
                    val preferredProvider = sourceHealth.lastGoodSource(id)
                        ?.let { SourceProvider.forType(it) }
                    val startType = options.firstOrNull { SourceProvider.forType(it.type) == preferredProvider }
                        ?.type
                        ?: options.first().type
                    resolveAndPlay(channel, startType, seamless)
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
        val n = surfChannels.size
        val idx = surfChannels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
        streamPreResolver.preResolve(surfChannels[(idx + 1) % n])
        streamPreResolver.preResolve(surfChannels[(idx - 1 + n) % n])
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
        sourceChainPos = 0
        sourceHasEverPlayed = false
        currentSourceConnected = false
        connectWatchdog?.cancel()
        connectWatchdog = null
    }

    /** User picked a source for the current channel — switch (or reload) in place. */
    fun selectSource(type: SourceType) {
        val channel = currentChannel ?: return
        val seamless = canSwapSeamlessly()
        resetFallbackState()
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
        // Build the full source chain for this channel — all available sources in priority order
        // starting from the selected type, wrapping around so every source gets a fair try.
        sourceChain = buildFallbackChain(channel)
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
     * Build the ordered list of sources to try for fallback, in priority order, avoiding any
     * that have already been attempted in this session.
     */
    private fun buildFallbackChain(channel: Channel): List<SourceType> {
        val seen = mutableSetOf<com.streamverse.core.data.SourceProvider>()
        return SOURCE_PRIORITY.filter { type ->
            val provider = com.streamverse.core.data.SourceProvider.forType(type)
            channel.sources.containsKey(type) && seen.add(provider) && type !in triedSources
        }
    }

    /**
     * Advance to the next untried source in the chain. Returns false if no sources remain.
     * When exhausted, the channel resolves to no-signal static.
     */
    private suspend fun advanceToNextSource(): Boolean {
        val ch = currentChannel ?: return false
        // The current source is being abandoned — forget it as this channel's preferred default so
        // we don't keep opening a source that no longer works.
        val failing = _uiState.value.selectedSource
        if (failing != null) sourceHealth.recordFailure(ch.id, failing)
        // Rebuild chain excluding already-tried sources
        sourceChain = buildFallbackChain(ch)
        if (sourceChain.isEmpty()) return false
        sourceChainPos = 0
        val nextType = sourceChain[0]
        android.util.Log.d("PlayerViewModel", "advanceToNextSource: falling back to $nextType")
        resolveAndPlay(ch, nextType, seamless = canSwapSeamlessly())
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
            // Remember this source as the known-good default for this channel next time.
            val id = currentChannelId
            val type = _uiState.value.selectedSource
            if (id != null && type != null) sourceHealth.recordSuccess(id, type)
        }
    }

    fun onPlayerError(errorMessage: String) {
        android.util.Log.d("PlayerViewModel", "onPlayerError: $errorMessage")
        connectWatchdog?.cancel()
        connectWatchdog = null
        // If the source previously played successfully, don't auto-advance — the user decides
        // whether to switch sources or retry.
        if (sourceHasEverPlayed) {
            _uiState.value = _uiState.value.copy(error = errorMessage, isLoading = false)
            return
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
