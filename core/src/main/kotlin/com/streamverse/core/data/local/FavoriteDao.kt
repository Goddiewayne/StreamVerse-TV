package com.streamverse.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT channelId FROM favorite_channels ORDER BY addedAt DESC")
    fun getAllFavoriteIds(): Flow<List<String>>

    @Query("SELECT * FROM favorite_channels ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(entity: FavoriteChannelEntity)

    @Query("DELETE FROM favorite_channels WHERE channelId = :channelId")
    suspend fun removeFavorite(channelId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_channels WHERE channelId = :channelId)")
    fun isFavorite(channelId: String): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM favorite_channels")
    fun getFavoriteCount(): Flow<Int>
}
