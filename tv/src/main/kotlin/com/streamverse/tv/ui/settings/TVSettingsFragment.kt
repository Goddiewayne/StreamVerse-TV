package com.streamverse.tv.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.streamverse.core.data.PlaybackPreferences
import com.streamverse.core.data.SourcePreferences
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.repository.ChannelRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * TV-native settings screen using leanback's GuidedStepSupportFragment.
 *
 * Layout: left panel shows title + description ("Guidance"); right panel shows a
 * scrollable list of actions (source provider checkboxes, playback toggles).
 *
 *  Section: Data Sources
 *    ☑ Live Sports & TV     ← each SourceProvider as a checkbox
 *    ☑ World TV
 *    ☑ Global Channels
 *    … (all 9 providers)
 *
 *  Section: Playback
 *    ☐ Keep Screen On
 *    ○ Video: Fit / Fill / Zoom
 *
 *  Section: About
 *    (version info — non-clickable)
 */
@AndroidEntryPoint
class TVSettingsFragment : GuidedStepSupportFragment() {

    @Inject lateinit var sourcePreferences:  SourcePreferences
    @Inject lateinit var playbackPreferences: PlaybackPreferences
    @Inject lateinit var channelRepository:   ChannelRepository

    // ---- Guidance panel (left side) -------------------------------------------------

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            "Settings",
            "Customize your StreamVerse TV experience.\n\nChanges to data sources take effect immediately and reload the channel catalogue.",
            "StreamVerse TV",
            null,
        )

    // ---- Actions list (right side) --------------------------------------------------

    override fun onCreateActions(
        actions: MutableList<GuidedAction>,
        savedInstanceState: Bundle?,
    ) {
        // ── Data Sources section header ──────────────────────────────────────────────
        actions.add(sectionHeader(ID_SOURCES_HEADER, "Data Sources"))

        SourceProvider.entries.forEachIndexed { i, provider ->
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(ID_SOURCE_BASE + i)
                    .title(provider.displayName)
                    .description(provider.description)
                    .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                    .checked(sourcePreferences.isEnabled(provider))
                    .build()
            )
        }

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

    // ---- Interaction ----------------------------------------------------------------

    override fun onGuidedActionClicked(action: GuidedAction) {
        when {
            // Source provider toggle
            action.id in ID_SOURCE_BASE until ID_SOURCE_BASE + SourceProvider.entries.size -> {
                val provider = SourceProvider.entries[(action.id - ID_SOURCE_BASE).toInt()]
                // setEnabled emits on enabledFlow, so the browse/home rows re-filter INSTANTLY
                // (the reactive channels flow is collected by TVBrowseFragment). Enabling also needs
                // a reload to fetch the newly-turned-on provider's catalogue; we reload on every
                // toggle so the home screen always reflects the current source selection — matching
                // the mobile app, which reloads on each toggle too.
                sourcePreferences.setEnabled(provider, action.isChecked)
                lifecycleScope.launch { runCatching { channelRepository.reload() } }
            }

            action.id == ID_KEEP_SCREEN_ON -> {
                playbackPreferences.keepScreenOn = action.isChecked
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

    // ---- Helpers --------------------------------------------------------------------

    private fun sectionHeader(id: Long, title: String): GuidedAction =
        GuidedAction.Builder(requireContext())
            .id(id)
            .title(title)
            .infoOnly(true)
            .build()

    companion object {
        private const val ID_SOURCES_HEADER = 1L
        private const val ID_SOURCE_BASE    = 100L    // 100–108 (9 providers)
        private const val ID_PLAYBACK_HEADER = 200L
        private const val ID_KEEP_SCREEN_ON  = 201L
        private const val ID_VIDEO_FIT       = 202L
        private const val ID_VIDEO_FILL      = 203L
        private const val ID_VIDEO_ZOOM      = 204L
        private const val ID_STATIC_HEADER        = 210L
        private const val ID_STATIC_LOW           = 211L
        private const val ID_STATIC_MEDIUM        = 212L
        private const val ID_STATIC_HIGH          = 213L
        private const val ID_STATIC_CHANNEL_BURST = 214L
        private const val ID_STATIC_AUDIO         = 215L

        private const val ID_ABOUT_HEADER    = 300L
        private const val ID_ABOUT_APP       = 301L

        // Shared checkSetIds for radio groups
        private const val RADIO_RESIZE = 50
        private const val RADIO_STATIC = 51
    }
}
