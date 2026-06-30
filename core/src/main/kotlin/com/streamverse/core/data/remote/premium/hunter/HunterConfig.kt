package com.streamverse.core.data.remote.premium.hunter

data class HunterConfig(
    val maxResults: Int = 10,
    val requestTimeoutMs: Long = 25_000L,
)
