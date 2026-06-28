package com.streamverse.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.streamverse.core.ui.SignalMessage
import com.streamverse.core.ui.TvStaticAudio
import com.streamverse.core.ui.TvStaticView

/**
 * The shared analogue TV static engine ([TvStaticView]) bridged into Compose. The same renderer
 * powers the View-based TV app, so there is exactly one snow implementation in the codebase.
 *
 * Fills its parent; respects whatever aspect/letterboxing the caller's Box gives it. Optionally
 * mixes in a faint static hiss, started/stopped with the composable's lifecycle so it never leaks
 * or outlives the screen.
 */
@Composable
fun TvStatic(
    modifier: Modifier = Modifier,
    intensity: TvStaticView.Intensity = TvStaticView.Intensity.MEDIUM,
    audioEnabled: Boolean = false,
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx -> TvStaticView(ctx).apply { this.intensity = intensity } },
        update = { it.intensity = intensity },
        modifier = modifier.fillMaxSize(),
    )

    if (audioEnabled) {
        DisposableEffect(Unit) {
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE)
                as? android.media.AudioManager
            val audio = TvStaticAudio()
            // Only bother if the media stream is actually audible right now.
            if (TvStaticAudio.isMediaAudible(audioManager)) audio.start()
            onDispose { audio.stop() }
        }
    }
}

/**
 * A full no-signal screen: TV static, a darkening vignette for legibility, and an elegant OSD that
 * fades in then recedes after a few seconds — like a television that has lost its broadcast. The
 * whole surface is clickable to retry, so the player stays responsive while snow is shown.
 *
 * Recovery is handled by the caller crossfading this away as the video fades in.
 */
@Composable
fun SignalLossOverlay(
    message: SignalMessage,
    modifier: Modifier = Modifier,
    intensity: TvStaticView.Intensity = TvStaticView.Intensity.MEDIUM,
    audioEnabled: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    // OSD choreography: fade in over ~700 ms, hold, then ease back to a faint resting alpha so the
    // snow takes over — never a hard cut, never permanently dominating the frame.
    var osdAlpha by remember(message) { mutableStateOf(0f) }
    val animatedOsd by animateFloatAsState(
        targetValue = osdAlpha,
        animationSpec = tween(durationMillis = 700, easing = LinearEasing),
        label = "osd",
    )
    LaunchedEffect(message) {
        osdAlpha = 1f
        kotlinx.coroutines.delay(4500)
        osdAlpha = 0.12f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (onRetry != null) Modifier.semantics {
                    contentDescription = "${message.headline}. ${message.detail} Tap to retry."
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        TvStatic(intensity = intensity, audioEnabled = audioEnabled)

        // Vignette: keeps the OSD readable over bright snow without a heavy scrim.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                        radius = 900f,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .alpha(animatedOsd)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Text(
                text = message.headline.uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Light,
                fontSize = 30.sp,
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(10.dp))
            androidx.compose.material3.Text(
                text = message.detail,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
            )
        }
    }

    // The clickable retry layer sits above the snow but, with no ripple, taps feel like a TV
    // remote rather than a button press.
    if (onRetry != null) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onRetry,
                ),
        )
    }
}
