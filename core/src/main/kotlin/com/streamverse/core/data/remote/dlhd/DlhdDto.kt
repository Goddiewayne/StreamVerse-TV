package com.streamverse.core.data.remote.dlhd

import com.google.gson.annotations.SerializedName

data class DlhdChannelDto(
    @SerializedName("channel_name") val channelName: String,
    @SerializedName("channel_id") val channelId: String,
    @SerializedName("logo_url") val logoUrl: String?,
)

data class DlhdScheduleDto(
    @SerializedName("channel_name") val channelName: String?,
    @SerializedName("channel_id") val channelId: String?,
    @SerializedName("logo_url") val logoUrl: String?,
)

data class DlhdScheduleDayDto(
    val time: String,
    val event: String,
    val channels: List<DlhdScheduleDto>?,
    val channels2: List<DlhdScheduleDto>?,
)

data class DlhdApiResponse(
    val channels: List<DlhdChannelDto>?,
    val schedule: Map<String, Map<String, List<DlhdScheduleDayDto>>>?,
    val info: String?,
)
