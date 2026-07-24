package com.cuentivo.app.ui.theme

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Glass, without a blur.
 *
 * The effect comes from two decisions rather than an expensive backdrop filter: the whole app sits
 * on a gradient, and every container colour in the scheme is translucent white. Material's Card,
 * BottomSheet and NavigationBar all read those colours, so the frosted look applies everywhere at
 * once instead of screen by screen — and it stays cheap on the low-end phones this app targets
 * (minSdk 24, where Modifier.blur does not exist).
 */
private val DarkColorScheme = darkColorScheme(
    primary = MintAccent,
    onPrimary = DeepNavy,
    secondary = SkyAccent,
    onSecondary = DeepNavy,
    tertiary = WarmSand,
    onTertiary = DeepNavy,
    // Transparent so the gradient painted below shows through the Scaffold.
    background = Color.Transparent,
    onBackground = Color(0xFFEAF2F5),
    surface = GlassDark,
    onSurface = Color(0xFFEAF2F5),
    surfaceVariant = GlassDark,
    onSurfaceVariant = Color(0xFFA9BDC6),
    surfaceContainer = GlassDark,
    // Bottom sheets read surfaceContainerLow and dialogs read surfaceContainerHigh: both are
    // near-opaque so the screen behind them stops competing with their text. Cards keep the glass
    // look through surfaceContainerHighest.
    surfaceContainerLow = OverlayDark,
    surfaceContainerLowest = GlassDark,
    surfaceContainerHigh = OverlayDark,
    surfaceContainerHighest = GlassDarkRaised,
    outline = GlassDarkBorder,
    outlineVariant = GlassDarkBorder,
    error = Color(0xFFFF8A80),
    onError = DeepNavy,
)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimaryLight,
    onPrimary = Color.White,
    secondary = BlueSecondaryLight,
    onSecondary = Color.White,
    tertiary = Color(0xFF9A6700),
    background = Color.Transparent,
    onBackground = DeepInk,
    surface = GlassLight,
    onSurface = DeepInk,
    surfaceVariant = GlassLight,
    onSurfaceVariant = Color(0xFF48606B),
    surfaceContainer = GlassLight,
    surfaceContainerLow = OverlayLight,
    surfaceContainerLowest = GlassLight,
    surfaceContainerHigh = OverlayLight,
    surfaceContainerHighest = GlassLightRaised,
    outline = GlassLightBorder,
    outlineVariant = GlassLightBorder,
)

@Composable
fun CuentivoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val backdrop = if (darkTheme) {
        Brush.linearGradient(listOf(DeepNavy, DeepTeal, DeepNavy))
    } else {
        Brush.linearGradient(listOf(PaleMist, Color.White, Color(0xFFDDEBF2)))
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography) {
        // One gradient behind everything, so every screen — including the sign-in view — gets it.
        Box(Modifier.fillMaxSize().background(backdrop)) {
            // Without this, text outside a Card or Surface falls back to black and disappears into
            // the dark gradient. Painting a background is not enough — the content colour has to be
            // told about it too.
            CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
                content()
            }
        }
    }
}
