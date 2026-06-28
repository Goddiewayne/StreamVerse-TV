package com.streamverse.core.data.model

data class DlhdChannel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val category: String?,
)

data class DlhdSchedule(
    val date: String,
    val category: String,
    val time: String,
    val event: String,
    val channelIds: List<String>,
)
