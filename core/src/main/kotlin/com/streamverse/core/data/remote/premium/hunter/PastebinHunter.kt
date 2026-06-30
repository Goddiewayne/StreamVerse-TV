package com.streamverse.core.data.remote.premium.hunter

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PastebinHunter @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : SourceHunter {
    override val name: String = "pastebin"

    private val client = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun hunt(config: HunterConfig): List<DiscoveredSource> = withContext(Dispatchers.IO) {
        val results = mutableSetOf<DiscoveredSource>()
        results.addAll(huntPastebinArchive(config))
        results.addAll(huntPasteEe(config))
        results.addAll(huntKnownSlugs(config))
        results.take(config.maxResults).toList()
    }

    private suspend fun huntPastebinArchive(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        val results = try {
            val archiveUrl = "https://pastebin.com/archive"
            val req = Request.Builder().url(archiveUrl)
                .header("User-Agent", "StreamVerse/1.0")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@coroutineScope emptyList()
            val html = resp.body?.string() ?: return@coroutineScope emptyList()

            val pasteIds = M3U_URL_REGEX.findAll(html)
                .map { it.value }
                .filter { it.matches(Regex("^[a-zA-Z0-9]{8}$")) }
                .distinct()
                .take(30)
                .toList()

            if (pasteIds.isEmpty()) return@coroutineScope emptyList()

            pasteIds.map { id ->
                async {
                    val pasteUrl = "https://pastebin.com/raw/$id"
                    if (isM3uContent(pasteUrl)) {
                        DiscoveredSource(
                            key = "pastebin_$id",
                            url = pasteUrl,
                            label = "Pastebin/$id",
                            hunterName = name,
                        )
                    } else null
                }
            }.awaitAll().filterNotNull()
        } catch (e: Exception) {
            Log.w(TAG, "Pastebin archive failed: ${e.message}")
            emptyList()
        }
        results
    }

    private suspend fun huntPasteEe(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        val results = try {
            val browseUrl = "https://paste.ee/browse"
            val req = Request.Builder().url(browseUrl)
                .header("User-Agent", "StreamVerse/1.0")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@coroutineScope emptyList()
            val html = resp.body?.string() ?: return@coroutineScope emptyList()

            val hexIds = HEX_ID_REGEX.findAll(html)
                .map { it.value }
                .distinct()
                .take(20)
                .toList()

            if (hexIds.isEmpty()) return@coroutineScope emptyList()

            hexIds.map { id ->
                async {
                    val pasteUrl = "https://paste.ee/r/$id"
                    if (isM3uContent(pasteUrl)) {
                        DiscoveredSource(
                            key = "pasteee_$id",
                            url = pasteUrl,
                            label = "Paste.ee/$id",
                            hunterName = name,
                        )
                    } else null
                }
            }.awaitAll().filterNotNull()
        } catch (e: Exception) {
            Log.w(TAG, "Paste.ee browse failed: ${e.message}")
            emptyList()
        }
        results
    }

    private fun huntKnownSlugs(config: HunterConfig): List<DiscoveredSource> {
        val results = mutableListOf<DiscoveredSource>()
        for ((site, slug) in KNOWN_SLUGS) {
            try {
                val url = "$site$slug"
                if (isM3uContent(url)) {
                    val key = site
                        .removePrefix("https://")
                        .removePrefix("http://")
                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        .trim('_') + "_" + slug.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    results.add(
                        DiscoveredSource(
                            key = key,
                            url = url,
                            label = "$site$slug",
                            hunterName = name,
                        )
                    )
                }
            } catch (_: Exception) { /* skip */ }
        }
        return results
    }

    private fun isM3uContent(url: String): Boolean {
        val req = Request.Builder().url(url)
            .header("User-Agent", "StreamVerse/1.0")
            .header("Range", "bytes=0-2047")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return false
        val data = resp.body?.string() ?: return false
        return data.startsWith("#EXTM3U")
    }

    companion object {
        private const val TAG = "PastebinHunter"

        private val M3U_URL_REGEX = Regex("""[a-zA-Z0-9]{8}""")
        private val HEX_ID_REGEX = Regex("""[0-9a-f]{32}""")

        private val KNOWN_SLUGS = listOf(
            "https://rentry.org/" to "iptv",
            "https://rentry.co/" to "iptv",
            "https://bin.disroot.org/" to "?m3u",
            "https://ivpaste.com/" to "v/iptv",
        )
    }
}
