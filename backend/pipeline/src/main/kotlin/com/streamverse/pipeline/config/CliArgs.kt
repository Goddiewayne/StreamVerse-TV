package com.streamverse.pipeline.config

data class CliArgs(
    val stages: List<String> = emptyList(),
    val help: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): CliArgs {
            if (args.isNotEmpty() && (args[0] == "--help" || args[0] == "-h")) return CliArgs(help = true)
            return CliArgs(stages = args.toList())
        }
    }

    fun usage(): String = """
Usage: java -jar pipeline.jar [stages...]

Stages (run in order if specified, else all):
  ingest       - Fetch raw channel data from all sources
  probe        - Validate stream health with bounded concurrency
  health       - Compute health scores
  canonicalize - Deduplicate and normalize channels
  rank         - Rank playback sources
  generate     - Build catalogue artifacts
  publish      - Publish to GitHub

If no stages specified, runs full pipeline.

Environment variables:
  GH_PAT                   GitHub personal access token
  DATA_BASE_URL            Base URL for GitHub Pages data
  MAX_CONCURRENT_PROBES    Max parallel probe workers (default: 50)
  PROBE_TIMEOUT_MS         Per-probe timeout (default: 15000)
  DRY_RUN                  Skip git push if "true"
  SKIP_PROBE               Skip probing stage if "true"
  OUTPUT_DIR               Output directory (default: build/catalogue)
  STATE_DIR                State persistence directory (default: build/state)
""".trimIndent()
}
