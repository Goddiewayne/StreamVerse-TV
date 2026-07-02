package com.streamverse.pipeline.telemetry

import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class MetricsExporter(
    private val metrics: MetricsCollector,
    private val logger: StructuredLogger,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val outputDir = File("logs")

    fun exportToFile(filename: String = "metrics.json") {
        outputDir.mkdirs()
        val file = File(outputDir, filename)
        val snapshot = metrics.snapshot()
        val json = gson.toJson(snapshot)
        file.writeText(json)
        logger.info("MetricsExporter", "Exported metrics to ${file.absolutePath}")
    }

    fun exportToLog() {
        metrics.report(logger)
    }
}
