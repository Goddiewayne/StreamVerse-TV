package com.streamverse.core.util

import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Live-TV–optimised ExoPlayer [DefaultLoadControl], tuned for **fast start AND resilience on
 * poor connectivity**.
 *
 * Two competing goals, reconciled:
 *  1. Start instantly — `bufferForPlaybackMs = 500 ms` emits the first frame almost immediately
 *     (vs ExoPlayer's 2 500 ms default), so channels open in a blink even on a slow link.
 *  2. Never stall once playing — a deep `maxBufferMs = 60 s` reserve plus
 *     `prioritizeTimeOverSizeThresholds` lets the player race ahead and bank as much video as
 *     bandwidth allows, riding straight through dropouts and congestion.
 *
 *  Setting                          Default     StreamVerse   Why
 *  ──────────────────────────────── ────────    ───────────   ─────────────────────────────────
 *  bufferForPlaybackMs              2 500       500           near-instant first frame
 *  bufferForPlaybackAfterRebuffer   5 000       2 000         quick recovery, but settled
 *  minBufferMs (resume re-fill at)  50 000      15 000        keep a healthy floor of reserve
 *  maxBufferMs (bank ahead up to)   50 000      60 000        ride out long dropouts on weak data
 *  back-buffer                      0           30 000        instant seek-back / rejoin
 *
 * `prioritizeTimeOverSizeThresholds(true)` makes buffering decisions by playback duration rather
 * than byte count — the right call for low-bitrate streams where bytes are scarce but seconds
 * are what matter.
 */
object StreamLoadControl {
    fun build(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs                    = */ 15_000,
            /* maxBufferMs                    = */ 60_000,
            /* bufferForPlaybackMs            = */ 500,
            /* bufferForPlaybackAfterRebufferMs= */ 2_000,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(/* backBufferDurationMs = */ 30_000, /* retainBackBufferFromKeyframe = */ true)
        .build()
}
