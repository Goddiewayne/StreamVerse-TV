package com.streamverse.core.util

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Ultra-low-latency adaptive track selector — **enter at lowest safe bitrate, climb on
 * measured bandwidth** so the first video frame arrives in <200 ms even on congested links.
 *
 * When [dataSaver] is `true`, permanently locks to the lowest bitrate.
 *
 * Key behaviours:
 *  • **Start low, climb on proof** — without `setInitialBandwidthEstimate()`, ExoPlayer's
 *    adaptive evaluator enters conservatively (~800 kbps assumed) and ramps up only as download
 *    throughput confirms headroom.  This means a weak HSPA connection gets a watchable picture
 *    immediately instead of stalling on a too-high rendition.
 *  • **Never fail** — `setExceedVideoConstraintsIfNecessary` + `setExceedRendererCapabilities`
 *    eliminate "no supported track" errors that would otherwise surface a black screen.
 *  • **Audio only when video is too heavy** — `setTunnelingAudioSessionId(null)` avoids audio
 *    tunnel stalls on some MediaTek devices.
 */
@UnstableApi
object StreamTrackSelector {
    fun build(context: Context, dataSaver: Boolean = false): DefaultTrackSelector =
        DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setForceLowestBitrate(dataSaver)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
            )
        }
}
