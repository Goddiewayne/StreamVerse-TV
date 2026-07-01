package com.streamverse.core.data.remote

import com.google.gson.Gson
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.util.StreamVerseDispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MergedIndexClient @Inject constructor(
    private val gson: Gson,
    private val dispatchers: StreamVerseDispatchers,
    okHttpClient: OkHttpClient,
) {
    private val baseUrl = "https://Goddiewayne.github.io/streamverse-data"

    private val client = okHttpClient.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    data class MergedResponse(
        val version: Int,
        val generatedAtMs: Long,
        val channels: List<Channel>,
    )

    suspend fun fetchAll(): List<Channel>? = withContext(dispatchers.io) {
        runCatching {
            val url = "$baseUrl/merged.json"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val parsed = gson.fromJson(body, MergedResponse::class.java)
                parsed.channels
            }
        }.getOrNull()
    }
}
