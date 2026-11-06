package com.cuentivo.app.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill

/**
 * El almacén de fotos de producto de iOS, sobre archivos de verdad.
 *
 * Genera una foto de 2000×1000, la guarda y comprueba que al cargarla baja a 512 o menos. También que
 * el nombre temporal es el esperado, que `delete` borra y que un `productId` con `../` no escribe
 * nada.
 *
 * Pendiente de CI: sólo corre en `:shared:iosSimulatorArm64Test`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosProductPhotoStoreTest {

    private val roots = mutableListOf<String>()
    private val manager = NSFileManager.defaultManager

    @AfterTest
    fun cleanUp() {
        roots.forEach { manager.removeItemAtPath(it, error = null) }
        roots.clear()
    }

    private fun newFiles(): IosAppFiles {
        val root = NSTemporaryDirectory().trimEnd('/') + "/cuentivo-photos-" + NSUUID().UUIDString
        roots += root
        return IosAppFiles(cacheRoot = "$root/cache", filesRoot = "$root/files")
    }

    /** Una foto grande y opaca, como la que entrega una cámara. */
    private fun sourcePhoto(width: Int, height: Int) = run {
        val format = UIGraphicsImageRendererFormat.defaultFormat()
        format.scale = 1.0
        format.opaque = true
        val renderer = UIGraphicsImageRenderer(
            size = CGSizeMake(width.toDouble(), height.toDouble()),
            format = format,
        )
        val image = renderer.imageWithActions {
            UIColor.colorWithRed(red = 0.2, green = 0.4, blue = 0.6, alpha = 1.0).setFill()
            UIRectFill(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        }
        UIImageJPEGRepresentation(image, 0.9)
    }

    @Test
    fun `guarda carga recorta y borra`() {
        val files = newFiles()
        val store = IosProductPhotoStore(files = files, now = { 1_700_000_000_000L })
        val photo = sourcePhoto(2000, 1000)
        assertNotNull(photo, "la foto de prueba tiene que generarse")

        val name = store.save(photo, "producto-1")

        assertEquals("producto-1-1700000000000.jpg", name)
        assertNotNull(files.absolutePath(FileArea.PERSISTENT, "product-images", name!!))

        val bitmap = store.loadBitmap(name)
        assertNotNull(bitmap)
        assertTrue(bitmap.width <= 512 && bitmap.height <= 512, "al cargar se recorta a 512")

        store.delete(name)
        assertNull(files.readBytes(FileArea.PERSISTENT, "product-images", name))
    }

    @Test
    fun `un productId invalido no escribe nada`() {
        val files = newFiles()
        val store = IosProductPhotoStore(files = files, now = { 1L })
        val photo = sourcePhoto(40, 40)
        assertNotNull(photo)

        assertNull(store.save(photo, "../otro"))
        assertTrue(files.list(FileArea.PERSISTENT, "product-images").isEmpty())
    }
}
