package com.streamverse.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FavoriteChannelEntity::class, ChannelSearchFts::class],
    version = 6, exportSchema = false,
)
abstract class StreamVerseDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun channelSearchDao(): ChannelSearchDao
}
