package com.streamverse.core.util

import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Ultra-low-latency ExoPlayer [DefaultLoadControl], tuned for **sub‑200 ms channel zaps** AND
 * resilience on poor connectivity.
 *
 * When [dataSaver] is `true`, all buffers are minimised — the user trades dropout coverage for
 * zero pre‑buffered waste on metered / capped connections.
 *
 * Setting                                Normal      Data‑Saver   Why
 * ────────────────────────────────────── ───────     ──────────   ─────────────────────────────────
 * bufferForPlaybackMs                      250         150        first frame in <200 ms (live)
 * bufferForPlaybackAfterRebuffer          1 000         500        quick recovery after glitch
 * minBufferMs (resume re-fill at)         8 000       3 000        floor low enough to fill fast
 * maxBufferMs (bank ahead up to)         30 000       8 000        dropouts up to 30 s covered
 *
 * Critical for live TV: **no size-based threshold** (`setTargetBufferBytes(-1)`) so ExoPlayer
 * never stalls because the byte budget is exhausted while time budget remains — a common source
 * of "stuck on loading" with HLS live streams.
 */
object StreamLoadControl {
    fun build(dataSaver: Boolean = false): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            if (dataSaver) 3_000 else 8_000,
            if (dataSaver) 8_000 else 30_000,
            if (dataSaver) 150 else 250,
            if (dataSaver) 500 else 1_000,
        )
        .setTargetBufferBytes(-1)
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(
            if (dataSaver) 5_000 else 15_000,
            true,
        )
        .build()
}
