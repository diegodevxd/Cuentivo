package com.cuentivo.app.ui.theme

/**
 * The complete semantic palettes as raw ARGB (0xFFRRGGBB) values, in one pure-Kotlin place with no
 * Compose dependency so the accessibility floors can be asserted as plain JVM math.
 *
 * `Dark` is the device-approved graphite ground: cyan primary, payment green secondary, hairline
 * borders. `Light` is the warm pale alternative: white surfaces, soft near-black text and an
 * accessible teal primary. Values were chosen so `outline` (control boundaries) keeps at least 3:1
 * against every surface it sits on and every text role keeps at least 4.5:1 against its container —
 * see `ColorContrastTest`.
 */
internal data class CuentivoScheme(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
    val outline: Long,
    val outlineVariant: Long,
    val error: Long,
    val onError: Long,
    /** Status colour outside the Material roles: "pays, but late". */
    val warning: Long,
)

internal object CuentivoPalette {

    val Dark = CuentivoScheme(
        primary = 0xFF18D8E6,
        onPrimary = 0xFF0D0F11,
        primaryContainer = 0xFF18D8E6,
        onPrimaryContainer = 0xFF0D0F11,
        secondary = 0xFFB7FF35,
        onSecondary = 0xFF0D0F11,
        secondaryContainer = 0xFF232629,
        onSecondaryContainer = 0xFFB7FF35,
        tertiary = 0xFFA98BFA,
        onTertiary = 0xFF0D0F11,
        tertiaryContainer = 0xFF232629,
        onTertiaryContainer = 0xFFA98BFA,
        background = 0xFF0D0F11,
        onBackground = 0xFFF2F5F4,
        surface = 0xFF1B1D1F,
        onSurface = 0xFFF2F5F4,
        surfaceVariant = 0xFF232629,
        onSurfaceVariant = 0xFFA9B0B4,
        surfaceContainerLowest = 0xFF0D0F11,
        surfaceContainerLow = 0xFF0D0F11,
        surfaceContainer = 0xFF1B1D1F,
        surfaceContainerHigh = 0xFF232629,
        surfaceContainerHighest = 0xFF232629,
        // Brighter than the hairline so a control boundary keeps 3:1 on the graphite ground.
        outline = 0xFF6E747C,
        outlineVariant = 0xFF303438,
        error = 0xFFFF7B82,
        onError = 0xFF0D0F11,
        warning = 0xFFFFC65C,
    )

    val Light = CuentivoScheme(
        primary = 0xFF006B69,
        onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFF9FF2EF,
        onPrimaryContainer = 0xFF00201F,
        secondary = 0xFF33691E,
        onSecondary = 0xFFFFFFFF,
        secondaryContainer = 0xFFC9F0B8,
        onSecondaryContainer = 0xFF162100,
        tertiary = 0xFF6B4FA0,
        onTertiary = 0xFFFFFFFF,
        tertiaryContainer = 0xFFEADDFF,
        onTertiaryContainer = 0xFF24005B,
        background = 0xFFF6F5F2,
        onBackground = 0xFF1E1D1B,
        surface = 0xFFFFFFFF,
        onSurface = 0xFF1E1D1B,
        surfaceVariant = 0xFFE7E3DD,
        onSurfaceVariant = 0xFF5D5A55,
        surfaceContainerLowest = 0xFFFFFFFF,
        surfaceContainerLow = 0xFFF6F5F2,
        surfaceContainer = 0xFFF0EFEB,
        surfaceContainerHigh = 0xFFE8E6E0,
        surfaceContainerHighest = 0xFFE1DED8,
        outline = 0xFF74777F,
        outlineVariant = 0xFFC9C5BF,
        error = 0xFFBA1A1A,
        onError = 0xFFFFFFFF,
        warning = 0xFF7A5900,
    )
}
