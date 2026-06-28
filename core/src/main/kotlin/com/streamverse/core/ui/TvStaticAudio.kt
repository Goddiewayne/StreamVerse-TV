package com.streamverse.core.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.random.Random

/**
 * A very subtle, procedurally-generated analogue static hiss to accompany [TvStaticView].
 *
 * Deliberately optional and OFF by default — silence is the accessible default, and many users
 * watch with the room quiet. When enabled it is mixed extremely low and routed through the media
 * stream, so it respects the system volume and mute exactly like the channel audio it replaces.
 *
 * No audio asset is shipped: a short white-noise PCM buffer is synthesised once and looped, so the
 * footprint is a few KB of RAM and zero disk.
 */
class TvStaticAudio {

    private var track: AudioTrack? = null

    /** Mix level for the hiss (0f..1f). Intentionally faint so it is ambience, never a startle. */
    var volume: Float = 0.05f
        set(value) {
            field = value.coerceIn(0f, 1f)
            track?.setVolume(field)
        }

    fun start() {
        if (track != null) return
        runCatching {
            val sampleRate = 22_050
            // ~0.5 s of looped noise — long enough to avoid an obvious periodic "tick".
            val frames = sampleRate / 2
            val buffer = ShortArray(frames)
            val rng = Random(System.nanoTime())
            // Soft-band the noise a touch (one-pole low-pass) so it hisses rather than fizzes.
            var prev = 0
            for (i in buffer.indices) {
                val white = rng.nextInt(-9000, 9000)
                prev = (prev + (white - prev) / 3)
                buffer[i] = prev.toShort()
            }

            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            t.write(buffer, 0, buffer.size)
            t.setLoopPoints(0, buffer.size, -1) // loop forever
            t.setVolume(volume)
            t.play()
            track = t
        }
    }

    fun stop() {
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    companion object {
        /** True only when the media stream is actually audible — saves spinning up a silent track. */
        fun isMediaAudible(audioManager: AudioManager?): Boolean {
            audioManager ?: return false
            return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
        }
    }
}
