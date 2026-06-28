package com.streamverse.core.data

import android.content.Context
import com.streamverse.core.domain.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight per-channel source health memory. Remembers which [SourceType] last actually started
 * playing for a given channel so the player can open that source FIRST next time instead of blindly
 * starting at priority #1 — cutting "dead on arrival" launches without any blocking network probe.
 *
 * This is the persistent, zero-cost layer of source selection; live cross-source failover at launch
 * time is still handled by the player's watchdog. A fuller networked Channel Health Engine (latency
 * scoring, geo-aware probing, temporary blacklisting) can build on top of this same store.
 */
@Singleton
class SourceHealthPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("sv_source_health", Context.MODE_PRIVATE)

    /** The source that last successfully played this channel, if still known. */
    fun lastGoodSource(channelId: String): SourceType? =
        prefs.getString(channelId, null)?.let { name ->
            runCatching { SourceType.valueOf(name) }.getOrNull()
        }

    /** Record that [type] successfully started playing [channelId] (no-op if already recorded). */
    fun recordSuccess(channelId: String, type: SourceType) {
        if (prefs.getString(channelId, null) == type.name) return
        prefs.edit().putString(channelId, type.name).apply()
    }

    /** Forget [type] as the preferred source for [channelId] if it was the remembered one. */
    fun recordFailure(channelId: String, type: SourceType) {
        if (prefs.getString(channelId, null) == type.name) {
            prefs.edit().remove(channelId).apply()
        }
    }
}
