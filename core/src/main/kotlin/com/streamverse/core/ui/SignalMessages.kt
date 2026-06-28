package com.streamverse.core.ui

/**
 * Television-style on-screen-display copy for the no-signal screen.
 *
 * The player surfaces all manner of technical failures — HTTP 403/404/500, decoder errors,
 * timeouts, DNS failures, geo-blocks, expired tokens, "All sources failed" — but a real TV never
 * shows a stack trace. [forError] maps any of those into a calm, branded OSD line so the user
 * reads "the signal dropped", never "the app broke".
 */
data class SignalMessage(
    /** Big line, e.g. "No Signal". */
    val headline: String,
    /** Quiet supporting line, e.g. "This channel is temporarily unavailable." */
    val detail: String,
)

object SignalMessages {

    val NO_SIGNAL = SignalMessage("No Signal", "This channel is temporarily unavailable.")
    private val SIGNAL_LOST = SignalMessage("Signal Lost", "Reconnecting…")
    private val UNABLE_TO_TUNE = SignalMessage("No Signal", "Unable to tune this channel.")
    private val OFF_AIR = SignalMessage("Off Air", "Broadcast currently unavailable.")
    private val NOT_FOUND = SignalMessage("No Signal", "This channel could not be found.")

    /**
     * Best-effort classification of a raw error/status string into TV-style copy. Matching is
     * keyword-based and intentionally forgiving — anything unrecognised falls back to [NO_SIGNAL],
     * which is always safe and never technical.
     */
    fun forError(raw: String?): SignalMessage {
        val s = raw?.lowercase().orEmpty()
        return when {
            s.isBlank() -> NO_SIGNAL
            "not found" in s || "404" in s -> NOT_FOUND
            "network" in s || "connection" in s || "timeout" in s || "timed out" in s ||
                "dns" in s || "unreachable" in s || "offline" in s -> SIGNAL_LOST
            "geo" in s || "region" in s || "403" in s || "forbidden" in s ||
                "expired" in s || "401" in s -> OFF_AIR
            "format" in s || "codec" in s || "decoder" in s || "unsupported" in s ||
                "parse" in s || "drm" in s -> UNABLE_TO_TUNE
            "no playable" in s || "all sources" in s || "unavailable" in s ||
                "no stream" in s -> NO_SIGNAL
            else -> NO_SIGNAL
        }
    }
}
