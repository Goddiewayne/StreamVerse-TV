package com.streamverse.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_channels")
data class FavoriteChannelEntity(
    @PrimaryKey val channelId: String,
    val displayName: String,
    val addedAt: Long = System.currentTimeMillis(),
)
