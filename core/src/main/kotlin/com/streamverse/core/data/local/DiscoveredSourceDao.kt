package com.streamverse.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DiscoveredSourceDao {
    @Query("SELECT * FROM discovered_sources")
    suspend fun getAll(): List<DiscoveredSourceEntity>

    @Query("SELECT key FROM discovered_sources")
    suspend fun getAllKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DiscoveredSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DiscoveredSourceEntity>)

    @Query("DELETE FROM discovered_sources WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM discovered_sources")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM discovered_sources")
    suspend fun count(): Int
}
