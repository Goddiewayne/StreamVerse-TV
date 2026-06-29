package com.streamverse.core.data.remote.fast

import android.util.Log
import com.streamverse.core.data.remote.m3u.M3uParser
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class FastChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val category: String?,
    val country: String?,
    val service: String,
    val headers: Map<String, String> = emptyMap(),
    val drmKeyId: String? = null,
    val drmKey: String? = null,
)

/**
 * Fetches supplemental channel streams from multiple curated M3U sources.
 *
 * Sources:
 *  - iptv-org legacy streams repo (raw.githubusercontent.com) — curated country streams
 *    that often have DIFFERENT stream CDN endpoints from the main iptv-org index, giving
 *    backup redundancy. Also carries many Samsung TV Plus / Pluto TV channels whose CDN
 *    streams are publicly accessible via Amagi, Verizon Media, etc.
 *  - Africa region from iptv-org GitHub Pages — broad African coverage
 *
 * All channels are deduplicated by display name in ChannelRepository.enrichAndMerge, so
 * channels present in both this client and IptvClient are MERGED (extra stream source)
 * rather than appearing twice.
 */
@Singleton
class FastTvClient @Inject constructor(
    private val dispatchers: StreamVerseDispatchers,
    okHttpClient: OkHttpClient,
) {
    // Reuse the shared pool + HTTP cache. A total callTimeout caps the whole request so a slow CDN
    // can't stall the load (readTimeout alone only bounds each read).
    private val client = okHttpClient.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    // Legacy iptv-org raw streams — different CDN endpoints from the main index
    // Grouped by region for clarity
    private val sources = mapOf(
        // ── Africa ──────────────────────────────────────────────────────────
        "ng" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ng.m3u",  // Nigeria
        "gh" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/gh.m3u",  // Ghana
        "za" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/za.m3u",  // South Africa
        "ke" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ke.m3u",  // Kenya
        "et" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/et.m3u",  // Ethiopia
        "tz" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/tz.m3u",  // Tanzania
        "ug" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ug.m3u",  // Uganda
        "ci" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ci.m3u",  // Ivory Coast
        "cm" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cm.m3u",  // Cameroon
        "sn" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/sn.m3u",  // Senegal
        "rw" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/rw.m3u",  // Rwanda
        "ma" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ma.m3u",  // Morocco
        "eg" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/eg.m3u",  // Egypt
        "tz2" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ao.m3u", // Angola
        "mx2" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/dz.m3u", // Algeria
        // ── Global markets ────────────────────────────────────────────────
        "uk" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/uk.m3u",  // UK (ITV1, BBC, Channel 4…)
        "us" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/us.m3u",  // USA (FAST channels)
        "in" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/in.m3u",  // India
        "ph" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ph.m3u",  // Philippines
        "mx" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/mx.m3u",  // Mexico
        "br" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/br.m3u",  // Brazil
        "tr" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/tr.m3u",  // Turkey
        "ar" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ar.m3u",  // Argentina
        "de" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/de.m3u",  // Germany
        "fr" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/fr.m3u",  // France
        "es" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/es.m3u",  // Spain
        "it" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/it.m3u",  // Italy
        "ru" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ru.m3u",  // Russia
    )

    private var cachedChannels: List<FastChannel>? = null
    private var cacheTime: Long = 0L

    private companion object {
        // Dev-only diagnostic: a guaranteed-live reference HLS so the proxy + player pipeline
        // can be verified end-to-end when the real (frequently-rotated) feeds are offline.
        // Set to false / remove before shipping.
        const val DEBUG_TEST_CHANNEL = false
    }

    private fun testChannel() = FastChannel(
        id = "zztest",
        name = "ZZ Test Stream (diag)",
        streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        logoUrl = null,
        category = "General",
        country = "US",
        service = "diag",
    )

    suspend fun fetchChannels(forceRefresh: Boolean = false): Result<List<FastChannel>> =
        withContext(dispatchers.io) {
            runCatching {
                val now = System.currentTimeMillis()
                if (!forceRefresh && cachedChannels != null && (now - cacheTime) < 86_400_000L) {
                    return@runCatching cachedChannels!!
                }
                val all = coroutineScope {
                    sources.map { (key, url) ->
                        async {
                            try {
                                val entries = M3uParser.parse(url, client)
                                Log.d("FastTvClient", "$key: ${entries.size} channels from $url")
                                entries.map { entry ->
                                    FastChannel(
                                        id      = "${key}_${(entry.tvgId ?: entry.name).hashCode().and(0x7FFFFFFF)}",
                                        name    = entry.name,
                                        streamUrl = entry.streamUrl,
                                        logoUrl = entry.logoUrl,
                                        category = entry.category,
                                        country  = key.take(2).uppercase().let {
                                            M3uParser.inferCountry(entry.tvgId) ?: it
                                        },
                                        service  = key,
                                        headers  = entry.headers,
                                        drmKeyId = entry.drmKeyId,
                                        drmKey   = entry.drmKey,
                                    )
                                }
                            } catch (e: Exception) {
                                Log.w("FastTvClient", "$key failed: ${e.message}")
                                emptyList()
                            }
                        }
                    }.map { it.await() }.flatten()
                }
                val withTest = if (DEBUG_TEST_CHANNEL) listOf(testChannel()) + all else all
                cachedChannels = withTest
                cacheTime = now
                Log.d("FastTvClient", "total supplemental: ${withTest.size} channels")
                withTest
            }
        }
}
