package com.cuentivo.app.ai.ledger

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/**
 * Gets a photographed notebook page ready to travel.
 *
 * Photographing the page is the whole point of this feature, so a photo from the phone camera has to
 * work. At full sensor resolution it does not: it can exceed what the callable accepts, and the
 * failure arrives as a silent "nothing happened" after the slowest part of the flow.
 *
 * So the page is **scaled down instead of refused**. A notebook line stays perfectly legible at a
 * couple of thousand pixels — the model downsamples large images anyway — while the upload drops by
 * an order of magnitude.
 *
 * Rotation is applied, not just recorded. A phone held sideways writes EXIF orientation instead of
 * rotating the pixels, and the model reads pixels: a page sent sideways is a page read sideways.
 */
object LedgerImagePreparer {

    /** Longest edge sent to the model. Enough for handwriting, far below any cap. */
    const val MAX_EDGE_PIXELS = 2400

    /** JPEG quality for the re-encoded page. High enough that thin pen strokes survive. */
    const val JPEG_QUALITY = 85

    /**
     * Rewrites [source] in place, scaled and rotated, and reports the mime type to send.
     * Returns null when the file cannot be decoded at all, so the caller can say so instead of
     * uploading something the server will reject.
     */
    fun prepareInPlace(source: File): String? {
        if (!source.exists() || !source.canRead()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val rotation = runCatching {
            when (ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching { BitmapFactory.decodeFile(source.absolutePath, options) }.getOrNull()
            ?: return null

        val rotated = if (rotation == 0f) {
            decoded
        } else {
            runCatching {
                Bitmap.createBitmap(
                    decoded,
                    0,
                    0,
                    decoded.width,
                    decoded.height,
                    Matrix().apply { postRotate(rotation) },
                    true,
                )
            }.getOrNull().also { if (it !== decoded) decoded.recycle() } ?: return null
        }

        return try {
            source.outputStream().use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (AiLedgerImagePolicy.isWithinCallableCap(source.length())) "image/jpeg" else null
        } catch (_: Exception) {
            null
        } finally {
            rotated.recycle()
        }
    }

    /**
     * Largest power of two that brings both edges under [MAX_EDGE_PIXELS]. Never upscales: a photo
     * already small enough is decoded as it is.
     */
    fun sampleSizeFor(width: Int, height: Int): Int =
        generateSequence(1) { it * 2 }
            .first { width / it <= MAX_EDGE_PIXELS && height / it <= MAX_EDGE_PIXELS }
}
