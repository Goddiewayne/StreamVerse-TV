package com.streamverse.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's search behaviour locally: an ordered list of recent searches and a
 * frequency map used to surface genuinely "popular" searches (most-repeated terms), with no
 * hard-coded placeholder suggestions. Backs the Search screen's Recent + Popular sections.
 */
@Singleton
class SearchHistoryPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("sv_search_history", Context.MODE_PRIVATE)

    private val _recent = MutableStateFlow(loadRecent())
    /** Most-recent-first, de-duplicated, capped at [MAX_RECENT]. */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private val _popular = MutableStateFlow(loadPopular())
    /** Most-searched-first terms (by repeat count), capped at [MAX_POPULAR]. */
    val popular: StateFlow<List<String>> = _popular.asStateFlow()

    /** Record a committed search (a tapped result / submitted query). Ignores trivial input. */
    fun record(term: String) {
        val t = term.trim()
        if (t.length < MIN_LEN) return

        val recent = _recent.value.toMutableList()
        recent.removeAll { it.equals(t, ignoreCase = true) }
        recent.add(0, t)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
        _recent.value = recent
        prefs.edit().putString(KEY_RECENT, recent.joinToString(SEP)).apply()

        val counts = loadCounts()
        val key = counts.keys.firstOrNull { it.equals(t, ignoreCase = true) } ?: t
        counts[key] = (counts[key] ?: 0) + 1
        saveCounts(counts)
        _popular.value = counts.entries.sortedByDescending { it.value }.take(MAX_POPULAR).map { it.key }
    }

    fun clearRecent() {
        _recent.value = emptyList()
        prefs.edit().remove(KEY_RECENT).apply()
    }

    private fun loadRecent(): List<String> =
        prefs.getString(KEY_RECENT, null)?.split(SEP)?.filter { it.isNotBlank() }?.take(MAX_RECENT) ?: emptyList()

    private fun loadPopular(): List<String> =
        loadCounts().entries.sortedByDescending { it.value }.take(MAX_POPULAR).map { it.key }

    private fun loadCounts(): LinkedHashMap<String, Int> {
        val map = LinkedHashMap<String, Int>()
        prefs.getString(KEY_COUNTS, null)?.split(SEP)?.forEach { entry ->
            val i = entry.lastIndexOf(KV)
            if (i > 0) entry.substring(i + 1).toIntOrNull()?.let { map[entry.substring(0, i)] = it }
        }
        return map
    }

    private fun saveCounts(counts: Map<String, Int>) {
        // Keep only the strongest signals so the store can't grow unbounded.
        val trimmed = counts.entries.sortedByDescending { it.value }.take(MAX_COUNTS)
        prefs.edit().putString(KEY_COUNTS, trimmed.joinToString(SEP) { it.key + KV + it.value }).apply()
    }

    private companion object {
        const val KEY_RECENT = "recent"
        const val KEY_COUNTS = "counts"
        const val SEP = "\n"   // record separator (terms never contain newlines)
        const val KV = "\t"    // key/value separator (terms never contain tabs)
        const val MIN_LEN = 2
        const val MAX_RECENT = 10
        const val MAX_POPULAR = 8
        const val MAX_COUNTS = 50
    }
}
