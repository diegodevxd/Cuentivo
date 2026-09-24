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

    /**
     * Cuánto ancho relativo recibe cada ítem de la barra etiquetada (v45).
     *
     * El defecto que cierra: "Nueva venta" es la etiqueta más larga de la barra y, repartida a
     * partes iguales entre cinco ítems, quedaba cortada ("\ueva", la N mocha) en el rango de 360 a
     * 411 dp. La v43 la mostraba completa porque el ítem central no competía con cuatro etiquetas
     * del mismo ancho.
     *
     * La acción central recibe una porción mayor que las cuatro pestañas: su etiqueta es la única
     * que no cabe en el ancho de una pestaña, y las pestañas —Inicio, Clientes, Productos, Abonos—
     * son más cortas. El reparto se expone aquí, puro y sin Compose, para que una prueba JVM lo
     * compruebe sin emulador.
     */
    const val LABELLED_DESTINATION_WEIGHT = 1f
    const val LABELLED_SALE_ACTION_WEIGHT = 1.4f

    /**
     * El ancho mínimo que necesita la etiqueta "Nueva venta" a `labelSmall` (11 sp) para no
     * truncarse, con un margen. Se usa en la prueba de regresión; el layout real lo resuelve el
     * peso de arriba.
     */
    const val SALE_LABEL_MIN_WIDTH_DP = 74
}
