package com.streamverse.core.data.remote.hosted

import com.streamverse.core.util.StreamVerseDispatchers
import com.google.gson.Gson
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostedIndexClient @Inject constructor(
    private val gson: Gson,
    private val dispatchers: StreamVerseDispatchers,
    okHttpClient: OkHttpClient,
) {
    private val baseUrl = "https://Goddiewayne.github.io/StreamVerse-TV"

    private val client = okHttpClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    data class IndexResponse(
        val version: Int,
        val channels: List<HostedChannel>,
    )

    data class HostedChannel(
        val id: String,
        val name: String,
        val streamUrl: String,
        val logoUrl: String?,
        val category: String?,
        val country: String?,
        val language: String?,
        val quality: String?,
        val source: String,
        val headers: Map<String, String>?,
        val drmKeyId: String?,
        val drmKey: String?,
    )

    suspend fun fetchAll(): List<HostedChannel> = withContext(dispatchers.io) {
        runCatching {
            val url = "$baseUrl/channels.json"
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@runCatching emptyList()
            val parsed = gson.fromJson(body, IndexResponse::class.java)
            parsed.channels
        }.getOrDefault(emptyList())
    }
}
