package com.cuentivo.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLayoutPolicyTest {
    @Test fun labelled_layout_is_used_when_width_and_font_scale_are_comfortable() {
        assertEquals(NavigationLayoutMode.LABELLED, NavigationLayoutPolicy.modeFor(411.dp, 1f))
    }

    @Test fun compact_layout_is_used_on_a_narrow_phone() {
        assertEquals(NavigationLayoutMode.COMPACT, NavigationLayoutPolicy.modeFor(320.dp, 1f))
    }

    @Test fun compact_layout_is_used_for_large_system_text() {
        assertEquals(NavigationLayoutMode.COMPACT, NavigationLayoutPolicy.modeFor(411.dp, 1.3f))
    }

    @Test fun compact_mode_selects_the_compact_clearance() {
        val mode = NavigationLayoutPolicy.modeFor(411.dp, 1.3f)

        assertEquals(NavigationLayoutMode.COMPACT, mode)
        assertEquals(128.dp, NavigationLayoutPolicy.bottomBarClearanceFor(mode))
    }

    @Test fun labelled_mode_selects_the_labelled_clearance() {
        val mode = NavigationLayoutPolicy.modeFor(411.dp, 1f)

        assertEquals(NavigationLayoutMode.LABELLED, mode)
        assertEquals(104.dp, NavigationLayoutPolicy.bottomBarClearanceFor(mode))
    }

    @Test fun compact_clearance_reserves_the_measured_compact_bar_footprint() {
        // The compact column — 56 dp action above the 56 dp destination row plus its padding —
        // measures about 120 dp once large text grows the labels (verified on the API 37 AVD).
        // The reserved clearance must clear that taller bar.
        assertTrue(NavigationLayoutPolicy.bottomBarClearanceFor(NavigationLayoutMode.COMPACT) >= 120.dp)
    }
}
