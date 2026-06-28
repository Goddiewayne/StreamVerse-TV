package com.streamverse.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

// ── StreamVerse Brand Palette ──────────────────────────────────────────────
val CyberCyan        = Color(0xFF22D3EE)
val CyberCyanDark    = Color(0xFF0891B2)
val CyberCyanDim     = Color(0xFF164E63)
val ElectricViolet   = Color(0xFF818CF8)
val ElectricVioletDk = Color(0xFF4F46E5)
val ElectricVioletDm = Color(0xFF1E1B4B)
val DeepSpace        = Color(0xFF000000)
val SpaceNavy        = Color(0xFF0A0A0A)
val NavyCard         = Color(0xFF1A1A1A)
val SlateBlue        = Color(0xFF2A2A2A)
val LiveGreen        = Color(0xFF4ADE80)
val CoralRed         = Color(0xFFF87171)
val TextPrimary      = Color(0xFFF1F5F9)
val TextSecondary    = Color(0xFF94A3B8)
val White            = Color(0xFFFFFFFF)

private val DarkColorScheme = darkColorScheme(
    primary            = CyberCyan,
    onPrimary          = Color(0xFF001A1F),
    primaryContainer   = CyberCyanDim,
    onPrimaryContainer = CyberCyan,
    secondary          = ElectricViolet,
    onSecondary        = Color(0xFF0A0020),
    secondaryContainer = ElectricVioletDm,
    onSecondaryContainer = ElectricViolet,
    tertiary           = LiveGreen,
    onTertiary         = Color(0xFF001A0C),
    background         = DeepSpace,
    onBackground       = TextPrimary,
    surface            = SpaceNavy,
    onSurface          = TextPrimary,
    surfaceVariant     = NavyCard,
    onSurfaceVariant   = TextSecondary,
    outline            = SlateBlue,
    outlineVariant     = Color(0xFF1E293B),
    error              = CoralRed,
    onError            = Color(0xFF1A0000),
    inverseSurface     = TextPrimary,
    inverseOnSurface   = DeepSpace,
)

// StreamVerse is a cinematic, dark-first streaming product: there is one intentional theme.
// (A light scheme was previously defined but unreachable — every screen renders dark — so it was
// removed to keep a single source of truth.)
@Composable
fun StreamVerseTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = false
            WindowInsetsControllerCompat(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = StreamVerseTypography,
        content = content,
    )
}
