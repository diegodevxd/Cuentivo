package com.cuentivo.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap


/**
 * Lo que devuelve un selector de imágenes.
 *
 * Android entrega un `android.net.Uri` e iOS entrega un `NSData` con los bytes ya cargados. Mismo
 * patrón que `PlatformAuthHost`: `expect` en común y `actual typealias` por plataforma.
 */
expect class PickedImage
/** El selector de fotos del sistema, ya sea la galería o la cámara. */
interface ImagePicker {
    fun pickFromGallery()

    /** `false` si este dispositivo no puede tomar fotos; quien llama muestra su aviso. */
    fun takePhoto(): Boolean
}

/**
 * El selector corre `onPicked` en su propio scope y limpia lo temporal en `finally`. Cancelar no
 * llama a `onPicked`.
 */
fun interface ImagePickerFactory {
    @Composable
    fun remember(onPicked: suspend (PickedImage) -> Unit): ImagePicker
}

/** Las fotos de producto, en el almacenamiento propio de la app. */
interface ProductPhotoStore {
    fun save(source: PickedImage, productId: String): String?
    fun loadBitmap(fileName: String): ImageBitmap?
    fun delete(fileName: String)
}
