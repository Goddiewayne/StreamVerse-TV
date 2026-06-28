package com.streamverse.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A previously-opened channel, with just enough to render its card and re-open it. */
data class WatchedChannel(val id: String, val name: String, val logoUrl: String?)

/**
 * Persists the user's recently-watched channels (most-recent first) for the Home
 * "Continue Watching" row. Stores minimal card metadata so the row renders instantly on cold
 * start — even before the full catalogue loads, and even offline.
 */
@Singleton
class WatchHistoryPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("sv_watch_history", Context.MODE_PRIVATE)

    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<WatchedChannel>> = _recent.asStateFlow()

    /** Record (or bump to front) a channel the user just opened. */
    fun record(id: String, name: String, logoUrl: String?) {
        if (id.isBlank() || name.isBlank()) return
        val list = _recent.value.toMutableList()
        list.removeAll { it.id == id }
        list.add(0, WatchedChannel(id, name, logoUrl))
        while (list.size > MAX) list.removeAt(list.lastIndex)
        _recent.value = list
        prefs.edit().putString(KEY, list.joinToString(REC) { "${it.id}$FIELD${it.name}$FIELD${it.logoUrl ?: ""}" }).apply()
    }

    fun clear() {
        _recent.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private fun load(): List<WatchedChannel> =
        prefs.getString(KEY, null)?.split(REC)?.mapNotNull { rec ->
            val p = rec.split(FIELD)
            if (p.size >= 2 && p[0].isNotBlank()) {
                WatchedChannel(p[0], p[1], p.getOrNull(2)?.takeIf { it.isNotBlank() })
            } else null
        }?.take(MAX) ?: emptyList()

    private companion object {
        const val KEY = "recent"
        const val REC = "\n"   // record separator
        const val FIELD = "\t" // field separator (ids/names/urls never contain tabs)
        const val MAX = 20
    }
}
