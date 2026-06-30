package com.streamverse.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteChannelEntity::class, ChannelSearchFts::class],
    version = 4, exportSchema = false,
)
abstract class StreamVerseDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun channelSearchDao(): ChannelSearchDao
}
