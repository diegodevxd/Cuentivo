package com.cuentivo.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.cuentivo.app.ui.LocalMotionEnabled
import com.cuentivo.app.ui.rememberMotionEnabled

private fun CuentivoScheme.toCompose(): ColorScheme = darkColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    error = Color(error),
    onError = Color(onError),
)

private fun CuentivoScheme.toLightScheme(): ColorScheme = lightColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    error = Color(error),
    onError = Color(onError),
)

private val DarkColorScheme: ColorScheme = CuentivoPalette.Dark.toCompose()
private val LightColorScheme: ColorScheme = CuentivoPalette.Light.toLightScheme()

/**
 * A deliberately still application ground — near-black graphite in dark, warm pale in light.
 * Decorative colour is confined to component bounds, so no full-screen infinite transition
 * consumes battery or distracts from balances.
 *
 * Dark is the device-approved default; `darkTheme = false` renders the complete light alternative.
 */
@Composable
fun CuentivoTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = scheme, typography = Typography) {
        Box(Modifier.fillMaxSize().background(scheme.background)) {
            CompositionLocalProvider(
                LocalContentColor provides scheme.onBackground,
                // Resolved once here so every primitive (press scale, entrances) honours the
                // system animator setting through the same channel.
                LocalMotionEnabled provides rememberMotionEnabled(),
            ) {
                content()
            }
        }
    }
}
