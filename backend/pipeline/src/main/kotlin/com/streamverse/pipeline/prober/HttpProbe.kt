package com.streamverse.pipeline.prober

import com.streamverse.pipeline.config.PipelineConfig
import com.streamverse.pipeline.telemetry.StructuredLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class HttpProbe(
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
                .header("Range", "bytes=0-65535")
                .build()

            val response = client.newCall(req).execute()
            val statusCode = response.code
            val contentType = response.header("Content-Type") ?: ""
            val contentLength = response.body?.contentLength() ?: -1
            val elapsed = System.currentTimeMillis() - startMs
            val chain = buildRedirectChain(response)

            if (statusCode in 200..299) {
                val bodyBytes = try { response.body?.bytes()?.size ?: -1 } catch (_: Exception) { -1 }
                val mimeType = contentType.substringBefore(";").trim()
                val isStreamable = when {
                    mimeType.contains("mpegurl") || mimeType.contains("m3u") -> true
                    mimeType.contains("mp4") || mimeType.contains("mpeg") -> true
                    mimeType.contains("ts") || mimeType.contains("transport") -> true
                    mimeType.contains("octet-stream") -> true
                    mimeType.contains("x-mpegurl") -> true
                    else -> false
                }

                return ProbeEngine.ProbeOutcome(
                    success = isStreamable || contentLength > 0 || (bodyBytes ?: 0) > 0,
                    manifestAvailable = true,
                    playlistValid = mimeType.contains("m3u") || mimeType.contains("mpegurl"),
                    segmentAccessible = bodyBytes ?: 0 > 0,
                    playbackStartupMs = elapsed,
                    decoderCompatible = isStreamable,
                    mimeType = mimeType,
                    httpStatus = statusCode,
                    redirectChain = chain,
                )
            } else if (statusCode in 300..399) {
                return ProbeEngine.ProbeOutcome(
                    success = false, httpStatus = statusCode,
                    redirectChain = chain, mimeType = contentType,
                    failureReason = "Redirect $statusCode",
                )
            } else if (statusCode == 401 || statusCode == 403) {
                return ProbeEngine.ProbeOutcome(
                    success = false, httpStatus = statusCode,
                    mimeType = contentType, requiresAuth = true,
                    failureReason = if (statusCode == 401) "Unauthorized" else "Forbidden",
                )
            } else {
                return ProbeEngine.ProbeOutcome(
                    success = false, httpStatus = statusCode,
                    mimeType = contentType, failureReason = "HTTP $statusCode",
                )
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            val reason = when {
                e.message?.contains("timeout", ignoreCase = true) == true -> "Timeout"
                e.message?.contains("reset", ignoreCase = true) == true -> "Connection reset"
                e.message?.contains("refused", ignoreCase = true) == true -> "Connection refused"
                e.message?.contains("SSL", ignoreCase = true) == true -> "SSL error"
                else -> e.message ?: e::class.simpleName ?: "Unknown"
            }
            return ProbeEngine.ProbeOutcome(
                success = false, httpStatus = -1, failureReason = reason,
            )
        }
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
