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

    /**
     * Bottom room a scrolling screen must leave so the navigation bar never covers its last row,
     * sized for the layout mode in effect.
     *
     * One policy used by every scrolling screen. Screens used to pick their own margin — 16 dp on
     * Inicio, 20 dp on Productos, 88 dp on Clientes, 96 dp on Nueva venta and Abonos — so the same
     * bar covered the last row on two of them and the bug came back with every screen added. The
     * labelled bar is 68 dp tall; the compact bar stacks the 56 dp action above the 56 dp
     * destination row and measures about 120 dp once large text grows its labels. Each value
     * reserves its bar plus the system-inset and breathing-room margin the old fixed constant
     * carried, so the compact clearance always clears the taller bar.
     */
    fun bottomBarClearanceFor(mode: NavigationLayoutMode): Dp = when (mode) {
        NavigationLayoutMode.LABELLED -> 104.dp
        NavigationLayoutMode.COMPACT -> 128.dp
    }
}
