package com.streamverse.pipeline.publisher

import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.model.CatalogueArtifact
import com.streamverse.pipeline.model.VersionManifest
import com.streamverse.pipeline.telemetry.MetricsCollector
import com.streamverse.pipeline.telemetry.StructuredLogger
import java.io.File

class GitHubPublisher(
    private val config: PipelineConfig,
    private val logger: StructuredLogger,
    private val metrics: MetricsCollector,
) {
    private val githubToken: String
        get() = config.githubToken.ifEmpty {
            System.getenv("GH_PAT") ?: System.getenv("STREAMVERSE_DATA_TOKEN") ?: ""
        }

    data class PublishResult(
        val published: Boolean,
        val commitHash: String? = null,
        val filesChanged: Int = 0,
        val durationMs: Long = 0,
        val artifacts: List<CatalogueArtifact> = emptyList(),
        val versionManifest: VersionManifest? = null,
    )

    fun publish(
        artifacts: List<CatalogueArtifact>,
        versionManifest: VersionManifest,
        dryRun: Boolean = config.dryRun,
    ): PublishResult {
        val startMs = System.currentTimeMillis()
        logger.info("GitHubPublisher", "Publishing ${artifacts.size} artifacts")

        if (githubToken.isEmpty()) {
            logger.warn("GitHubPublisher", "No GitHub token configured, skipping publish")
            return PublishResult(published = false)
        }

        val outputDir = File(config.outputDir)
        if (!outputDir.exists()) {
            logger.error("GitHubPublisher", "Output directory does not exist: $outputDir")
            return PublishResult(published = false)
        }

        val files = outputDir.listFiles() ?: emptyArray()
        logger.info("GitHubPublisher", "Found ${files.size} files in output directory")

        if (dryRun) {
            logger.info("GitHubPublisher", "DRY RUN: would publish ${files.size} files")
            return PublishResult(
                published = false,
                filesChanged = files.size,
                artifacts = artifacts,
                versionManifest = versionManifest,
            )
        }

        val publishDir = File(config.outputDir, "gh-pages")
        publishDir.mkdirs()

        try {
            val token = githubToken
            val remoteUrl = config.dataRepoRemote.replace("https://", "https://x-access-token:$token@")
            val branch = config.dataRepoBranch

            if (!File(publishDir, ".git").exists()) {
                runGit(publishDir, "init")
                runGit(publishDir, "config", "user.email", "bot@streamverse.tv")
                runGit(publishDir, "config", "user.name", "StreamVerse Bot")
                runGit(publishDir, "remote", "add", "origin", remoteUrl)
                runGit(publishDir, "checkout", "-b", branch)

                try {
                    runGit(publishDir, "fetch", "origin", branch, "--depth=1")
                    runGit(publishDir, "reset", "--soft", "origin/$branch")
                } catch (e: Exception) {
                    logger.info("GitHubPublisher", "Branch $branch does not exist on remote, starting fresh")
                }
            } else {
                runGit(publishDir, "remote", "set-url", "origin", remoteUrl)
                try {
                    runGit(publishDir, "fetch", "origin", branch, "--depth=1")
                    runGit(publishDir, "reset", "--soft", "origin/$branch")
                } catch (e: Exception) {
                    logger.warn("GitHubPublisher", "Could not fetch remote: ${e.message}")
                }
            }

            for (file in files) {
                if (file.isFile) {
                    val dest = File(publishDir, file.name)
                    file.copyTo(dest, overwrite = true)
                }
            }

            runGit(publishDir, "add", "-A", ".")
            val status = runGit(publishDir, "status", "--porcelain")
            val changedCount = status.lines().count { it.isNotBlank() }

            if (changedCount == 0) {
                logger.info("GitHubPublisher", "No changes to publish")
                return PublishResult(
                    published = true,
                    filesChanged = 0,
                    artifacts = artifacts,
                    versionManifest = versionManifest,
                    durationMs = System.currentTimeMillis() - startMs,
                )
            }

            val message = "chore: catalogue v${versionManifest.catalogueVersion} - " +
                "${versionManifest.channelCount} channels, ${versionManifest.liveChannelCount} live"
            runGit(publishDir, "commit", "-m", message)

            val pushOutput = runGit(publishDir, "push", "origin", branch)
            val commitHash = runGit(publishDir, "rev-parse", "HEAD").trim()

            val elapsed = System.currentTimeMillis() - startMs
            logger.info("GitHubPublisher",
                "Published $changedCount files to $branch (commit: $commitHash) in ${elapsed}ms")

            metrics.gauge("publish.files_changed", changedCount.toDouble())
            metrics.gauge("publish.duration_ms", elapsed.toDouble())
            metrics.increment("publish.total")

            return PublishResult(
                published = true,
                commitHash = commitHash,
                filesChanged = changedCount,
                durationMs = elapsed,
                artifacts = artifacts,
                versionManifest = versionManifest,
            )
        } catch (e: Exception) {
            logger.error("GitHubPublisher", "Publishing failed: ${e.message}", e)
            metrics.increment("publish.failure")
            return PublishResult(published = false, artifacts = artifacts, versionManifest = versionManifest)
        } finally {
            publishDir.deleteRecursively()
        }
    }

    private fun runGit(workingDir: File, vararg args: String): String {
        val gitCmd = if (System.getProperty("os.name").lowercase().contains("windows")) "git.exe" else "git"
        val cmd = mutableListOf(gitCmd)
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
            .directory(workingDir)
            .redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            logger.warn("GitHubPublisher", "git ${args.joinToString(" ")} exit $exitCode: ${output.take(200)}")
        }
        return output
    }
}
