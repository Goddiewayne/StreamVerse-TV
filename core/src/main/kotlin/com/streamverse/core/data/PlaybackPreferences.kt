package com.streamverse.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class VideoResizeMode(val key: String, val displayName: String) {
    FIT("fit", "Fit"),
    FILL("fill", "Fill"),
    ZOOM("zoom", "Zoom"),
}

@Singleton
class PlaybackPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)

    // Defaults to true: live TV should never let the display sleep mid-stream unless the
    // user explicitly opts out in Settings.
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    // Defaults to true: when the screen turns off or the app is backgrounded, keep the audio
    // playing (video simply stops rendering). Turn off to pause playback whenever the player
    // leaves the foreground (saves mobile data / battery).
    var backgroundPlayback: Boolean
        get() = prefs.getBoolean("background_playback", true)
        set(value) = prefs.edit().putBoolean("background_playback", value).apply()

    var resizeMode: VideoResizeMode
        get() = try { VideoResizeMode.valueOf(prefs.getString("resize_mode", "FIT")!!.uppercase()) }
               catch (_: Exception) { VideoResizeMode.FIT }
        set(value) = prefs.edit().putString("resize_mode", value.key).apply()

    // ── No-signal TV static ──────────────────────────────────────────────────────────────
    // The analogue-snow screen shown when a stream can't start or drops. Tunable so it can be
    // calmed on weak hardware (or for accessibility) without touching playback code.

    /** "low" | "medium" | "high". "low" doubles as a low-power rendering mode. */
    var staticIntensity: String
        get() = prefs.getString("static_intensity", "medium") ?: "medium"
        set(value) = prefs.edit().putString("static_intensity", value).apply()

    /** Faint analogue hiss under the static. OFF by default — silence is the accessible default. */
    var staticAudio: Boolean
        get() = prefs.getBoolean("static_audio", false)
        set(value) = prefs.edit().putBoolean("static_audio", value).apply()

    /** A very brief snow burst when zapping channels — reinforces the "real TV" feel. */
    var staticChannelBurst: Boolean
        get() = prefs.getBoolean("static_channel_burst", true)
        set(value) = prefs.edit().putBoolean("static_channel_burst", value).apply()
}
