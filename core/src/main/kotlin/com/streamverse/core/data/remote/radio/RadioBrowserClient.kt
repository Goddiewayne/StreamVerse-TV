package com.streamverse.core.data.remote.radio

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamverse.core.data.model.RadioStation
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class RadioStationDto(
    val stationuuid: String,
    val name: String,
    val url: String?,
    val url_resolved: String?,
    val favicon: String?,
    val country: String?,
    val countrycode: String?,
    val language: String?,
    val tags: String?,
    val codec: String?,
    val bitrate: Int?,
    val clickcount: Int?,
)

@Singleton
class RadioBrowserClient @Inject constructor(
    private val gson: Gson,
    private val dispatchers: StreamVerseDispatchers,
    okHttpClient: OkHttpClient,
) {
    private val baseUrl = "https://de1.api.radio-browser.info"

    // Reuse the shared pool + HTTP cache.
    private val client = okHttpClient

    private var cachedStations: List<RadioStation>? = null
    private var cacheTime: Long = 0

    suspend fun fetchStations(forceRefresh: Boolean = false): Result<List<RadioStation>> =
        withContext(dispatchers.io) {
            runCatching {
                val now = System.currentTimeMillis()
                if (!forceRefresh && cachedStations != null && (now - cacheTime) < 86_400_000L) {
                    return@runCatching cachedStations!!
                }
                coroutineScope {
                    val topClickDeferred = async { runCatching { fetchJson("$baseUrl/json/stations/topclick/500") } }
                    val nigeriaDeferred = async { runCatching { fetchJson("$baseUrl/json/stations/bycountry/Nigeria?limit=200") } }
                    val southAfricaDeferred = async { runCatching { fetchJson("$baseUrl/json/stations/bycountry/South%20Africa?limit=200") } }
                    val ghanaDeferred = async { runCatching { fetchJson("$baseUrl/json/stations/bycountry/Ghana?limit=200") } }

                    val allDtos = mutableListOf<RadioStationDto>()
                    for (deferred in listOf(topClickDeferred, nigeriaDeferred, southAfricaDeferred, ghanaDeferred)) {
                        val json = deferred.await().getOrNull() ?: continue
                        try {
                            val dtos: List<RadioStationDto> = gson.fromJson(json, stationListType)
                            allDtos.addAll(dtos)
                        } catch (_: Exception) { }
                    }

                    val stations = allDtos
                        .distinctBy { it.stationuuid }
                        .map { it.toRadioStation() }

                    cachedStations = stations
                    cacheTime = now
                    stations
                }
            }
        }

    private val stationListType = object : TypeToken<List<RadioStationDto>>() {}.type

    private fun fetchJson(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "StreamVerse/1.0")
            .build()
        val response = client.newCall(request).execute()
        return response.body?.string() ?: throw RuntimeException("Empty response from $url")
    }

    private fun RadioStationDto.toRadioStation(): RadioStation {
        val resolvedUrl = url_resolved?.takeIf { it.isNotBlank() } ?: url ?: ""
        return RadioStation(
            id = stationuuid,
            name = name,
            streamUrl = resolvedUrl,
            logoUrl = favicon?.takeIf { it.isNotBlank() },
            country = country?.takeIf { it.isNotBlank() },
            countryCode = countrycode?.takeIf { it.isNotBlank() }?.uppercase(),
            language = language?.takeIf { it.isNotBlank() },
            tags = tags?.takeIf { it.isNotBlank() },
            codec = codec?.takeIf { it.isNotBlank() },
            bitrate = bitrate,
            clickCount = clickcount,
        )
    }
}
