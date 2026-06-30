package com.streamverse.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "discovered_sources")
data class DiscoveredSourceEntity(
    @PrimaryKey val key: String,
    val url: String,
    val label: String,
    val hunterName: String,
    val discoveredAt: Long = System.currentTimeMillis(),
)
