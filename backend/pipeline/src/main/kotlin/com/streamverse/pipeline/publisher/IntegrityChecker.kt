package com.streamverse.pipeline.publisher

import com.streamverse.pipeline.model.CatalogueArtifact
import com.streamverse.pipeline.telemetry.StructuredLogger
import java.io.File
import java.security.MessageDigest

class IntegrityChecker(
    private val logger: StructuredLogger,
) {
    data class IntegrityResult(
        val allValid: Boolean,
        val artifactsChecked: Int,
        val mismatched: List<ArtifactMismatch>,
    )

    data class ArtifactMismatch(
        val artifact: String,
        val expectedSha256: String,
        val actualSha256: String,
    )

    fun verify(outputDir: File, artifacts: List<CatalogueArtifact>): IntegrityResult {
        val mismatched = mutableListOf<ArtifactMismatch>()

        for (artifact in artifacts) {
            val file = File(outputDir, artifact.relativePath)
            if (!file.exists()) {
                mismatched.add(ArtifactMismatch(
                    artifact = artifact.relativePath,
                    expectedSha256 = artifact.checksumSha256,
                    actualSha256 = "FILE_NOT_FOUND",
                ))
                logger.warn("IntegrityChecker", "Missing artifact: ${artifact.relativePath}")
                continue
            }

            val content = file.readBytes()
            val actualSha256 = MessageDigest.getInstance("SHA-256")
                .digest(content).joinToString("") { "%02x".format(it) }

            if (actualSha256 != artifact.checksumSha256) {
                mismatched.add(ArtifactMismatch(
                    artifact = artifact.relativePath,
                    expectedSha256 = artifact.checksumSha256,
                    actualSha256 = actualSha256,
                ))
                logger.warn("IntegrityChecker", "Checksum mismatch: ${artifact.relativePath}")
            }
        }

        val result = IntegrityResult(
            allValid = mismatched.isEmpty(),
            artifactsChecked = artifacts.size,
            mismatched = mismatched,
        )

        if (result.allValid) {
            logger.info("IntegrityChecker", "All ${artifacts.size} artifacts verified")
        } else {
            logger.error("IntegrityChecker",
                "${mismatched.size}/${artifacts.size} artifacts failed integrity check")
        }

        return result
    }
}
