package com.streamverse.core.data.remote.premium.hunter

interface SourceHunter {
    val name: String
    suspend fun hunt(config: HunterConfig = HunterConfig()): List<DiscoveredSource>
}
