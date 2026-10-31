package com.cuentivo.app.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Keeps product photos inside the app's own storage.
 *
 * The picked content:// Uri is not stored: those grants are revoked on reboot and the row would be
 * left pointing at a photo the app can no longer open. The file is copied in instead, and
 * [ProductEntity.imageUri] holds only the file *name* — an absolute path would break on restore or
 * on a new phone, where the app's data directory is different.
 *
 * Photos stay on this device for now. A product that arrives by sync carries the name of a file that
 * only its author's phone has, so [load] returns null there and the screen shows the placeholder.
 * Uploading them needs Firebase Storage, which needs the Blaze plan.
 */
class ProductImageStore(
    context: Context,
    private val cache: BoundedImageCache<Bitmap> = BoundedImageCache(
        maxBytes = CACHE_BYTES,
        sizeOf = { it.byteCount.toLong() },
    ),
) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY)
    private val resolver = context.applicationContext.contentResolver

    /**
     * Copies and downscales [source]; returns the stored file name, or null if it could not be read.
     *
     * The name carries a timestamp rather than being just the product id. Reusing one name meant a
     * replaced photo kept the old file name, and every screen keyed on that name — so the list went
     * on showing the previous picture, and the sync row looked unchanged.
     */
    fun save(source: Uri, productId: String): String? = runCatching {
        directory.mkdirs()
        val bitmap = decodeDownscaled(source) ?: return null
        val fileName = "$productId-${System.currentTimeMillis()}.jpg"
        File(directory, fileName).outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, output)
        }
        bitmap.recycle()
        fileName
    }.getOrNull()

    /**
     * Loads a display-sized decode of the photo, served from a bounded memory cache when possible.
     *
     * A product list scrolls rows in and out constantly; decoding the stored master (up to 1024 px)
     * on every entry is what made cheap phones stutter. The cache keeps the most recently used
     * bitmaps in memory under a fixed byte budget, and [load] itself decodes sampled so a single
     * frame never pulls more than a thumbnail-sized image off disk.
     */
    fun load(fileName: String): Bitmap? = runCatching {
        cache.get(fileName)?.let { return it }
        val file = File(directory, fileName)
        if (!file.exists()) return null
        val bitmap = decodeDisplay(file) ?: return null
        cache.put(fileName, bitmap)
        bitmap
    }.getOrNull()

    fun delete(fileName: String) {
        cache.remove(fileName)
        runCatching { File(directory, fileName).delete() }
    }

    /**
     * Reads the image at a fraction of its size.
     *
     * Phone cameras produce images far larger than a list thumbnail needs, and decoding one at full
     * resolution is a reliable way to run a low-end device out of memory.
     */
    private fun decodeDownscaled(source: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // The measuring pass ALWAYS returns null by design — that is what inJustDecodeBounds means.
        // Testing its result instead of the stream is why saving a photo silently did nothing: the
        // method bailed out on every single image. The real answer is in bounds.outWidth/outHeight.
        val measured = resolver.openInputStream(source) ?: return null
        measured.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
        }
        return resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Decodes a stored master sampled to the display edge, so a row never holds a full-size image. */
    private fun decodeDisplay(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DISPLAY_EDGE_PX)
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    companion object {
        /** The power-of-two factor that keeps both decoded edges at or below [maxEdgePx]. */
        internal fun sampleSizeFor(width: Int, height: Int, maxEdgePx: Int): Int {
            if (width <= 0 || height <= 0) return 1
            var sampleSize = 1
            while (width / sampleSize > maxEdgePx || height / sampleSize > maxEdgePx) {
                sampleSize *= 2
            }
            return sampleSize
        }

        private const val DIRECTORY = "product-images"

        /** Master copy edge for the saved file. */
        private const val MAX_EDGE_PX = 1024

        /** Display decode edge: comfortably above every photo surface the app draws (<= 96 dp at 4x). */
        private const val MAX_DISPLAY_EDGE_PX = 512

        /** Roughly a dozen display bitmaps at 512 px; bounded so scrolling cannot grow memory forever. */
        private const val CACHE_BYTES = 6L * 1024 * 1024

        private const val QUALITY = 85
    }
}
