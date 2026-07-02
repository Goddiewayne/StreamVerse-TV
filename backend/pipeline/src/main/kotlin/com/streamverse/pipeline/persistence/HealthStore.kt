package com.streamverse.pipeline.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.HealthRecord
import com.streamverse.pipeline.telemetry.StructuredLogger
import java.io.File

class HealthStore(
    private val config: PipelineConfig,
    private val logger: StructuredLogger,
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val stateDir = File(config.stateDir)

    fun loadAll(): Map<String, HealthRecord> {
        stateDir.mkdirs()
        val file = File(stateDir, "health_records.json")
        if (!file.exists()) return emptyMap()
        return try {
            val json = file.readText()
            val type = object : TypeToken<Map<String, HealthRecord>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            logger.warn("HealthStore", "Failed to load health records: ${e.message}")
            emptyMap()
        }
    }

    fun saveAll(records: Map<String, HealthRecord>) {
        stateDir.mkdirs()
        val file = File(stateDir, "health_records.json")
        try {
            file.writeText(gson.toJson(records))
        } catch (e: Exception) {
            logger.error("HealthStore", "Failed to save health records: ${e.message}")
        }
    }

    fun load(channelId: String, sourceTypeStr: String): HealthRecord? {
        return loadAll()["$channelId:$sourceTypeStr"]
    }
}
