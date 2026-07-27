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
class ProductImageStore(context: Context) {
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

    fun load(fileName: String): Bitmap? = runCatching {
        val file = File(directory, fileName)
        if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }.getOrNull()

    fun delete(fileName: String) {
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

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_EDGE_PX || bounds.outHeight / sampleSize > MAX_EDGE_PX) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private companion object {
        const val DIRECTORY = "product-images"
        const val MAX_EDGE_PX = 1024
        const val QUALITY = 85
    }
}
