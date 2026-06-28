package com.streamverse.core.util

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request as OkHttpRequest
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException

class NewPipeOkHttpDownloader(
    private val client: OkHttpClient,
) : Downloader() {

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.let { data ->
            val contentType = request.headers()
                .filterKeys { it != null }
                .filter { it.key.equals("Content-Type", ignoreCase = true) }
                .values
                .firstOrNull()
                ?.firstOrNull()
                ?: "application/octet-stream"
            data.toRequestBody(contentType.toMediaTypeOrNull())
        }

        val okRequest = OkHttpRequest.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .apply {
                request.headers().forEach { (key, values) ->
                    values?.forEach { value ->
                        if (key != null) addHeader(key, value)
                    }
                }
            }
            .build()

        val okResponse = client.newCall(okRequest).execute()
        val responseBody = okResponse.body?.string() ?: ""
        val responseHeaders = okResponse.headers.toMultimap()
            .mapValues { it.value.toList() }
        val latestUrl = okResponse.request.url.toString()

        return Response(
            okResponse.code,
            okResponse.message,
            responseHeaders,
            responseBody,
            latestUrl,
        )
    }
}
