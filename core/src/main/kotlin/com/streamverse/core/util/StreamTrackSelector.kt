package com.streamverse.core.util

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Adaptive track selector tuned for poor connectivity and a near-zero failure rate.
 *
 * Behaviour:
 *  • **Start safe, climb when able** — adaptive selection begins conservatively and ramps the
 *    bitrate up only as measured bandwidth proves it can sustain it, so a weak link gets a
 *    watchable picture immediately instead of stalling on a too-high rendition.
 *  • **Always play *something*** — `setExceedVideoConstraintsIfNecessary` /
 *    `setExceedRendererCapabilitiesIfNecessary` guarantee a renderable track is chosen even when
 *    every rendition technically exceeds the device/constraints, eliminating "no supported track"
 *    playback failures.
 */
@UnstableApi
object StreamTrackSelector {
    fun build(context: Context): DefaultTrackSelector = DefaultTrackSelector(context).apply {
        setParameters(
            buildUponParameters()
                .setForceLowestBitrate(false)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedAudioConstraintsIfNecessary(true)
        )
    }
}
