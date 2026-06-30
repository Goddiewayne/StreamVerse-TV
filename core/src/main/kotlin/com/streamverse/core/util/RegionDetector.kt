package com.streamverse.core.util

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegionDetector @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "RegionDetector"
        private const val PREFS = "region_cache"
        private const val KEY_COUNTRY = "detected_country"
        private const val KEY_TIMESTAMP = "detected_at_ms"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000L
        private const val GEO_API = "http://ip-api.com/json/"
    }

    private val client = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getCachedRegion(): String? {
        val country = prefs.getString(KEY_COUNTRY, null)
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        if (country != null && (System.currentTimeMillis() - timestamp) < CACHE_TTL_MS) {
            return country
        }
        return null
    }

    suspend fun detectRegion(): String = withContext(Dispatchers.IO) {
        getCachedRegion()?.let { return@withContext it }

        val ipCountry = try {
            val resp = client.newCall(
                okhttp3.Request.Builder().url(GEO_API)
                    .header("User-Agent", "StreamVerse/1.0")
                    .build()
            ).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.optString("status") == "success") {
                    json.optString("countryCode").takeIf { it.isNotBlank() }
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "IP geolocation failed: ${e.message}")
            null
        }

        val region = ipCountry ?: java.util.Locale.getDefault().country.takeIf { it.isNotBlank() }?.uppercase()

        if (region != null) {
            prefs.edit()
                .putString(KEY_COUNTRY, region)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
        }

        region ?: "US"
    }
}
