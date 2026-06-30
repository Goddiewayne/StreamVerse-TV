package com.streamverse.core.data.remote.premium.hunter

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubHunter @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : SourceHunter {
    override val name: String = "github"

    private val client = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val repoQueries = listOf(
        "iptv premium playlist",
        "dstv m3u playlist",
        "supersport m3u",
        "hbo m3u playlist",
        "iptv m3u link",
        "free iptv m3u",
        "iptv list m3u",
        "live tv m3u playlist",
        "premium iptv m3u8",
        "world cup iptv m3u",
        "sports iptv m3u",
    )

    private val gistQueries = listOf(
        "iptv extension:m3u",
        "m3u playlist extension:m3u",
        "live tv extension:m3u",
        "premium iptv extension:m3u",
        "dstv extension:m3u",
        "supersport extension:m3u",
    )

    private val fallbackPaths = listOf(
        "playlist.m3u", "channels.m3u", "index.m3u",
        "iptv.m3u", "live.m3u", "tv.m3u",
    )

    override suspend fun hunt(config: HunterConfig): List<DiscoveredSource> = withContext(Dispatchers.IO) {
        val results = mutableSetOf<DiscoveredSource>()
        results.addAll(searchRepos(config))
        if (results.size < config.maxResults) {
            results.addAll(searchGists(config))
        }
        if (results.size < config.maxResults) {
            results.addAll(fallbackCommonRepos(config))
        }
        results.take(config.maxResults).toList()
    }

    private suspend fun searchRepos(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        repoQueries.map { query ->
            async {
                try {
                    val repos = searchGitHubRepos(query, 5) ?: emptyList()
                    repos.mapNotNull { (fullName, branch) ->
                        val files = findM3uFiles(fullName, branch)
                        files.firstNotNullOfOrNull { (name, rawUrl) ->
                            val key = sanitizeKey(name)
                            if (key.isNotBlank()) {
                                DiscoveredSource(
                                    key = key,
                                    url = rawUrl,
                                    label = "$fullName/$name",
                                    hunterName = name,
                                )
                            } else null
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Repo query '$query' failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun searchGists(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        gistQueries.map { query ->
            async {
                try {
                    val gists = searchGitHubGists(query, 3) ?: emptyList()
                    gists.mapNotNull { (gistId, filename, rawUrl) ->
                        if (!isValidM3u(rawUrl)) return@mapNotNull null
                        val key = sanitizeKey(filename)
                        if (key.isNotBlank()) {
                            DiscoveredSource(
                                key = key,
                                url = rawUrl,
                                label = "gist:$gistId/$filename",
                                hunterName = name,
                            )
                        } else null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Gist query '$query' failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun fallbackCommonRepos(config: HunterConfig): List<DiscoveredSource> = coroutineScope {
        val commonRepos = listOf(
            "Shariar-Ahamed/online-tv-streaming-platform" to "main",
            "Free-TV/IPTV" to "master",
            "iptv-org/iptv" to "master",
            "Romaxa55/world_ip_tv" to "master",
            "MARIKO578/IPTV" to "main",
        )
        commonRepos.map { (fullName, branch) ->
            async {
                try {
                    findM3uFiles(fullName, branch).mapNotNull { (name, rawUrl) ->
                        val key = sanitizeKey(name)
                        if (key.isNotBlank()) {
                            DiscoveredSource(
                                key = key,
                                url = rawUrl,
                                label = "$fullName/$name",
                                hunterName = name,
                            )
                        } else null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback repo $fullName failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private fun searchGitHubRepos(query: String, perPage: Int): List<Pair<String, String>>? {
        val url = "https://api.github.com/search/repositories?q=$query&sort=updated&per_page=$perPage"
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "StreamVerse/1.0")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return null
        val body = resp.body?.string() ?: return null
        val items = JSONObject(body).getJSONArray("items")
        return (0 until items.length()).map { i ->
            val repo = items.getJSONObject(i)
            repo.getString("full_name") to repo.optString("default_branch", "master")
        }
    }

    private fun searchGitHubGists(query: String, perPage: Int): List<Triple<String, String, String>>? {
        val url = "https://api.github.com/search/code?q=$query&per_page=$perPage"
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "StreamVerse/1.0")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            if (resp.code == 403) return searchRecentGists(perPage)
            return null
        }
        val body = resp.body?.string() ?: return null
        val items = JSONObject(body).getJSONArray("items")
        return (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val name = item.getString("name")
            val htmlUrl = item.getString("html_url")
            val gistId = htmlUrl.substringAfter("gist.github.com/").substringBefore("/")
            val rawUrl = htmlUrl
                .replace("github.com", "raw.githubusercontent.com")
                .removeSuffix("/$name") + "/raw/$name"
            Triple(gistId, name, rawUrl)
        }
    }

    private fun searchRecentGists(count: Int): List<Triple<String, String, String>>? {
        val url = "https://api.github.com/gists/public?per_page=$count"
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "StreamVerse/1.0")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return null
        val body = resp.body?.string() ?: return null
        val gists = JSONArray(body)
        return (0 until gists.length()).mapNotNull { i ->
            val gist = gists.getJSONObject(i)
            val gistId = gist.getString("id")
            val desc = gist.optString("description", "")
            if (!desc.contains("iptv", ignoreCase = true) &&
                !desc.contains("m3u", ignoreCase = true) &&
                !desc.contains("playlist", ignoreCase = true)
            ) return@mapNotNull null
            val files = gist.getJSONObject("files")
            val keys = files.keys()
            while (keys.hasNext()) {
                val fname = keys.next()
                if (fname.endsWith(".m3u") || fname.endsWith(".m3u8")) {
                    val f = files.getJSONObject(fname)
                    val rawUrl = f.getString("raw_url")
                    return@mapNotNull Triple(gistId, fname, rawUrl)
                }
            }
            null
        }
    }

    private fun findM3uFiles(fullName: String, branch: String): List<Pair<String, String>> {
        val url = "https://api.github.com/repos/$fullName/git/trees/$branch?recursive=1"
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "StreamVerse/1.0")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            if (resp.code == 403) return probeFallbackPaths(fullName, branch)
            return emptyList()
        }
        val body = resp.body?.string() ?: return emptyList()
        val tree = JSONObject(body).getJSONArray("tree")
        val results = mutableListOf<Pair<String, String>>()
        for (i in 0 until tree.length()) {
            val entry = tree.getJSONObject(i)
            val path = entry.getString("path")
            val mode = entry.optString("mode", "")
            if ((path.endsWith(".m3u") || path.endsWith(".m3u8")) && mode !in setOf("120000", "160000")) {
                val rawUrl = "https://raw.githubusercontent.com/$fullName/$branch/$path"
                results.add(path.substringAfterLast('/') to rawUrl)
            }
        }
        return results
    }

    private fun probeFallbackPaths(fullName: String, branch: String): List<Pair<String, String>> {
        return fallbackPaths.mapNotNull { path ->
            val rawUrl = "https://raw.githubusercontent.com/$fullName/$branch/$path"
            try {
                if (isValidM3u(rawUrl)) path to rawUrl else null
            } catch (_: Exception) { null }
        }
    }

    private fun isValidM3u(url: String): Boolean {
        val req = Request.Builder().url(url)
            .header("User-Agent", "StreamVerse/1.0")
            .header("Range", "bytes=0-2047")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return false
        val data = resp.body?.string() ?: return false
        return data.startsWith("#EXTM3U") && data.count { it == '\n' } >= 5
    }

    private fun sanitizeKey(name: String): String {
        return name.removeSuffix(".m3u").removeSuffix(".m3u8")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase().trim('_')
    }

    companion object {
        private const val TAG = "GitHubHunter"
    }
}
