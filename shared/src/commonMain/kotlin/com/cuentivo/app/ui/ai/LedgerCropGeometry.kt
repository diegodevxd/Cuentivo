package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.ledger.LedgerRegion

data class LedgerCropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object LedgerCropGeometry {
    const val MAX_CROP_EDGE_PIXELS = 1600

    fun pixelRect(region: LedgerRegion, imageWidth: Int, imageHeight: Int): LedgerCropRect? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val left = (region.x * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val top = (region.y * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val right = ((region.x + region.width) * imageWidth).toInt().coerceIn(left + 1, imageWidth)
        val bottom = ((region.y + region.height) * imageHeight).toInt().coerceIn(top + 1, imageHeight)
        return LedgerCropRect(left, top, right, bottom)
    }
}
