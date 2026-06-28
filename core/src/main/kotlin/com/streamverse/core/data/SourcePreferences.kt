package com.streamverse.core.data

import android.content.Context
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-facing channel source providers that can be toggled on/off in Settings.
 * Maps to one or more [com.streamverse.core.domain.model.SourceType]s in the repository.
 */
enum class SourceProvider(
    val displayName: String,
    val description: String,
) {
    DLHD("Live Sports & TV", "Live sports, news & entertainment — 500+ channels"),
    STMIFY("World TV", "Middle East, African & international channels"),
    IPTV("Global Channels", "10,000+ live channels from around the world"),
    FREE_TV("Free-to-Air", "Free broadcast channels worldwide"),
    FAST_TV("Regional Live TV", "Direct live streams across 28 countries"),
    RADIO("Radio", "Live internet radio stations"),
    INDEPENDENT("Featured", "Hand-picked channels verified to work"),
    PREMIUM("Premium TV", "HBO, Showtime, Starz, sports & more premium channels"),
    ;

    companion object {
        /** The user-facing provider that a given [SourceType] belongs to. */
        fun forType(type: SourceType): SourceProvider = when (type) {
            SourceType.DLHD -> DLHD
            SourceType.STMIFY_FREE, SourceType.STMIFY_PREMIUM -> STMIFY
            SourceType.IPTV -> IPTV
            SourceType.FREE_TV -> FREE_TV
            SourceType.FAST_TV -> FAST_TV
            SourceType.RADIO -> RADIO
            SourceType.INDEPENDENT -> INDEPENDENT
            SourceType.PREMIUM -> PREMIUM
        }
    }
}

/**
 * The number of distinct user-facing providers a channel can be watched from. STMIFY_FREE and
 * STMIFY_PREMIUM collapse into one ("World TV"), so this matches the player's source picker rather
 * than a raw [Channel.sources] count — a channel offered by DLHD + Stmify (free & premium) reads as
 * "2 sources", not 3.
 */
fun Channel.sourceProviderCount(): Int =
    sources.keys.mapTo(HashSet()) { SourceProvider.forType(it) }.size

/**
 * Persists which [SourceProvider]s are enabled. All sources default to enabled.
 */
@Singleton
class SourcePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("sv_sources", Context.MODE_PRIVATE)

    private val _enabledFlow = MutableStateFlow(readAll())
    /** Reactive view of enabled providers; emits on every [setEnabled]. */
    val enabledFlow: StateFlow<Map<SourceProvider, Boolean>> = _enabledFlow.asStateFlow()

    fun isEnabled(provider: SourceProvider): Boolean =
        prefs.getBoolean(provider.name, true)

    fun setEnabled(provider: SourceProvider, enabled: Boolean) {
        prefs.edit().putBoolean(provider.name, enabled).apply()
        _enabledFlow.value = readAll()
    }

    fun enabled(): Map<SourceProvider, Boolean> = _enabledFlow.value

    private fun readAll(): Map<SourceProvider, Boolean> =
        SourceProvider.entries.associateWith { prefs.getBoolean(it.name, true) }
}
