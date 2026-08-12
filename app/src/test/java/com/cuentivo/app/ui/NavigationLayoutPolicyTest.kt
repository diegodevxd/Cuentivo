package com.cuentivo.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
}
