package com.streamverse.pipeline.publisher

import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.VersionManifest
import com.streamverse.pipeline.telemetry.StructuredLogger

class ReleaseManager(
    private val config: PipelineConfig,
    private val logger: StructuredLogger,
) {
    fun generateReleaseNotes(manifest: VersionManifest, previousManifest: VersionManifest?): String {
        val sb = StringBuilder()
        sb.appendLine("# StreamVerse Catalogue v${manifest.catalogueVersion}")
        sb.appendLine()
        sb.appendLine("**Generated:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'")
            .format(java.util.Date(manifest.generatedAtMs))}")
        sb.appendLine()
        sb.appendLine("## Summary")
        sb.appendLine("- **Total channels:** ${manifest.channelCount}")
        sb.appendLine("- **Live channels:** ${manifest.liveChannelCount}")
        sb.appendLine("- **Providers:** ${manifest.providerCount}")
        sb.appendLine("- **Total sources:** ${manifest.sourceCount}")
        sb.appendLine()

        if (previousManifest != null) {
            val added = manifest.channelCount - previousManifest.channelCount
            val removed = previousManifest.channelCount - manifest.channelCount
            sb.appendLine("## Changes from v${previousManifest.catalogueVersion}")
            sb.appendLine("- **Channels:** ${if (added >= 0) "+$added" else added}")
            sb.appendLine("- **Previous version:** ${previousManifest.channelCount} channels")
            sb.appendLine()
        }

        sb.appendLine("## Artifacts")
        for (artifact in manifest.artifacts) {
            val sizeKb = artifact.compressedBytes / 1024
            sb.appendLine("- `${artifact.relativePath}` (${sizeKb} KB, ${artifact.artifactType.name})")
        }
        sb.appendLine()
        sb.appendLine("## Integrity")
        sb.appendLine("- **Root hash:** `${manifest.integrity.rootHash}`")
        sb.appendLine("- **Signed:** ${manifest.integrity.signed}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("*StreamVerse Pipeline v${manifest.pipelineVersion}*")

        return sb.toString()
    }

    fun createReleaseTag(manifest: VersionManifest): String {
        return "catalogue-v${manifest.catalogueVersion}"
    }
}
