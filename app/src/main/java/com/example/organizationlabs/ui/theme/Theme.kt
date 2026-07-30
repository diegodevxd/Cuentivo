package com.cuentivo.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

private val CuentivoColorScheme = darkColorScheme(
    primary = NeoCyan,
    onPrimary = NeoGraphite,
    primaryContainer = NeoCyan,
    onPrimaryContainer = NeoGraphite,
    secondary = NeoPayment,
    onSecondary = NeoGraphite,
    secondaryContainer = NeoGraphiteHigh,
    onSecondaryContainer = NeoPayment,
    tertiary = NeoViolet,
    onTertiary = NeoGraphite,
    tertiaryContainer = NeoGraphiteHigh,
    onTertiaryContainer = NeoViolet,
    background = NeoGraphite,
    onBackground = NeoText,
    surface = NeoGraphiteRaised,
    onSurface = NeoText,
    surfaceVariant = NeoGraphiteHigh,
    onSurfaceVariant = NeoMuted,
    surfaceContainer = NeoGraphiteRaised,
    surfaceContainerLow = NeoGraphite,
    surfaceContainerLowest = NeoGraphite,
    surfaceContainerHigh = NeoGraphiteHigh,
    surfaceContainerHighest = NeoGraphiteHigh,
    outline = NeoHairline,
    outlineVariant = NeoHairline,
    error = NeoOverdue,
    onError = NeoGraphite,
)

/**
 * A deliberately still near-black application ground. Decorative colour is confined to component
 * bounds, so no full-screen infinite transition consumes battery or distracts from balances.
 */
@Composable
fun CuentivoTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = CuentivoColorScheme, typography = Typography) {
        Box(Modifier.fillMaxSize().background(NeoGraphite)) {
            CompositionLocalProvider(LocalContentColor provides CuentivoColorScheme.onBackground) {
                content()
            }
        }
    }
}
