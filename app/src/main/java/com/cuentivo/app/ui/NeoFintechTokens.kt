package com.cuentivo.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Compact dimensions distilled from the approved Stitch captures.
 *
 * This is the whole spacing scale. A screen that needs a gap not on this list is a screen that is
 * about to invent its own, and enough of those is how an app stops looking like one product.
 */
internal object NeoSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp

    /** Separation between sections of a screen — bigger than the gap between rows inside one. */
    val xxl = 24.dp

    /** Breathing room around something that owns its area, like a total or an empty state. */
    val xxxl = 32.dp
}

/**
 * How far a disabled control fades, in one place, because "disabled" is a system-wide statement.
 *
 * The rule: **a disabled control keeps a visible surface and legible text.** It is saying "not yet",
 * which the person has to be able to read; it is not saying "gone". Material's defaults (12 % fill,
 * 38 % label) were tuned for a light surface and vanish on this app's graphite.
 */
internal object NeoDisabled {
    /** Fill of a primary action. Enough cyan left to still read as the same button. */
    const val SURFACE = 0.22f

    /** Label on any disabled action. Above the 4.5:1 floor against that dimmed fill. */
    const val LABEL = 0.60f

    /** Outline of a secondary action, so the border fades with its label instead of staying lit. */
    const val BORDER = 0.45f
}

internal object NeoShapes {
    val row = RoundedCornerShape(10.dp)
    val card = RoundedCornerShape(14.dp)
    val hero = RoundedCornerShape(18.dp)
}

/**
 * Claymorphism, applied only to the bottom navigation.
 *
 * Clay is soft, thick and rounded, it casts a wide gentle shadow, and a pressed element sinks *into*
 * the material instead of lighting up on top of it. That last part is the whole trick: the selected
 * tab reads as a thumbprint pushed into the bar.
 *
 * Everything here is built from gradients and [androidx.compose.ui.draw.shadow], which draw the same
 * on every Android this app supports. `Modifier.blur` is deliberately not part of the recipe: it is
 * backed by `RenderEffect` and does nothing at all below API 31, and this app ships to `minSdk 24` —
 * an effect that silently vanishes on the older phone a grandparent is likely to be holding is not
 * an effect, it is a bug waiting for a bug report nobody can reproduce.
 */
internal object NeoClay {
    /** Thick and round. Clay does not do tight corners. */
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** The selected tab's own pillow, rounded on all four sides. */
    val tabShape = RoundedCornerShape(18.dp)

    /** Wide and soft rather than tight and dark, which is what separates clay from a drop shadow. */
    val elevation = 14.dp

    /** How far the bar floats from the screen edges so the rounded corners are actually visible. */
    val inset = 10.dp

    /** The sunken tab is drawn a touch darker than the bar, then lit from below. */
    const val PRESSED_TOP_ALPHA = 0.16f
    const val PRESSED_BOTTOM_ALPHA = 0.04f

    /** The lit rim along the top edge that makes the surface look domed instead of flat. */
    const val HIGHLIGHT_ALPHA = 0.10f
}

internal object NeoMotion {
    const val QUICK_MS = 160
    const val STANDARD_MS = 200
    const val PRESSED_SCALE = 0.97f

    /** Screen and sheet entrances. Long enough to be felt, short enough never to be waited on. */
    const val ENTRANCE_MS = 260
}

/**
 * Whether the system allows animations, resolved once by the theme from the animator duration scale
 * and read by every animated primitive — press scaling, navigation entrances — so that disabling
 * animations system-wide disables them everywhere, in one place.
 */
internal val LocalMotionEnabled = staticCompositionLocalOf { true }
