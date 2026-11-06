package com.cuentivo.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import com.cuentivo.app.data.local.BoundedImageCache
import kotlin.math.min
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.useContents
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation

/**
 * Las fotos de producto de iOS, en el almacenamiento propio de la app.
 *
 * El nombre lleva marca de tiempo por la misma razón que en Android: reusar el nombre hacía que una
 * foto reemplazada se siguiera viendo con el nombre viejo.
 *
 * Aristas: máximo 1024 px al guardar y 512 al cargar, redibujando con `UIGraphicsImageRenderer`, que
 * respeta la orientación de la foto. JPEG 0.85. La caché en memoria es la misma `BoundedImageCache`
 * de Android, con 6 MB y `width * height * 4` por entrada.
 *
 * `save` y `loadBitmap` devuelven `null` ante cualquier fallo o nombre inválido, sin tronar: una foto
 * que no se puede leer no puede tumbar una lista.
 *
 * Pendiente de CI: esta clase sólo compila y corre en el runner de macOS.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class)
class IosProductPhotoStore(
    private val files: AppFiles,
    private val now: () -> Long = { iosNowEpochMillis() },
) : ProductPhotoStore {

    private val cache = BoundedImageCache<ImageBitmap>(
        maxBytes = CACHE_BYTES,
        sizeOf = { it.width.toLong() * it.height.toLong() * 4L },
    )

    override fun save(source: PickedImage, productId: String): String? = autoreleasepool {
        if (!SafeFileName.isValid(productId)) return@autoreleasepool null
        val image = UIImage(data = source) ?: return@autoreleasepool null
        val scaled = image.downscaled(MAX_SAVE_EDGE) ?: return@autoreleasepool null
        val data = UIImageJPEGRepresentation(scaled, JPEG_QUALITY) ?: return@autoreleasepool null
        val bytes = data.toByteArray() ?: return@autoreleasepool null
        val name = "$productId-${now()}.jpg"
        if (files.writeBytes(FileArea.PERSISTENT, DIRECTORY, name, bytes)) name else null
    }

    override fun loadBitmap(fileName: String): ImageBitmap? = autoreleasepool {
        cache.get(fileName)?.let { return@autoreleasepool it }
        val bytes = files.readBytes(FileArea.PERSISTENT, DIRECTORY, fileName) ?: return@autoreleasepool null
        val image = UIImage(data = bytes.toNSData()) ?: return@autoreleasepool null
        val scaled = image.downscaled(MAX_LOAD_EDGE) ?: return@autoreleasepool null
        val png = UIImagePNGRepresentation(scaled) ?: return@autoreleasepool null
        val bitmap = runCatching { png.toByteArray().decodeToImageBitmap() }.getOrNull()
            ?: return@autoreleasepool null
        cache.put(fileName, bitmap)
        bitmap
    }

    override fun delete(fileName: String) {
        cache.remove(fileName)
        files.delete(FileArea.PERSISTENT, DIRECTORY, fileName)
    }

    /**
     * Reduce la imagen para que su lado mayor no pase de [maxEdge], redibujándola.
     *
     * Se redibuja en vez de tocar el tamaño: `drawInRect` respeta la orientación EXIF de la foto, que
     * es lo que evita que una foto tomada de lado se guarde girada.
     */
    private fun UIImage.downscaled(maxEdge: Int): UIImage? {
        val (width, height) = size.useContents { width to height }
        if (width <= 0.0 || height <= 0.0) return null
        val factor = min(maxEdge.toDouble() / width, maxEdge.toDouble() / height).coerceAtMost(1.0)
        if (factor >= 1.0) return this

        val format = UIGraphicsImageRendererFormat.defaultFormat()
        format.scale = 1.0
        format.opaque = true
        val renderer = UIGraphicsImageRenderer(
            size = CGSizeMake(width * factor, height * factor),
            format = format,
        )
        return renderer.imageWithActions {
            this@downscaled.drawInRect(CGRectMake(0.0, 0.0, width * factor, height * factor))
        }
    }

    private companion object {
        const val DIRECTORY = "product-images"
        const val MAX_SAVE_EDGE = 1024
        const val MAX_LOAD_EDGE = 512
        const val JPEG_QUALITY = 0.85
        const val CACHE_BYTES = 6L * 1024 * 1024
    }
}
