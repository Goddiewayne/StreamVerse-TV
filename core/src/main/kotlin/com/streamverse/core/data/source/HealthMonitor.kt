package com.streamverse.core.data.source

import android.util.Log
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class InstanceHealth(
    val instanceId: String,
    val channelId: String,
    val sourceType: SourceType,
    val providerId: String,
    val isReachable: Boolean,
    val latencyMs: Long = -1,
    val lastCheckedMs: Long = 0,
    val consecutiveFailures: Int = 0,
    val totalProbes: Int = 0,
    val successfulProbes: Int = 0,
) {
    val successRate: Float
        get() = if (totalProbes == 0) 0.5f else successfulProbes.toFloat() / totalProbes
}

data class InstanceProbeResult(
    val instanceId: String,
    val reachable: Boolean,
    val latencyMs: Long,
    val error: String? = null,
)

@Singleton
class HealthMonitor @Inject constructor(
    private val sourceRegistry: SourceRegistry,
) {
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build()

    private val instanceHealth = ConcurrentHashMap<String, InstanceHealth>()
    private val _liveInstances = MutableStateFlow<Set<String>>(emptySet())
    private val _monitorStats = MutableStateFlow(MonitorStats())

    val liveInstances: StateFlow<Set<String>> = _liveInstances.asStateFlow()
    val monitorStats: StateFlow<MonitorStats> = _monitorStats.asStateFlow()

    data class MonitorStats(
        val totalProbes: Long = 0,
        val successfulProbes: Long = 0,
        val activeInstances: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun schedulePeriodicProbe(instanceId: String, url: String, intervalMs: Long = 5 * 60 * 1000L) {
        if (activeJobs.containsKey(instanceId)) return
        activeJobs[instanceId] = scope.launch {
            while (isActive) {
                probeInstance(instanceId, url)
                delay(intervalMs)
            }
        }
    }

    fun stopPeriodicProbe(instanceId: String) {
        activeJobs.remove(instanceId)?.cancel()
    }

    suspend fun probeInstance(instanceId: String, url: String): InstanceProbeResult {
        val startMs = System.currentTimeMillis()
        val result = withTimeoutOrNull(8_000L) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", DEFAULT_UA)
                    .build()
                probeClient.newCall(request).execute().use { response ->
                    response.isSuccessful || response.code == 206
                }
            }.getOrDefault(false)
        } ?: false

        val latency = System.currentTimeMillis() - startMs
        val probeResult = InstanceProbeResult(instanceId, result, latency)

        updateInstanceHealth(instanceId, probeResult)
        updateMonitorStats()

        return probeResult
    }

    fun getInstanceHealth(instanceId: String): InstanceHealth? = instanceHealth[instanceId]

    fun isInstanceLive(instanceId: String): Boolean =
        instanceHealth[instanceId]?.isReachable == true

    fun recordSourceHealthFromPlayback(
        channelId: String,
        sourceType: SourceType,
        providerId: String,
        success: Boolean,
        latencyMs: Long,
    ) {
        val instanceId = "$channelId:${sourceType.name}"
        val current = instanceHealth[instanceId] ?: InstanceHealth(
            instanceId = instanceId,
            channelId = channelId,
            sourceType = sourceType,
            providerId = providerId,
            isReachable = false,
        )
        instanceHealth[instanceId] = current.copy(
            isReachable = if (success) true else current.isReachable,
            latencyMs = if (success) latencyMs else current.latencyMs,
            lastCheckedMs = System.currentTimeMillis(),
            consecutiveFailures = if (success) 0 else current.consecutiveFailures + 1,
            totalProbes = current.totalProbes + 1,
            successfulProbes = current.successfulProbes + (if (success) 1 else 0),
        )
        recomputeLiveInstances()
    }

    fun getLiveChannels(channels: List<Channel>): Set<String> {
        val liveIds = mutableSetOf<String>()
        for (ch in channels) {
            val hasLiveSource = ch.sources.keys.any { type ->
                val instanceId = "${ch.id}:${type.name}"
                instanceHealth[instanceId]?.isReachable == true
            }
            if (hasLiveSource) liveIds.add(ch.id)
        }
        return liveIds
    }

    private fun updateInstanceHealth(instanceId: String, result: InstanceProbeResult) {
        val current = instanceHealth[instanceId] ?: InstanceHealth(
            instanceId = instanceId,
            channelId = instanceId.substringBeforeLast(":"),
            sourceType = SourceType.valueOf(instanceId.substringAfterLast(":")),
            providerId = "",
            isReachable = false,
        )
        instanceHealth[instanceId] = current.copy(
            isReachable = result.reachable,
            latencyMs = result.latencyMs,
            lastCheckedMs = System.currentTimeMillis(),
            consecutiveFailures = if (result.reachable) 0 else current.consecutiveFailures + 1,
            totalProbes = current.totalProbes + 1,
            successfulProbes = current.successfulProbes + (if (result.reachable) 1 else 0),
        )
        recomputeLiveInstances()
    }

    private fun recomputeLiveInstances() {
        _liveInstances.value = instanceHealth.asSequence()
            .filter { it.value.isReachable }
            .map { it.key }
            .toHashSet()
    }

    private fun updateMonitorStats() {
        val total = instanceHealth.values.sumOf { it.totalProbes.toLong() }
        val success = instanceHealth.values.sumOf { it.successfulProbes.toLong() }
        _monitorStats.value = MonitorStats(
            totalProbes = total,
            successfulProbes = success,
            activeInstances = instanceHealth.size,
        )
    }

    fun clear() {
        instanceHealth.clear()
        _liveInstances.value = emptySet()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
