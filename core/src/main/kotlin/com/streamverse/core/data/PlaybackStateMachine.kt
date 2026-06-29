package com.streamverse.core.data

/**
 * Production-grade playback state machine for TV and mobile.
 *
 * The TV static overlay is displayed **only** when the resolved state is
 * [State.UNAVAILABLE] — meaning every candidate source has been evaluated,
 * automatic retries have completed, and no playable stream exists.
 *
 * States and their valid transitions:
 *
 * IDLE ──load──► LOADING
 * LOADING ──url-resolved──► BUFFERING
 * LOADING ──fail──► FAILED
 * BUFFERING ──ready──► PLAYING
 * BUFFERING ──error──► RECOVERING  (if fallback sources remain)
 * BUFFERING ──error──► FAILED      (if no fallback)
 * PLAYING ──buffering──► BUFFERING (normal re-buffer, no static)
 * PLAYING ──error──► RECOVERING    (if source ever played, wait for user)
 * PLAYING ──error──► FAILED        (if source never played, auto-retry)
 * RECOVERING ──source-found──► BUFFERING
 * RECOVERING ──all-exhausted──► UNAVAILABLE
 * FAILED ──retry──► BUFFERING
 * FAILED ──all-exhausted──► UNAVAILABLE
 * SWITCHING_SOURCES ──source-selected──► LOADING
 * UNAVAILABLE ──retry──► LOADING
 * UNAVAILABLE ──source-change──► SWITCHING_SOURCES
 * Any ──stop──► IDLE
 * Any ──channel-change──► LOADING
 */
class PlaybackStateMachine {

    enum class State {
        IDLE,
        LOADING,
        BUFFERING,
        PLAYING,
        RECOVERING,
        SWITCHING_SOURCES,
        FAILED,
        UNAVAILABLE,
    }

    enum class Event {
        LOAD,
        URL_RESOLVED,
        BUFFERING_STARTED,
        PLAYBACK_READY,
        PLAYBACK_ERROR,
        SOURCE_FOUND,
        ALL_SOURCES_EXHAUSTED,
        RETRY,
        SOURCE_SWITCH,
        CHANNEL_CHANGE,
        STOP,
    }

    @Volatile private var _state: State = State.IDLE
    val state: State get() = _state

    /** True when a recoverable error has occurred but a fallback source is still pending. */
    @Volatile private var hasFallbackSource: Boolean = false

    /** True when the current source has ever reached STATE_READY (played at least one frame). */
    @Volatile private var hasEverPlayed: Boolean = false

    @Volatile private var retryCount: Int = 0
    private val maxRetries = 5

    /** Callbacks invoked on state transitions. */
    var onStateChanged: ((State) -> Unit)? = null

    fun reset() {
        _state = State.IDLE
        hasFallbackSource = false
        hasEverPlayed = false
        retryCount = 0
    }

    fun markHasEverPlayed() { hasEverPlayed = true }

    fun setHasFallbackSource(has: Boolean) { hasFallbackSource = has }

    fun retriesRemaining(): Int = maxRetries - retryCount

    fun transition(event: Event) {
        val prev = _state
        val next = computeNext(event) ?: return
        _state = next
        if (event == Event.PLAYBACK_ERROR && next == State.FAILED) retryCount++
        if (next == State.PLAYING) { hasEverPlayed = true; retryCount = 0 }
        if (next == State.IDLE) reset()
        if (prev != next) onStateChanged?.invoke(next)
    }

    /** Should the TV static overlay be visible right now? */
    val shouldShowStatic: Boolean get() = _state == State.UNAVAILABLE

    /** Should the progress spinner be visible right now? */
    val shouldShowSpinner: Boolean get() = _state in setOf(
        State.LOADING,
        State.BUFFERING,
        State.RECOVERING,
        State.SWITCHING_SOURCES,
        State.FAILED,
    )

    private fun computeNext(event: Event): State? = when (_state) {
        State.IDLE -> when (event) {
            Event.LOAD, Event.CHANNEL_CHANGE -> State.LOADING
            else -> null
        }
        State.LOADING -> when (event) {
            Event.URL_RESOLVED -> State.BUFFERING
            Event.PLAYBACK_ERROR -> {
                if (hasFallbackSource) State.RECOVERING
                else if (retryCount < maxRetries) State.FAILED
                else State.UNAVAILABLE
            }
            Event.ALL_SOURCES_EXHAUSTED -> State.UNAVAILABLE
            Event.STOP -> State.IDLE
            else -> null
        }
        State.BUFFERING -> when (event) {
            Event.PLAYBACK_READY -> State.PLAYING
            Event.PLAYBACK_ERROR -> {
                if (hasFallbackSource) State.RECOVERING
                else if (!hasEverPlayed && retryCount < maxRetries) State.FAILED
                else State.UNAVAILABLE
            }
            Event.ALL_SOURCES_EXHAUSTED -> State.UNAVAILABLE
            Event.SOURCE_FOUND -> State.BUFFERING
            Event.STOP -> State.IDLE
            else -> null
        }
        State.PLAYING -> when (event) {
            Event.BUFFERING_STARTED -> State.BUFFERING
            Event.PLAYBACK_ERROR -> {
                if (hasFallbackSource) State.RECOVERING
                else State.UNAVAILABLE
            }
            Event.CHANNEL_CHANGE -> State.LOADING
            Event.STOP -> State.IDLE
            else -> null
        }
        State.RECOVERING -> when (event) {
            Event.SOURCE_FOUND -> State.BUFFERING
            Event.ALL_SOURCES_EXHAUSTED -> State.UNAVAILABLE
            Event.PLAYBACK_READY -> State.PLAYING
            Event.LOAD,
            Event.CHANNEL_CHANGE -> State.LOADING
            Event.STOP -> State.IDLE
            else -> null
        }
        State.SWITCHING_SOURCES -> when (event) {
            Event.SOURCE_SWITCH -> State.LOADING
            Event.STOP -> State.IDLE
            else -> null
        }
        State.FAILED -> when (event) {
            Event.RETRY -> State.LOADING
            Event.ALL_SOURCES_EXHAUSTED -> State.UNAVAILABLE
            Event.SOURCE_SWITCH -> State.SWITCHING_SOURCES
            Event.STOP -> State.IDLE
            else -> null
        }
        State.UNAVAILABLE -> when (event) {
            Event.RETRY -> State.LOADING
            Event.SOURCE_SWITCH -> State.SWITCHING_SOURCES
            Event.CHANNEL_CHANGE -> State.LOADING
            Event.STOP -> State.IDLE
            else -> null
        }
    }
}
