package com.cuentivo.app.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember as composeRemember
import androidx.compose.ui.graphics.ImageBitmap
import com.cuentivo.app.ai.ledger.AiLedgerImagePolicy
import com.cuentivo.app.ai.ledger.LedgerCropGeometry
import com.cuentivo.app.ai.ledger.LedgerRegion
import com.cuentivo.app.platform.AppFiles
import com.cuentivo.app.platform.FileArea
import com.cuentivo.app.platform.IosImagePickerFactory
import com.cuentivo.app.platform.newUuid
import com.cuentivo.app.platform.toByteArray
import com.cuentivo.app.platform.toNSData
import kotlin.math.min
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation

/**
 * La libreta en iOS: archivos en caché y el selector del sistema.
 *
 * El `path` es sólo el nombre del archivo, nunca una ruta absoluta: el contenedor de la app puede
 * cambiar entre lanzamientos y una ruta guardada dejaría de apuntar a la foto.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class)
class IosLedgerCapture(
    private val files: AppFiles,
    private val pickers: IosImagePickerFactory,
) : LedgerPagePickerFactory, LedgerPageFiles {

    @Composable
    override fun remember(
        onPrepared: (PreparedLedgerPage) -> Unit,
        onCancelled: () -> Unit,
        onFailed: () -> Unit,
    ): LedgerPagePicker {
        // En iOS el proceso no se recrea con la pantalla abierta: los huérfanos se limpian al abrir.
        LaunchedEffect(Unit) {
            files.clear(FileArea.CACHE, DIRECTORY)
        }
        val picker = pickers.rememberIosImagePicker(
            onPicked = { source ->
                val name = "${newUuid()}.jpg"
                val bytes = withContext(Dispatchers.Default) { prepare(source) }
                if (bytes == null || !files.writeBytes(FileArea.CACHE, DIRECTORY, name, bytes)) {
                    onFailed()
                } else {
                    onPrepared(PreparedLedgerPage(name, "image/jpeg"))
                }
            },
            onNoImage = onCancelled,
        )
        return composeRemember(picker) {
            object : LedgerPagePicker {
                override fun pickFromGallery() {
                    picker.pickFromGallery()
                }

                override fun takePhoto(): Boolean = picker.takePhoto()
            }
        }
    }

    override fun readBytes(path: String): ByteArray? =
        files.readBytes(FileArea.CACHE, DIRECTORY, path)

    override fun delete(path: String) {
        files.delete(FileArea.CACHE, DIRECTORY, path)
    }

    override fun cleanupAll() {
        files.clear(FileArea.CACHE, DIRECTORY)
    }

    /** En iOS la cámara no deja archivos propios: el selector entrega los bytes en memoria. */
    override fun deleteStaleCaptures() = Unit

    override fun decodeCrop(path: String, region: LedgerRegion): ImageBitmap? = autoreleasepool {
        val bytes = files.readBytes(FileArea.CACHE, DIRECTORY, path) ?: return@autoreleasepool null
        val image = UIImage(data = bytes.toNSData()) ?: return@autoreleasepool null
        val whole = image.CGImage ?: return@autoreleasepool null
        val rect = LedgerCropGeometry.pixelRect(
            region,
            CGImageGetWidth(whole).toInt(),
            CGImageGetHeight(whole).toInt(),
        ) ?: return@autoreleasepool null
        val cropped = CGImageCreateWithImageInRect(
            whole,
            CGRectMake(rect.left.toDouble(), rect.top.toDouble(), rect.width.toDouble(), rect.height.toDouble()),
        ) ?: return@autoreleasepool null
        try {
            val png = UIImagePNGRepresentation(UIImage(cGImage = cropped)) ?: return@autoreleasepool null
            runCatching { png.toByteArray().decodeToImageBitmap() }.getOrNull()
        } finally {
            CGImageRelease(cropped)
        }
    }

    private companion object {
        const val DIRECTORY = "ledger-capture"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun prepare(source: NSData): ByteArray? = autoreleasepool {
    val image = UIImage(data = source) ?: return@autoreleasepool null
    val (width, height) = image.size.useContents { width to height }
    if (width <= 0.0 || height <= 0.0) return@autoreleasepool null
    val factor = min(MAX_EDGE / width, MAX_EDGE / height).coerceAtMost(1.0)
    val format = UIGraphicsImageRendererFormat.defaultFormat()
    format.scale = 1.0
    format.opaque = true
    val renderer = UIGraphicsImageRenderer(
        size = CGSizeMake(width * factor, height * factor),
        format = format,
    )
    val drawn = renderer.imageWithActions {
        image.drawInRect(CGRectMake(0.0, 0.0, width * factor, height * factor))
    }
    val bytes = UIImageJPEGRepresentation(drawn, 0.85)?.toByteArray() ?: return@autoreleasepool null
    if (AiLedgerImagePolicy.isWithinCallableCap(bytes.size.toLong())) bytes else null
}

private const val MAX_EDGE = 2400.0
