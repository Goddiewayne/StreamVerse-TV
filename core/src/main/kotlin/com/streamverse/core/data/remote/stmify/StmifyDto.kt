package com.streamverse.core.data.remote.stmify

import com.google.gson.annotations.SerializedName

data class StmifyChannelDto(
    val id: Int,
    val slug: String,
    val title: TitleDto?,
    val content: ContentDto?,
    val excerpt: ExcerptDto?,
    @SerializedName("featured_media") val featuredMedia: Int?,
    @SerializedName("_embedded") val embedded: StmifyEmbedded?,
    @SerializedName("meta") val meta: Map<String, String>?,
)

data class TitleDto(
    val rendered: String,
)

data class ContentDto(
    val rendered: String,
    val protected: Boolean = false,
)

data class ExcerptDto(
    val rendered: String,
)

data class StmifyEmbedded(
    @SerializedName("wp:featuredmedia") val featuredMedia: List<StmifyMediaDto>?,
    @SerializedName("wp:term") val terms: List<List<StmifyTermDto>>?,
)

data class StmifyMediaDto(
    val id: Int,
    @SerializedName("source_url") val sourceUrl: String?,
    @SerializedName("media_details") val mediaDetails: StmifyMediaDetails?,
)

data class StmifyMediaDetails(
    val width: Int?,
    val height: Int?,
    val sizes: Map<String, StmifyMediaSize>?,
)

data class StmifyMediaSize(
    @SerializedName("source_url") val sourceUrl: String,
)

data class StmifyTermDto(
    val id: Int,
    val name: String,
    val slug: String,
)
