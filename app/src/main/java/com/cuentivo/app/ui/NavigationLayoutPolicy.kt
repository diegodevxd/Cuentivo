package com.cuentivo.app.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class NavigationLayoutMode { LABELLED, COMPACT }

object NavigationLayoutPolicy {
    private val compactWidthThreshold = 360.dp
    private const val compactFontScaleThreshold = 1.3f

    fun modeFor(availableWidth: Dp, fontScale: Float): NavigationLayoutMode =
        if (availableWidth < compactWidthThreshold || fontScale >= compactFontScaleThreshold) {
            NavigationLayoutMode.COMPACT
        } else {
            NavigationLayoutMode.LABELLED
        }
}
