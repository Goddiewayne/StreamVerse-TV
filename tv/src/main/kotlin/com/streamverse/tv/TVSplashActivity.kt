package com.streamverse.tv

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.streamverse.core.data.repository.ChannelRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Branded launch screen shown on both Android TV and Amazon FireTV while the
 * channel catalogue warms up from cache or network.
 *
 * Animation sequence (total ≈ 1.6 s before channels are ready):
 *   0 ms  — logo scales in from 0.5× + fades in (600 ms, OvershootInterpolator)
 *   350 ms — "StreamVerse" name fades in (400 ms)
 *   600 ms — "TV" tag fades in (300 ms)
 *   900 ms — loading bar pulses in (300 ms)
 *
 * Transitions to [MainActivity] with a 300 ms crossfade once:
 *   • channels.first { it.isNotEmpty() } resolves, OR
 *   • SPLASH_TIMEOUT_MS elapses (safety valve for offline launch)
 */
@AndroidEntryPoint
class TVSplashActivity : FragmentActivity() {

    @Inject lateinit var channelRepository: ChannelRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_splash)

        val logo    = findViewById<ImageView>(R.id.splash_logo)
        val name    = findViewById<TextView>(R.id.splash_name)
        val tag     = findViewById<TextView>(R.id.splash_tagline)
        val bar     = findViewById<View>(R.id.splash_loading_bar)

        // ── Logo entrance: scale 0.5 → 1.0 with overshoot + fade ────────────────
        logo.scaleX = 0.5f
        logo.scaleY = 0.5f
        logo.alpha  = 0f

        val logoScale = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, "scaleX", 0.5f, 1f),
                ObjectAnimator.ofFloat(logo, "scaleY", 0.5f, 1f),
                ObjectAnimator.ofFloat(logo, "alpha",  0f,   1f),
            )
            duration    = 600
            interpolator = OvershootInterpolator(1.4f)
        }

        // ── Text cascade: name, then tag ─────────────────────────────────────────
        val nameAnim = ObjectAnimator.ofFloat(name, "alpha", 0f, 1f).apply {
            duration   = 400
            startDelay = 350
        }
        val tagAnim = ObjectAnimator.ofFloat(tag, "alpha", 0f, 1f).apply {
            duration   = 300
            startDelay = 600
        }
        val barAnim = ObjectAnimator.ofFloat(bar, "alpha", 0f, 0.6f).apply {
            duration   = 300
            startDelay = 900
        }

        logoScale.start()
        nameAnim.start()
        tagAnim.start()
        barAnim.start()

        // ── Start loading data, then wait for first batch of channels ────────────
        lifecycleScope.launch {
            withContext(NonCancellable) {
                channelRepository.load()
            }
        }
        lifecycleScope.launch {
            withTimeoutOrNull(SPLASH_TIMEOUT_MS) {
                channelRepository.channels.first { it.isNotEmpty() }
            }
            launchBrowse()
        }
    }

    private fun launchBrowse() {
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        // Allow up to 20 s for phase 1 (DLHD scrape can take 15-30 s with OkHttp timeouts).
        // Once channels arrive (or timeout), the browse screen takes over and shows
        // loading placeholders while phase 2 finishes in the background.
        private const val SPLASH_TIMEOUT_MS = 20_000L
    }
}
