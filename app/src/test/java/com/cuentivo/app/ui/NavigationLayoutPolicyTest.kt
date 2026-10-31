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

    /**
     * Regresión del hallazgo v45: "Nueva venta" quedaba cortada en la barra etiquetada entre 360 y
     * 411 dp. La acción central debe recibir más ancho que cada pestaña, y ese ancho debe alcanzar
     * para la etiqueta más larga.
     */
    @Test fun la_accion_central_recibe_mas_ancho_que_una_pestana() {
        assertTrue(
            NavigationLayoutPolicy.LABELLED_SALE_ACTION_WEIGHT >
                NavigationLayoutPolicy.LABELLED_DESTINATION_WEIGHT,
        )
    }

    @Test fun el_ancho_de_la_accion_central_alcanza_para_la_etiqueta_mas_larga() {
        // El ancho útil de la barra a 360 dp: 360 − 2×10 (inset) − 2×4 (padding) = 332 dp.
        val barWidthDp = 360f - 2 * 10f - 2 * 4f
        val totalWeight = 4 * NavigationLayoutPolicy.LABELLED_DESTINATION_WEIGHT +
            NavigationLayoutPolicy.LABELLED_SALE_ACTION_WEIGHT
        val saleItemWidthDp = barWidthDp * NavigationLayoutPolicy.LABELLED_SALE_ACTION_WEIGHT / totalWeight
        // Menos el padding horizontal del ítem (2 dp por lado).
        val saleLabelWidthDp = saleItemWidthDp - 2 * 2f

        assertTrue(
            "la etiqueta 'Nueva venta' necesita al menos " +
                "${NavigationLayoutPolicy.SALE_LABEL_MIN_WIDTH_DP} dp y recibe $saleLabelWidthDp dp",
            saleLabelWidthDp >= NavigationLayoutPolicy.SALE_LABEL_MIN_WIDTH_DP,
        )
    }
}
