package com.streamverse.core.util

import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Live-TV–optimised ExoPlayer [DefaultLoadControl], tuned for **fast start AND resilience on
 * poor connectivity**.
 *
 * When [dataSaver] is `true`, buffer durations are halved to reduce pre‑buffered video data —
 * ideal for users on metered / capped connections who want to minimise consumption per zap.
 *
 * Setting                                Normal      Data‑Saver   Why
 * ────────────────────────────────────── ───────     ──────────   ─────────────────────────────────
 * bufferForPlaybackMs                      500         250        near-instant first frame (even)
 * bufferForPlaybackAfterRebuffer         2 000       1 000       quick recovery, but settled
 * minBufferMs (resume re-fill at)       15 000       5 000       keep a healthy floor, but smaller
 * maxBufferMs (bank ahead up to)        60 000      15 000       ride out dropouts, but cap waste
 */
object StreamLoadControl {
    fun build(dataSaver: Boolean = false): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs                    = */ if (dataSaver) 5_000 else 15_000,
            /* maxBufferMs                    = */ if (dataSaver) 15_000 else 60_000,
            /* bufferForPlaybackMs            = */ if (dataSaver) 250 else 500,
            /* bufferForPlaybackAfterRebufferMs= */ if (dataSaver) 1_000 else 2_000,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(
            /* backBufferDurationMs = */ if (dataSaver) 10_000 else 30_000,
            /* retainBackBufferFromKeyframe = */ true,
        )
        .build()
}
