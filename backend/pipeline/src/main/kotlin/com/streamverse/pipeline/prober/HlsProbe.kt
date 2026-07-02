package com.streamverse.pipeline.prober

import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.telemetry.StructuredLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class HlsProbe(
    private val config: PipelineConfig,
    private val logger: StructuredLogger,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.probeConnectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.probeReadTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(config.probeTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun probe(url: String): ProbeEngine.ProbeOutcome {
        val startMs = System.currentTimeMillis()
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "*/*")
                .build()

            val response = client.newCall(req).execute()
            val statusCode = response.code
            val redirectChain = buildRedirectChain(response)
            val contentType = response.header("Content-Type") ?: ""
            val bodyBytes = response.body?.bytes() ?: ByteArray(0)
            val elapsed = System.currentTimeMillis() - startMs

            if (statusCode !in 200..299) {
                return ProbeEngine.ProbeOutcome(
                    success = false, httpStatus = statusCode,
                    redirectChain = redirectChain, mimeType = contentType,
                    failureReason = "HTTP $statusCode",
                )
            }

            val body = bodyBytes.toString(Charsets.UTF_8)
            val lines = body.lines().map { it.trim() }.filter { it.isNotBlank() }

            if (lines.isEmpty()) {
                return ProbeEngine.ProbeOutcome(
                    success = false, httpStatus = statusCode,
                    mimeType = contentType, failureReason = "Empty manifest",
                )
            }

            val isMasterPlaylist = body.contains("#EXT-X-STREAM-INF", ignoreCase = true)
            val isMediaPlaylist = body.contains("#EXTINF", ignoreCase = true)
            val hasHeader = body.contains("#EXTM3U", ignoreCase = true)

            if (!hasHeader || (!isMasterPlaylist && !isMediaPlaylist)) {
                return ProbeEngine.ProbeOutcome(
                    success = false, httpStatus = statusCode,
                    mimeType = contentType, failureReason = "Invalid HLS format",
                    manifestAvailable = true,
                )
            }

            if (isMasterPlaylist) {
                val variantLines = body.lines().filter { it.contains("#EXT-X-STREAM-INF", ignoreCase = true) }
                if (variantLines.isEmpty()) {
                    return ProbeEngine.ProbeOutcome(
                        success = false, httpStatus = statusCode,
                        mimeType = contentType, failureReason = "No variants in master playlist",
                        manifestAvailable = true, playlistValid = true,
                    )
                }

                val bestVariantUrl = resolveVariantUrl(url, body)
                if (bestVariantUrl != null && bestVariantUrl.startsWith("http")) {
                    val variantResult = probeVariant(bestVariantUrl)
                    return ProbeEngine.ProbeOutcome(
                        success = variantResult.success,
                        manifestAvailable = true,
                        playlistValid = true,
                        segmentAccessible = variantResult.segmentAccessible,
                        playbackStartupMs = elapsed,
                        resolution = variantResult.resolution,
                        width = variantResult.width,
                        height = variantResult.height,
                        codec = variantResult.codec,
                        bitrate = variantResult.bitrate,
                        encrypted = variantResult.encrypted,
                        requiresAuth = variantResult.requiresAuth,
                        httpStatus = statusCode,
                        redirectChain = redirectChain,
                        mimeType = "application/vnd.apple.mpegurl",
                        failureReason = if (variantResult.success) null else "Variant probe failed",
                    )
                }
            }

            val segmentUrl = resolveSegmentUrl(url, body)
            val segmentOk = if (segmentUrl != null) probeSegment(segmentUrl) else false

            val resolution = parseResolution(body)
            val codec = parseCodec(body)
            val encrypted = body.contains("#EXT-X-KEY", ignoreCase = true)

            return ProbeEngine.ProbeOutcome(
                success = segmentOk,
                manifestAvailable = true,
                playlistValid = true,
                segmentAccessible = segmentOk,
                playbackStartupMs = elapsed,
                decoderCompatible = true,
                resolution = resolution,
                width = 0,
                height = 0,
                codec = codec,
                encrypted = encrypted,
                httpStatus = statusCode,
                redirectChain = redirectChain,
                mimeType = "application/vnd.apple.mpegurl",
                failureReason = if (segmentOk) null else "Segment unreachable",
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            return ProbeEngine.ProbeOutcome(
                success = false,
                httpStatus = -1,
                failureReason = e.message ?: e::class.simpleName ?: "Unknown",
            )
        }
    }

    private fun probeVariant(url: String): ProbeEngine.ProbeOutcome {
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return ProbeEngine.ProbeOutcome(success = false, httpStatus = resp.code,
                    failureReason = "Variant HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: ""
            val segmentUrl = resolveSegmentUrl(url, body)
            val segmentOk = if (segmentUrl != null) probeSegment(segmentUrl) else false
            val resolution = parseResolution(body)
            val codec = parseCodec(body)
            val encrypted = body.contains("#EXT-X-KEY", ignoreCase = true)
            return ProbeEngine.ProbeOutcome(
                success = segmentOk, manifestAvailable = true, playlistValid = true,
                segmentAccessible = segmentOk, playbackStartupMs = System.currentTimeMillis() - start,
                resolution = resolution, codec = codec, encrypted = encrypted,
                httpStatus = resp.code,
                failureReason = if (segmentOk) null else "Variant segment unreachable",
            )
        } catch (e: Exception) {
            return ProbeEngine.ProbeOutcome(success = false,
                failureReason = e.message ?: "Variant probe error")
        }
    }

    private fun probeSegment(url: String): Boolean {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Range", "bytes=0-65535")
                .build()
            val resp = client.newCall(req).execute()
            resp.isSuccessful && (resp.body?.bytes()?.size ?: 0) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveVariantUrl(manifestUrl: String, body: String): String? {
        val lines = body.lines()
        var resolveNext = false
        var bestBandwidth = -1L
        var bestUrl: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(trimmed)?.groupValues?.get(1)?.toLongOrNull() ?: -1
                if (bw > bestBandwidth) {
                    bestBandwidth = bw
                    resolveNext = true
                }
            } else if (resolveNext && trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                bestUrl = resolveRelativeUrl(manifestUrl, trimmed)
                resolveNext = false
            }
        }
        return bestUrl
    }

    private fun resolveSegmentUrl(playlistUrl: String, body: String): String? {
        val lines = body.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                return resolveRelativeUrl(playlistUrl, trimmed)
            }
        }
        return null
    }

    private fun resolveRelativeUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        val baseUrl = base.substringBeforeLast("/")
        return "$baseUrl/$relative"
    }

    private fun parseResolution(body: String): String? {
        val m = Regex("""RESOLUTION=(\d+x\d+)""").find(body)
        return m?.groupValues?.get(1)
    }

    private fun parseCodec(body: String): String? {
        val m = Regex("""CODECS="([^"]+)""").find(body)
        return m?.groupValues?.get(1)
    }

    private fun buildRedirectChain(response: okhttp3.Response): List<String> {
        val chain = mutableListOf<String>()
        var r: okhttp3.Response? = response
        while (r != null) {
            chain.add(r.request.url.toString())
            r = r.priorResponse
        }
        return chain
    }
}
