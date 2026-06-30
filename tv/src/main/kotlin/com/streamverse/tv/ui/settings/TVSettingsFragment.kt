package com.streamverse.tv.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.streamverse.core.data.CacheTier
import com.streamverse.core.data.PlaybackPreferences
import com.streamverse.core.data.SmartCacheManager
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.repository.ChannelRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TVSettingsFragment : GuidedStepSupportFragment() {

    @Inject lateinit var sourcePreferences:  SourcePreferences
    @Inject lateinit var playbackPreferences: PlaybackPreferences
    @Inject lateinit var channelRepository:   ChannelRepository
    @Inject lateinit var smartCacheManager:   SmartCacheManager

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            "Settings",
            "Customize your StreamVerse TV experience.\n\nChanges to data sources take effect immediately and reload the channel catalogue.",
            "StreamVerse TV",
            null,
        )

    override fun onCreateActions(
        actions: MutableList<GuidedAction>,
        savedInstanceState: Bundle?,
    ) {
        // ── Data Sources section header ──────────────────────────────────────────────
        actions.add(sectionHeader(ID_SOURCES_HEADER, "Data Sources"))

        SourceProvider.entries.forEachIndexed { i, provider ->
            val enabled = sourcePreferences.isEnabled(provider)
            val count = countChannelsFor(provider)
            val countLabel = formatChannelCount(count)
            val desc = buildString {
                append(provider.description)
                if (!enabled) append(" (disabled)")
                else append(" — $countLabel channels")
            }
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(ID_SOURCE_BASE + i)
                    .title(provider.displayName)
                    .description(desc)
                    .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                    .checked(enabled)
                    .build()
            )
        }

        // ── Cache management section ────────────────────────────────────────────────
        actions.add(sectionHeader(ID_CACHE_HEADER, "Cache"))

        for (tier in CacheTier.entries) {
            val size = smartCacheManager.tierSizeBytes(tier)
            val sizeLabel = formatBytes(size)
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(ID_CACHE_BASE + tier.ordinal)
                    .title("${tier.label}")
                    .description("$sizeLabel — tap to clear")
                    .build()
            )
        }

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_CACHE_CLEAR_ALL)
                .title("Clear All Cache")
                .description("Remove all cached data — next launch re-downloads everything")
                .build()
        )

        // ── Playback section header ──────────────────────────────────────────────────
        actions.add(sectionHeader(ID_PLAYBACK_HEADER, "Playback"))

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_KEEP_SCREEN_ON)
                .title("Keep Screen On")
                .description("Prevent display from sleeping during live streams")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(playbackPreferences.keepScreenOn)
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_DATA_SAVER)
                .title("Data Saver")
                .description("Reduce data usage: force low bitrate, cap pre‑buffer, skip preloading")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(sourcePreferences.isDataSaverEnabled())
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_VIDEO_FIT)
                .title("Video: Fit")
                .description("Letterbox/pillarbox — full video visible, may have bars")
                .checkSetId(RADIO_RESIZE)
                .checked(playbackPreferences.resizeMode.name == "FIT")
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_VIDEO_FILL)
                .title("Video: Fill")
                .description("Stretch to fill screen — may distort aspect ratio")
                .checkSetId(RADIO_RESIZE)
                .checked(playbackPreferences.resizeMode.name == "FILL")
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_VIDEO_ZOOM)
                .title("Video: Zoom")
                .description("Crop and zoom to fill screen — edges may be clipped")
                .checkSetId(RADIO_RESIZE)
                .checked(playbackPreferences.resizeMode.name == "ZOOM")
                .build()
        )

        // ── No-Signal Screen section ──────────────────────────────────────────────────
        actions.add(sectionHeader(ID_STATIC_HEADER, "No-Signal Screen"))

        val currentIntensity = playbackPreferences.staticIntensity
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_STATIC_LOW)
                .title("Low")
                .description("Calm static — low power, ideal for weak hardware")
                .checkSetId(RADIO_STATIC)
                .checked(currentIntensity.equals("low", ignoreCase = true))
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_STATIC_MEDIUM)
                .title("Medium")
                .description("Standard analogue-snow effect")
                .checkSetId(RADIO_STATIC)
                .checked(currentIntensity.equals("medium", ignoreCase = true))
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_STATIC_HIGH)
                .title("High")
                .description("Dense, intense snow — full retro feel")
                .checkSetId(RADIO_STATIC)
                .checked(currentIntensity.equals("high", ignoreCase = true))
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_STATIC_CHANNEL_BURST)
                .title("Tuning Static")
                .description("Brief snow burst when switching channels")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(playbackPreferences.staticChannelBurst)
                .build()
        )

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_STATIC_AUDIO)
                .title("Static Sound")
                .description("Faint analogue hiss under the static")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(playbackPreferences.staticAudio)
                .build()
        )

        // ── About section ────────────────────────────────────────────────────────────
        actions.add(sectionHeader(ID_ABOUT_HEADER, "About"))

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_ABOUT_APP)
                .title("StreamVerse TV")
                .description("Built by Captain Global Technologies")
                .infoOnly(true)
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when {
            // Source provider toggle
            action.id in ID_SOURCE_BASE until ID_SOURCE_BASE + SourceProvider.entries.size -> {
                val provider = SourceProvider.entries[(action.id - ID_SOURCE_BASE).toInt()]
                sourcePreferences.setEnabled(provider, action.isChecked)
                rebuildActions()
            }

            // Cache tier clear
            action.id in ID_CACHE_BASE until ID_CACHE_BASE + CacheTier.entries.size -> {
                val tier = CacheTier.entries[(action.id - ID_CACHE_BASE).toInt()]
                smartCacheManager.evict(tier)
                rebuildActions()
            }

            action.id == ID_CACHE_CLEAR_ALL -> {
                smartCacheManager.evictAll()
                rebuildActions()
            }

            action.id == ID_KEEP_SCREEN_ON -> {
                playbackPreferences.keepScreenOn = action.isChecked
            }

            action.id == ID_DATA_SAVER -> {
                sourcePreferences.setDataSaverEnabled(action.isChecked)
            }

            action.id == ID_VIDEO_FIT -> {
                playbackPreferences.resizeMode = com.streamverse.core.data.VideoResizeMode.FIT
            }
            action.id == ID_VIDEO_FILL -> {
                playbackPreferences.resizeMode = com.streamverse.core.data.VideoResizeMode.FILL
            }
            action.id == ID_VIDEO_ZOOM -> {
                playbackPreferences.resizeMode = com.streamverse.core.data.VideoResizeMode.ZOOM
            }

            action.id == ID_STATIC_LOW -> {
                playbackPreferences.staticIntensity = "low"
            }
            action.id == ID_STATIC_MEDIUM -> {
                playbackPreferences.staticIntensity = "medium"
            }
            action.id == ID_STATIC_HIGH -> {
                playbackPreferences.staticIntensity = "high"
            }

            action.id == ID_STATIC_CHANNEL_BURST -> {
                playbackPreferences.staticChannelBurst = action.isChecked
            }

            action.id == ID_STATIC_AUDIO -> {
                playbackPreferences.staticAudio = action.isChecked
            }
        }
    }

    private fun rebuildActions() {
        val actions = mutableListOf<GuidedAction>()
        onCreateActions(actions, null)
        setActions(actions)
    }

    private fun sectionHeader(id: Long, title: String): GuidedAction =
        GuidedAction.Builder(requireContext())
            .id(id)
            .title(title)
            .infoOnly(true)
            .build()

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun countChannelsFor(provider: SourceProvider): Int {
        val all = channelRepository.getAllChannels()
        return all.count { ch -> ch.sources.keys.any { SourceProvider.forType(it) == provider } }
    }

    private fun formatChannelCount(count: Int): String = when {
        count >= 10_000 -> "%.1fk".format(count / 1_000.0)
        count >= 1_000 -> "%.1fk".format(count / 1_000.0)
        else -> "$count"
    }

    companion object {
        private const val ID_SOURCES_HEADER = 1L
        private const val ID_SOURCE_BASE    = 100L    // 100–108 (9 providers)
        private const val ID_CACHE_HEADER   = 150L
        private const val ID_CACHE_BASE     = 151L   // 151–153 (3 tiers)
        private const val ID_CACHE_CLEAR_ALL = 154L
        private const val ID_PLAYBACK_HEADER = 200L
        private const val ID_KEEP_SCREEN_ON  = 201L
        private const val ID_DATA_SAVER      = 205L
        private const val ID_VIDEO_FIT       = 202L
        private const val ID_VIDEO_FILL       = 203L
        private const val ID_VIDEO_ZOOM       = 204L
        private const val ID_STATIC_HEADER        = 210L
        private const val ID_STATIC_LOW           = 211L
        private const val ID_STATIC_MEDIUM        = 212L
        private const val ID_STATIC_HIGH          = 213L
        private const val ID_STATIC_CHANNEL_BURST = 214L
        private const val ID_STATIC_AUDIO         = 215L

        private const val ID_ABOUT_HEADER    = 300L
        private const val ID_ABOUT_APP       = 301L

        private const val RADIO_RESIZE = 50
        private const val RADIO_STATIC = 51
    }
}
