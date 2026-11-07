package com.cuentivo.app.ui.ai

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.cuentivo.app.ai.ledger.LedgerRegion
import java.io.File
import java.io.InputStream

/**
 * Pure Android graphics helper to decode thumbnail crops safely from local image files.
 * Zero network, zero Room, zero database dependencies.
 */
object LedgerCropHelper {

    /** Longest edge, in pixels, a row crop is decoded at. Enough to read handwriting, far from OOM. */
    const val MAX_CROP_EDGE_PIXELS = LedgerCropGeometry.MAX_CROP_EDGE_PIXELS

    /**
     * Decodes a cropped thumbnail for a given [region] from [file].
     * Returns null if [file] does not exist, [region] is null, or decoding fails.
     */
    fun decodeCrop(file: File?, region: LedgerRegion?): Bitmap? {
        if (file == null || !file.exists() || !file.canRead() || region == null) return null
        return runCatching {
            file.inputStream().use { stream ->
                decodeCropFromStream(stream, region)
            }
        }.getOrNull()
    }

    /**
     * Decodes a cropped bitmap for a normalized [region] from an [InputStream].
     */
    fun decodeCropFromStream(stream: InputStream, region: LedgerRegion?): Bitmap? {
        if (region == null) return null
        return runCatching {
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            } ?: return null

            val imageWidth = decoder.width
            val imageHeight = decoder.height
            val crop = LedgerCropGeometry.pixelRect(region, imageWidth, imageHeight)
            if (crop == null || crop.width <= 0 || crop.height <= 0) {
                decoder.recycle()
                return null
            }

            val rect = Rect(crop.left, crop.top, crop.right, crop.bottom)
            if (rect.width() <= 0 || rect.height() <= 0) {
                decoder.recycle()
                return null
            }

            // A whole notebook line at full sensor resolution is megabytes of bitmap for a
            // thumbnail a few hundred pixels wide. Sample down to something that still reads,
            // never up: the point of this crop is that the person can check the digits.
            val sampleSize = generateSequence(1) { it * 2 }
                .first { rect.width() / it <= MAX_CROP_EDGE_PIXELS && rect.height() / it <= MAX_CROP_EDGE_PIXELS }

            val options = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize
            }

            // recycle() in a finally: decodeRegion can throw, and leaking the decoder leaks the
            // mapped file with it.
            val bitmap = try {
                decoder.decodeRegion(rect, options)
            } finally {
                decoder.recycle()
            }
            bitmap
        }.getOrNull()
    }
}
