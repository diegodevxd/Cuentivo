package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.ledger.LedgerRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LedgerCropGeometryTest {

    @Test
    fun `pixel rect maps a normalized region`() {
        val rect = LedgerCropGeometry.pixelRect(
            LedgerRegion(0.1, 0.2, 0.5, 0.5),
            imageWidth = 1000,
            imageHeight = 1000,
        )
        assertEquals(LedgerCropRect(100, 200, 600, 700), rect)
    }

    @Test
    fun `pixel rect keeps one pixel for a zero sized region`() {
        val rect = LedgerCropGeometry.pixelRect(
            LedgerRegion(0.5, 0.5, 0.0, 0.0),
            imageWidth = 1000,
            imageHeight = 1000,
        )
        assertEquals(1, rect?.width)
        assertEquals(1, rect?.height)
    }

    @Test
    fun `pixel rect clips a region that leaves the image`() {
        val rect = LedgerCropGeometry.pixelRect(
            LedgerRegion(0.9, 0.9, 0.5, 0.5),
            imageWidth = 1000,
            imageHeight = 1000,
        )
        assertEquals(LedgerCropRect(900, 900, 1000, 1000), rect)
    }

    @Test
    fun `pixel rect returns null for an empty image`() {
        assertNull(
            LedgerCropGeometry.pixelRect(
                LedgerRegion(0.1, 0.2, 0.5, 0.5),
                imageWidth = 0,
                imageHeight = 1000,
            ),
        )
    }
}
