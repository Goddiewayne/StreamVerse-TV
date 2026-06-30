package com.streamverse.core.data.remote.youtube

data class YouTubeTvEntry(
    val referenceId: String,
    val displayName: String,
    val channelId: String,
    val category: String,
    val language: String = "en",
    val country: String = "",
)

/**
 * Profile of a live YouTube TV channel resolved to a playable URL.
 */
data class YouTubeTvChannel(
    val referenceId: String,
    val displayName: String,
    val channelId: String,
    val category: String,
    val language: String,
    val country: String,
    val liveUrl: String,
)
