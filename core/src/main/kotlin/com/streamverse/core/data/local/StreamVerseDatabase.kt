package com.streamverse.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FavoriteChannelEntity::class, ChannelSearchFts::class, DiscoveredSourceEntity::class],
    version = 5, exportSchema = false,
)
abstract class StreamVerseDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun channelSearchDao(): ChannelSearchDao
    abstract fun discoveredSourceDao(): DiscoveredSourceDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `discovered_sources` (
                        `key` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `hunterName` TEXT NOT NULL,
                        `discoveredAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`key`)
                    )"""
                )
            }
        }
    }
}
