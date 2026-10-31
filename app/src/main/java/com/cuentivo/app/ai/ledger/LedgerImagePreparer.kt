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

    fun getExifMatrix(orientation: Int): Matrix {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> {}
        }
        return matrix
    }

    /**
     * Prepares [source] and writes the scaled/EXIF-transformed JPEG to [target].
     */
    fun prepareToTarget(source: File, target: File): String? {
        if (!source.exists() || !source.canRead()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = getExifMatrix(orientation)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching { BitmapFactory.decodeFile(source.absolutePath, options) }.getOrNull()
            ?: return null

        val longestEdge = maxOf(decoded.width, decoded.height)
        val scaled = if (longestEdge <= MAX_EDGE_PIXELS) {
            decoded
        } else {
            val ratio = MAX_EDGE_PIXELS.toDouble() / longestEdge
            val targetBmp = runCatching {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            }.getOrNull() ?: return null
            if (targetBmp !== decoded) decoded.recycle()
            targetBmp
        }

        val rotated = if (matrix.isIdentity) {
            scaled
        } else {
            runCatching {
                Bitmap.createBitmap(
                    scaled,
                    0,
                    0,
                    scaled.width,
                    scaled.height,
                    matrix,
                    true,
                )
            }.getOrNull().also { if (it !== scaled) scaled.recycle() } ?: return null
        }

        return try {
            target.outputStream().use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (AiLedgerImagePolicy.isWithinCallableCap(target.length())) "image/jpeg" else null
        } catch (_: Exception) {
            target.delete()
            null
        } finally {
            rotated.recycle()
        }
    }

    /**
     * Rewrites [source] scaled and rotated.
     */
    fun prepareInPlace(source: File): String? {
        val tempFile = try {
            File.createTempFile("prepared_", ".jpg", source.parentFile)
        } catch (_: Exception) {
            return null
        }
        val mimeType = prepareToTarget(source, tempFile)
        if (mimeType != null) {
            try {
                tempFile.copyTo(source, overwrite = true)
                tempFile.delete()
                return mimeType
            } catch (_: Exception) {
                tempFile.delete()
                return null
            }
        } else {
            tempFile.delete()
            return null
        }
    }


    /**
     * Largest power of two that still leaves both edges at or above [MAX_EDGE_PIXELS], so the decode
     * is cheap without throwing away detail the final scaling still needs. Returns 1 for a photo
     * that is already small: nothing is ever upscaled.
     *
     * takeWhile before lastOrNull on purpose: the sequence is infinite, and last over it never
     * returns.
     */
    fun sampleSizeFor(width: Int, height: Int): Int =
        generateSequence(1) { it * 2 }
            .takeWhile { width / it >= MAX_EDGE_PIXELS && height / it >= MAX_EDGE_PIXELS }
            .lastOrNull() ?: 1
}
