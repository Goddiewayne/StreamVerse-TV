package com.streamverse.core.data.repository

import android.content.Context
import android.util.Log
import com.streamverse.core.data.ChannelCacheManager
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.catalogue.CatalogueClient
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.ChannelSummary
import com.streamverse.core.domain.model.SourceType
import com.streamverse.core.domain.model.toSummary
import com.streamverse.core.util.StreamVerseDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class LoadingPhase { IDLE, CACHE, LOADING, DONE }
enum class ProviderLoadingPhase { PENDING, LOADING, COMPLETE, FAILED, SKIPPED }

@Singleton
class ChannelRepository @Inject constructor(
    private val catalogueClient: CatalogueClient,
    private val sourcePreferences: SourcePreferences,
    private val dispatchers: StreamVerseDispatchers,
    private val cacheManager: ChannelCacheManager,
    @ApplicationContext private val context: Context,
) {
    private val tag = "ChannelRepository"
    private val loadInProgress = AtomicBoolean(false)
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private val RE_SEARCH_WORD = Regex("""[\s\-/\.&']+""")
    }

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    private val _loadingPhase = MutableStateFlow(LoadingPhase.IDLE)
    private val _isLoading = MutableStateFlow(false)
    private val _channelRefreshTrigger = MutableStateFlow(0L)

    val loadingPhase: Flow<LoadingPhase> = _loadingPhase.asStateFlow()
    val isLoading: Flow<Boolean> = _isLoading.asStateFlow()
    val channelRefreshTrigger: StateFlow<Long> = _channelRefreshTrigger.asStateFlow()

    val providerProgress: StateFlow<Map<SourceProvider, ProviderLoadingPhase>> =
        MutableStateFlow(SourceProvider.entries.associateWith { ProviderLoadingPhase.COMPLETE })

    val channels: Flow<List<Channel>> =
        combine(_channels, sourcePreferences.enabledFlow) { chs, enabled ->
            if (chs.isEmpty()) return@combine emptyList()
            chs.filter { hasEnabledSource(it, enabled) }
        }.flowOn(dispatchers.default)

    val channelSummaries: Flow<List<ChannelSummary>> =
        channels.map { it.map { ch -> ch.toSummary() } }.flowOn(dispatchers.default)

    val availableChannelIds: StateFlow<Set<String>> =
        channels.map { list -> list.mapTo(HashSet()) { it.id } }
            .stateIn(repoScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    val radioChannels: Flow<List<Channel>> =
        combine(_channels, sourcePreferences.enabledFlow) { all, enabled ->
            if (enabled[SourceProvider.RADIO] == false) emptyList()
            else all.asSequence().filter { it.sources.containsKey(SourceType.RADIO) }.take(80).toList()
        }.flowOn(dispatchers.default)

    private fun hasEnabledSource(channel: Channel, enabled: Map<SourceProvider, Boolean>): Boolean {
        return channel.sources.keys.any { type ->
            val provider = SourceProvider.forType(type)
            enabled[provider] != false
        }
    }

    @Volatile private var _idIndex: Map<String, Channel> = emptyMap()

    suspend fun load() = withContext(dispatchers.io) {
        if (!loadInProgress.compareAndSet(false, true)) return@withContext
        try {
            _loadingPhase.value = LoadingPhase.LOADING
            _isLoading.value = true

            val cached = cacheManager.load()
            if (cached != null && cached.isNotEmpty()) {
                _loadingPhase.value = LoadingPhase.CACHE
                _channels.value = cached
                _idIndex = cached.associateBy { it.id }
                _isLoading.value = false
            }

            val result = catalogueClient.load()
            if (result.channels.isNotEmpty()) {
                _channels.value = result.channels
                _idIndex = result.channels.associateBy { it.id }
                _loadingPhase.value = LoadingPhase.DONE
                _isLoading.value = false
                _channelRefreshTrigger.value++
                Log.d(tag, "Loaded ${result.channels.size} channels (v${result.manifest.version}, cache=${result.fromCache})")
            } else {
                _loadingPhase.value = LoadingPhase.DONE
                _isLoading.value = false
                _channelRefreshTrigger.value++
            }
        } finally {
            loadInProgress.set(false)
        }
    }

    suspend fun reload() {
        cacheManager.invalidate()
        load()
    }

    suspend fun searchChannels(query: String, category: String? = null): List<Channel> = withContext(dispatchers.io) {
        val q = query.lowercase().trim()
        val pool = _channels.value
        if (q.isEmpty()) {
            if (category != null) return@withContext pool.filter { it.category == category }
            return@withContext emptyList()
        }
        val catFilter = category?.lowercase()
        data class Scored(val channel: Channel, val score: Int)
        pool.mapNotNull { ch ->
            if (catFilter != null && ch.category?.lowercase() != catFilter) return@mapNotNull null
            val n = ch.displayName.lowercase()
            val score = when {
                n == q -> 0
                n.startsWith(q) -> 1
                n.split(RE_SEARCH_WORD).any { it.startsWith(q) } -> 2
                q in (ch.category?.lowercase() ?: "") -> 3
                q in (ch.language?.lowercase() ?: "") -> 3
                q in (ch.country?.lowercase() ?: "") -> 3
                ch.aliases.any { q in it.lowercase() } -> 3
                q in (ch.description?.lowercase() ?: "") -> 3
                n.contains(q) -> 4
                else -> return@mapNotNull null
            }
            Scored(ch, score)
        }.sortedWith(compareBy<Scored> { it.score }.thenBy { it.channel.displayName.length })
            .map { it.channel }
            .let { results ->
                val enabled = sourcePreferences.enabled()
                results.filter { hasEnabledSource(it, enabled) }
            }
    }

    fun getCachedChannels(): List<Channel> = _channels.value

    fun getAllChannels(): List<Channel> = _channels.value

    fun getChannelByIdMap(): Map<String, Channel> = _idIndex

    suspend fun getChannelById(id: String): Channel? = _idIndex[id]

    suspend fun getAvailableChannel(id: String): Channel? {
        val ch = _idIndex[id] ?: return null
        val enabled = sourcePreferences.enabled()
        return if (hasEnabledSource(ch, enabled)) ch else null
    }

    suspend fun getChannelBySourceRef(sourceType: SourceType, referenceId: String): Channel? {
        val refId = referenceId.trim().lowercase()
        return _idIndex.values.find { ch ->
            ch.sources[sourceType]?.referenceId?.trim()?.lowercase() == refId
        }
    }

    suspend fun getCategories(): List<String> =
        _channels.value.mapNotNull { it.category }.distinct().sorted()
}
