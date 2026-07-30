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

internal object NeoMotion {
    const val QUICK_MS = 160
    const val STANDARD_MS = 200
    const val PRESSED_SCALE = 0.97f
}
