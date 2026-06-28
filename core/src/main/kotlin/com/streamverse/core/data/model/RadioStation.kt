package com.streamverse.core.data.model

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val country: String?,
    val countryCode: String?,
    val language: String?,
    val tags: String?,
    val codec: String?,
    val bitrate: Int?,
    val clickCount: Int?,
)
