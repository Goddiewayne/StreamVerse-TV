package com.streamverse.app.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges the Compose player screen and [com.streamverse.app.MainActivity] for Picture-in-Picture.
 *
 * The player screen flags itself [eligible] while a video (ExoPlayer) is actively on screen; the
 * Activity reads that flag in `onUserLeaveHint()` to decide whether to shrink into a PiP window
 * when the user presses Home. The Activity in turn publishes [inPip] so the player screen can strip
 * its chrome down to just the video while floating.
 */
object PipController {
    /** True while a fullscreen ExoPlayer video is on screen — the only state where PiP makes sense. */
    @Volatile var eligible: Boolean = false

    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    fun setInPip(value: Boolean) { _inPip.value = value }
}
