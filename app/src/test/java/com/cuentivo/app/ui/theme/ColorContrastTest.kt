package com.cuentivo.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x accessibility floors over the semantic palettes, checked as pure JVM math on the raw
 * ARGB values so the scheme never ships a control boundary or a text colour that disappears on its
 * own surface:
 *
 * - Control boundaries (outline) against the surfaces they sit on: 3:1, the non-text floor.
 * - Text colours against the surfaces they sit on: 4.5:1, the normal-text floor.
 *
 * Both schemes are checked so the light theme is not an afterthought of the approved dark one.
 */
class ColorContrastTest {

    private val schemes = mapOf(
        "dark" to CuentivoPalette.Dark,
        "light" to CuentivoPalette.Light,
    )

    @Test
    fun controlOutline_meetsNonTextFloor_againstEverySurface() {
        for ((name, scheme) in schemes) {
            for (surface in listOf(
                scheme.background,
                scheme.surface,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
            )) {
                assertContrast(
                    label = "$name outline vs surface $surface",
                    foreground = scheme.outline,
                    background = surface,
                    floor = 3.0,
                )
            }
        }
    }

    @Test
    fun primaryText_meetsTextFloor_againstSurfaces() {
        for ((name, scheme) in schemes) {
            assertContrast("$name onSurface vs surface", scheme.onSurface, scheme.surface, 4.5)
            assertContrast(
                "$name onSurfaceVariant vs surfaceContainer",
                scheme.onSurfaceVariant,
                scheme.surfaceContainer,
                4.5,
            )
            assertContrast("$name onBackground vs background", scheme.onBackground, scheme.background, 4.5)
        }
    }

    @Test
    fun roleLabels_meetTextFloor_againstTheirContainers() {
        for ((name, scheme) in schemes) {
            assertContrast("$name onPrimary vs primary", scheme.onPrimary, scheme.primary, 4.5)
            assertContrast("$name onSecondary vs secondary", scheme.onSecondary, scheme.secondary, 4.5)
            assertContrast("$name onTertiary vs tertiary", scheme.onTertiary, scheme.tertiary, 4.5)
            assertContrast("$name onError vs error", scheme.onError, scheme.error, 4.5)
        }
    }

    private fun assertContrast(label: String, foreground: Long, background: Long, floor: Double) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$label: ratio %.2f:1 below %.1f:1".format(ratio, floor), ratio >= floor)
    }
}

/** WCAG 2.x relative luminance of an opaque sRGB colour packed as 0xFFRRGGBB. */
private fun relativeLuminance(argb: Long): Double {
    fun linear(channel: Long): Double {
        val c = (channel and 0xFF).toDouble() / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    val r = linear(argb shr 16)
    val g = linear(argb shr 8)
    val b = linear(argb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

private fun contrastRatio(a: Long, b: Long): Double {
    val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
    val darker = minOf(relativeLuminance(a), relativeLuminance(b))
    return (lighter + 0.05) / (darker + 0.05)
}
