package com.streamverse.core.data.remote.m3u

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader

data class M3uEntry(
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val category: String?,
    val tvgId: String?,
    // Per-stream HTTP headers parsed from #EXTVLCOPT / #EXTHTTP directives. Many CDN feeds
    // 403 without the exact User-Agent/Referer the playlist specifies.
    val headers: Map<String, String> = emptyMap(),
    // ClearKey DRM (KID:KEY hex) parsed from #KODIPROP:inputstream.adaptive.license_key.
    val drmKeyId: String? = null,
    val drmKey: String? = null,
)

object M3uParser {
    private val tvgIdRegex = Regex("""tvg-id="(.*?)"""", RegexOption.IGNORE_CASE)
    private val tvgLogoRegex = Regex("""tvg-logo="(.*?)"""", RegexOption.IGNORE_CASE)
    private val groupTitleRegex = Regex("""group-title="(.*?)"""", RegexOption.IGNORE_CASE)

    fun parse(url: String, client: OkHttpClient): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body ?: throw RuntimeException("Empty response from $url")

        body.byteStream().buffered().use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                var currentName: String? = null
                var currentLogo: String? = null
                var currentCategory: String? = null
                var currentTvgId: String? = null
                var currentHeaders = mutableMapOf<String, String>()
                var currentDrmKid: String? = null
                var currentDrmKey: String? = null

                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("#EXTINF:")) {
                        val commaIdx = line.lastIndexOf(',')
                        if (commaIdx > 0 && commaIdx < line.length - 1) {
                            val attrEnd = commaIdx
                            val attrs = if (attrEnd > 8) line.substring(8, attrEnd) else ""
                            currentName = line.substring(commaIdx + 1).trim()
                            currentTvgId = tvgIdRegex.find(attrs)?.groupValues?.getOrNull(1)
                                ?.takeIf { it.isNotBlank() && it != "undefined" }
                            currentLogo = tvgLogoRegex.find(attrs)?.groupValues?.getOrNull(1)
                                ?.takeIf { it.isNotBlank() }
                            currentCategory = groupTitleRegex.find(attrs)?.groupValues?.getOrNull(1)
                                ?.takeIf { it.isNotBlank() }
                        }
                    } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                        // e.g. #EXTVLCOPT:http-user-agent=... / http-referrer=... / http-origin=...
                        val opt = line.substring("#EXTVLCOPT:".length)
                        val eq = opt.indexOf('=')
                        if (eq > 0) {
                            val key = opt.substring(0, eq).trim().lowercase()
                            val value = opt.substring(eq + 1).trim()
                            if (value.isNotBlank()) when (key) {
                                "http-user-agent" -> currentHeaders["User-Agent"] = value
                                "http-referrer", "http-referer" -> currentHeaders["Referer"] = value
                                "http-origin" -> currentHeaders["Origin"] = value
                            }
                        }
                    } else if (line.startsWith("#EXTHTTP:", ignoreCase = true)) {
                        // e.g. #EXTHTTP:{"User-Agent":"...","Referer":"..."}
                        parseExtHttpJson(line.substring("#EXTHTTP:".length).trim())
                            .forEach { (k, v) -> currentHeaders[k] = v }
                    } else if (line.startsWith("#KODIPROP:", ignoreCase = true)) {
                        val prop = line.substring("#KODIPROP:".length)
                        val eq = prop.indexOf('=')
                        if (eq > 0) {
                            val key = prop.substring(0, eq).trim().lowercase()
                            val value = prop.substring(eq + 1).trim()
                            // ClearKey only: KID:KEY (hex:hex). Widevine/other DRM is out of scope here.
                            if (key == "inputstream.adaptive.license_key") {
                                val parts = value.split(':')
                                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                                    currentDrmKid = parts[0].trim()
                                    currentDrmKey = parts[1].trim()
                                }
                            }
                        }
                    } else if (line.isNotBlank() && !line.startsWith("#") && currentName != null) {
                        entries.add(
                            M3uEntry(
                                name = currentName!!,
                                streamUrl = line,
                                logoUrl = currentLogo,
                                category = currentCategory,
                                tvgId = currentTvgId,
                                headers = currentHeaders.toMap(),
                                drmKeyId = currentDrmKid,
                                drmKey = currentDrmKey,
                            )
                        )
                        currentName = null
                        currentLogo = null
                        currentCategory = null
                        currentTvgId = null
                        currentHeaders = mutableMapOf()
                        currentDrmKid = null
                        currentDrmKey = null
                    }
                }
            }
        }
        response.close()
        return entries
    }

    private fun parseExtHttpJson(json: String): Map<String, String> = try {
        val obj = org.json.JSONObject(json)
        buildMap { obj.keys().forEach { k -> obj.optString(k).takeIf { it.isNotBlank() }?.let { put(k, it) } } }
    } catch (_: Exception) {
        emptyMap()
    }

    fun inferQuality(url: String, name: String): String? {
        val lower = if (url.length < 200) url.lowercase() + name.lowercase() else name.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> "4K"
            lower.contains("1080") || lower.contains("fhd") -> "FHD"
            lower.contains("720") || lower.contains("hd") -> "HD"
            lower.contains("480") || lower.contains("sd") -> "SD"
            else -> null
        }
    }

    fun inferCountry(tvgId: String?): String? {
        if (tvgId == null) return null
        val dot = tvgId.indexOf('.')
        if (dot < 0) return null
        val code = tvgId.substring(dot + 1, minOf(dot + 3, tvgId.length))
        return code.takeIf { it.length == 2 && it.all { c -> c.isLetter() } }?.uppercase()
    }
}
