package com.cuentivo.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Glass and neon, without a blur and without a shader.
 *
 * Two constraints shape everything here. `Modifier.blur` needs API 31 and `RuntimeShader` needs API
 * 33, while this app ships to `minSdk 24` — so the depth comes from a live gradient behind the whole
 * app plus translucent container colours. Material's Card, BottomSheet and NavigationBar all read
 * those colours, so the effect lands everywhere at once instead of screen by screen, and it stays
 * cheap on the low-end phones this is for.
 *
 * The light scheme is the real design; see the note on [DeepInk] for why.
 */
private val LightColorScheme = lightColorScheme(
    primary = MintInk,
    onPrimary = Color.White,
    primaryContainer = NeonMint,
    onPrimaryContainer = DeepInk,
    secondary = CyanInk,
    onSecondary = Color.White,
    secondaryContainer = NeonCyan,
    onSecondaryContainer = DeepInk,
    tertiary = MagentaInk,
    onTertiary = Color.White,
    tertiaryContainer = NeonMagenta,
    onTertiaryContainer = DeepInk,
    // Transparent so the animated backdrop shows through the Scaffold.
    background = Color.Transparent,
    onBackground = DeepInk,
    surface = GlassLight,
    onSurface = DeepInk,
    surfaceVariant = GlassLight,
    onSurfaceVariant = SlateInk,
    surfaceContainer = GlassLight,
    // Sheets read surfaceContainerLow and dialogs read surfaceContainerHigh: both near-opaque so the
    // screen behind them stops competing with their text. Cards keep the glass via
    // surfaceContainerHighest.
    surfaceContainerLow = OverlayLight,
    surfaceContainerLowest = GlassLight,
    surfaceContainerHigh = OverlayLight,
    surfaceContainerHighest = GlassLightRaised,
    outline = GlassLightBorder,
    outlineVariant = GlassLightBorder,
    error = AlertInk,
    onError = Color.White,
)

/** The alternative. Here the neon can carry text, because the ground is dark enough. */
private val DarkColorScheme = darkColorScheme(
    primary = NeonMint,
    onPrimary = DeepNavy,
    primaryContainer = MintInk,
    onPrimaryContainer = Color.White,
    secondary = NeonCyan,
    onSecondary = DeepNavy,
    secondaryContainer = CyanInk,
    onSecondaryContainer = Color.White,
    tertiary = NeonMagenta,
    onTertiary = DeepNavy,
    tertiaryContainer = MagentaInk,
    onTertiaryContainer = Color.White,
    background = Color.Transparent,
    onBackground = PaleMist,
    surface = GlassDark,
    onSurface = PaleMist,
    surfaceVariant = GlassDark,
    onSurfaceVariant = Color(0xFFA9BDC6),
    surfaceContainer = GlassDark,
    surfaceContainerLow = OverlayDark,
    surfaceContainerLowest = GlassDark,
    surfaceContainerHigh = OverlayDark,
    surfaceContainerHighest = GlassDarkRaised,
    outline = GlassDarkBorder,
    outlineVariant = GlassDarkBorder,
    error = Color(0xFFFF8A80),
    onError = DeepNavy,
)

@Composable
fun CuentivoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, typography = Typography) {
        Box(Modifier.fillMaxSize().background(animatedBackdrop(darkTheme))) {
            // Without this, text outside a Card or Surface falls back to black and disappears into
            // the backdrop. Painting a background is not enough — the content colour has to be told
            // about it too. This line is load-bearing; removing it has broken the app before.
            CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
                content()
            }
        }
    }
}

/**
 * A slow drifting colour wash — the "alive" part of the design.
 *
 * It sweeps rather than sitting still, on a deliberately long cycle. Twenty seconds is slow enough
 * that nobody sits watching it and fast enough to read as movement when you glance up. A permanent
 * animation on a cheap phone is a real battery and jank cost, so it stays cheap: two interpolated
 * offsets on one linear gradient, with the colour list remembered rather than rebuilt per frame.
 *
 * Honours the system "remove animations" setting. Someone who turned animations off asked for a
 * still screen, and motion sickness is a real reason to have done that.
 */
@Composable
private fun animatedBackdrop(darkTheme: Boolean): Brush {
    val colors = remember(darkTheme) {
        if (darkTheme) listOf(DeepNavy, DeepTeal, DeepPlum, DeepNavy)
        else listOf(CloudWhite, MistBlue, MintHaze, BlushHaze, CloudWhite)
    }

    val context = LocalContext.current
    val animationsOff = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    if (animationsOff) {
        return Brush.linearGradient(colors, start = Offset.Zero, end = Offset(SPAN, SPAN))
    }

    val transition = rememberInfiniteTransition(label = "backdrop")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CYCLE_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shift",
    )
    val travel = SPAN * DRIFT * shift
    return Brush.linearGradient(
        colors = colors,
        start = Offset(-travel, travel),
        end = Offset(SPAN - travel, SPAN + travel),
    )
}

/** Comfortably past any phone's pixel height, so the gradient never tiles mid-screen. */
private const val SPAN = 2600f
private const val DRIFT = 0.35f
private const val CYCLE_MILLIS = 20_000
