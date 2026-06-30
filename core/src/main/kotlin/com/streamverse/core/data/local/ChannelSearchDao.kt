package com.streamverse.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChannelSearchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ChannelSearchFts>)

    @Query("DELETE FROM channel_fts")
    suspend fun clearAll()

    @Query("SELECT channel_id FROM channel_fts WHERE channel_fts MATCH :query")
    suspend fun searchIds(query: String): List<String>
}
