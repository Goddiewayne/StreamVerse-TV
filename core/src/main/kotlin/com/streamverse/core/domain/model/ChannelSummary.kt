package com.streamverse.core.domain.model

data class ChannelSummary(
    val id: String,
    val displayName: String,
    val logoUrl: String?,
    val quality: Quality?,
    val category: String?,
    val country: String?,
    val language: String?,
    val sourceCount: Int,
    val isVerified: Boolean = false,
)

fun Channel.toSummary() = ChannelSummary(
    id = id,
    displayName = displayName,
    logoUrl = logoUrl,
    quality = quality,
    category = category,
    language = language,
    country = country,
    sourceCount = sources.size,
    isVerified = isVerified,
)
