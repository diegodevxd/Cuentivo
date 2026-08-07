package com.cuentivo.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Compact dimensions distilled from the approved Stitch captures. */
internal object NeoSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
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
}
