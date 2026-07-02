package com.streamverse.pipeline.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.CanonicalChannel
import com.streamverse.pipeline.telemetry.StructuredLogger
import java.io.File

class StateStore(
    private val config: PipelineConfig,
    private val logger: StructuredLogger,
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val stateDir = File(config.stateDir)

    data class PipelineState(
        val lastCatalogueVersion: Int = -1,
        val lastSuccessfulRunMs: Long = 0,
        val lastChannelCount: Int = 0,
        val runCount: Int = 0,
        val stageTimings: Map<String, Long> = emptyMap(),
    )

    fun loadState(): PipelineState {
        stateDir.mkdirs()
        val file = File(stateDir, "pipeline_state.json")
        if (!file.exists()) return PipelineState()
        return try {
            val json = file.readText()
            gson.fromJson(json, PipelineState::class.java) ?: PipelineState()
        } catch (e: Exception) {
            logger.warn("StateStore", "Failed to load state: ${e.message}")
            PipelineState()
        }
    }

    fun saveState(state: PipelineState) {
        stateDir.mkdirs()
        val file = File(stateDir, "pipeline_state.json")
        try {
            file.writeText(gson.toJson(state))
        } catch (e: Exception) {
            logger.error("StateStore", "Failed to save state: ${e.message}")
        }
    }

    fun saveCatalogue(channels: List<CanonicalChannel>) {
        stateDir.mkdirs()
        val file = File(stateDir, "last_catalogue.json")
        try {
            file.writeText(gson.toJson(channels))
        } catch (e: Exception) {
            logger.error("StateStore", "Failed to save catalogue snapshot: ${e.message}")
        }
    }

    fun loadCatalogue(): List<CanonicalChannel> {
        val file = File(stateDir, "last_catalogue.json")
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val type = object : com.google.gson.reflect.TypeToken<List<CanonicalChannel>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            logger.warn("StateStore", "Failed to load catalogue snapshot: ${e.message}")
            emptyList()
        }
    }

    fun clear() {
        stateDir.deleteRecursively()
        stateDir.mkdirs()
        logger.info("StateStore", "State cleared")
    }
}
