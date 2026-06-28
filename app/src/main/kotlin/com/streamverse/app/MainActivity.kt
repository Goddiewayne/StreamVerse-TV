package com.streamverse.app

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.streamverse.app.ui.player.PipController
import com.streamverse.app.ui.theme.StreamVerseTheme
import com.streamverse.core.data.repository.ChannelRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        @Volatile private var handled = false
        // Don't hold the splash forever if the network/cache is slow — show the (skeleton) UI.
        private const val SPLASH_MAX_HOLD_MS = 6_000L
    }

    @Inject lateinit var channelRepository: ChannelRepository
    @Volatile private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep the branded splash visible until Home Phase 1 has channels to show (cache or
        // network), so the user never sees an empty Home or a white/black flash. Bounded by a
        // safety timeout so a slow/offline start still reveals the UI.
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !contentReady }
        // Cross-fade the splash into Home instead of a hard cut.
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .setDuration(250L)
                .withEndAction { provider.remove() }
                .start()
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            withTimeoutOrNull(SPLASH_MAX_HOLD_MS) {
                channelRepository.channels.first { it.isNotEmpty() }
            }
            contentReady = true
        }
        val channelFromExtra = intent?.getStringExtra("channelId")
        val channelFromLink = intent?.data?.lastPathSegment
        val initialChannelId = channelFromExtra ?: channelFromLink
        android.util.Log.d("MainActivity", "onCreate: intent=$intent channelFromExtra=$channelFromExtra channelFromLink=$channelFromLink initialChannelId=$initialChannelId handled=$handled")
        setContent {
            StreamVerseTheme {
                StreamVerseNavGraph(
                    initialChannelId = if (handled) null else initialChannelId,
                    onNavigated = { handled = true },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val ch = intent.data?.lastPathSegment
        android.util.Log.d("MainActivity", "onNewIntent: data=${intent.data} lastPathSegment=$ch")
    }

    /** Pressing Home while a video is playing shrinks it into a floating PiP window that keeps
     *  playing — instead of stopping playback. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!PipController.eligible) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipController.setInPip(isInPictureInPictureMode)
    }
}
