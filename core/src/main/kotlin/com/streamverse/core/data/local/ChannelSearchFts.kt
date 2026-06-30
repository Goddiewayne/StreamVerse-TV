package com.streamverse.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

@Entity(tableName = "channel_fts")
@Fts4
data class ChannelSearchFts(
    @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val aliases: String,
    val category: String,
    val language: String,
    val country: String,
    val description: String,
)
