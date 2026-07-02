package com.streamverse.pipeline

import com.streamverse.pipeline.config.CliArgs
import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.pipeline.PipelineOrchestrator
import com.streamverse.pipeline.telemetry.MetricsCollector
import com.streamverse.pipeline.telemetry.StructuredLogger

fun main(args: Array<String>) {
    val cliArgs = CliArgs.parse(args)
    if (cliArgs.help) {
        println(cliArgs.usage())
        kotlin.system.exitProcess(0)
    }

    val config = PipelineConfig()
    val logger = StructuredLogger.forName("PipelineApp")
    val metrics = MetricsCollector()

    logger.info("PipelineApp", "StreamVerse Pipeline v${config.javaClass.`package`?.implementationVersion ?: "2.0.0"}")
    logger.info("PipelineApp", "Output: ${config.outputDir}")
    logger.info("PipelineApp", "Max probes: ${config.maxConcurrentProbes}")
    logger.info("PipelineApp", "Workers: ${config.workers}")
    logger.info("PipelineApp", "Dry run: ${config.dryRun}")
    logger.info("PipelineApp", "Skip probe: ${config.skipProbe}")

    // Ensure output and state directories exist
    java.io.File(config.outputDir).mkdirs()
    java.io.File(config.stateDir).mkdirs()

    val orchestrator = PipelineOrchestrator(config, logger, metrics)
    val result = orchestrator.run(cliArgs.stages)

    // Print summary
    println()
    println("╔══════════════════════════════════════════════════════╗")
    println("║          StreamVerse Pipeline Report                ║")
    println("╠══════════════════════════════════════════════════════╣")
    println("║  Status:    ${if (result.success) "SUCCESS" else "FAILED"}${" ".repeat(36 - if (result.success) 7 else 6)}║")
    println("║  Duration:  ${result.totalDurationMs}ms${" ".repeat(34 - result.totalDurationMs.toString().length)}║")
    println("║  Stages:    ${result.stages.size}${" ".repeat(37 - result.stages.size.toString().length)}║")
    println("╠══════════════════════════════════════════════════════╣")
    println("║  Stage Results:                                     ║")

    for ((name, stageResult) in result.results) {
        val label = when (stageResult) {
            is com.streamverse.pipeline.pipeline.StageResult.Success -> "✓ $name"
            is com.streamverse.pipeline.pipeline.StageResult.Failure -> "✗ $name"
            is com.streamverse.pipeline.pipeline.StageResult.Skipped -> "- $name (skipped)"
        }
        println("║    ${label.padEnd(47, ' ')}║")
    }

    println("╠══════════════════════════════════════════════════════╣")
    println("║  Run #${result.state.runCount}${", channels: ${result.state.lastChannelCount}".padEnd(46 - result.state.runCount.toString().length)}║")
    println("╚══════════════════════════════════════════════════════╝")

    if (!result.success) {
        kotlin.system.exitProcess(1)
    }
}
