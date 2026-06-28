package com.streamverse.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [FavoriteChannelEntity::class], version = 1, exportSchema = false)
abstract class StreamVerseDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
}
