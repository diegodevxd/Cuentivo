package com.cuentivo.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * El selector de iOS: `PHPickerViewController` para la galería y `UIImagePickerController` para la
 * cámara.
 *
 * **Los delegados son referencias débiles** en UIKit: si no se guardan en un campo fuerte, el
 * recolector los borra y el resultado nunca llega. [IosPickerHost] es ese campo, y se conserva
 * mientras el selector vive.
 *
 * Pendiente de CI: no hay simulador de pruebas con ventana ni cámara.
 */
@OptIn(ExperimentalForeignApi::class)
class IosImagePickerFactory(
    private val host: () -> UIViewController? = { topViewController() },
) : ImagePickerFactory {

    @Composable
    override fun remember(onPicked: suspend (PickedImage) -> Unit): ImagePicker {
        val scope = rememberCoroutineScope()
        val pickerHost = remember { IosPickerHost() }
        DisposableEffect(pickerHost) {
            onDispose { pickerHost.release() }
        }
        return remember(pickerHost, scope, onPicked) {
            IosImagePicker(pickerHost = pickerHost, host = host, scope = scope, onPicked = onPicked)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosImagePicker(
    private val pickerHost: IosPickerHost,
    private val host: () -> UIViewController?,
    private val scope: CoroutineScope,
    private val onPicked: suspend (PickedImage) -> Unit,
) : ImagePicker {

    override fun pickFromGallery() {
        val controller = host() ?: return
        val configuration = PHPickerConfiguration()
        configuration.filter = PHPickerFilter.imagesFilter
        configuration.selectionLimit = 1

        val picker = PHPickerViewController(configuration = configuration)
        picker.delegate = pickerHost
        pickerHost.onImage = { data ->
            if (data == null) return@onImage
            // El selector corre `onPicked` en su propio scope y limpia lo temporal en `finally`.
            scope.launch {
                try {
                    onPicked(data)
                } finally {
                    pickerHost.onImage = null
                }
            }
        }
        controller.presentViewController(picker, animated = true, completion = null)
    }

    override fun takePhoto(): Boolean {
        if (!UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)) {
            return false
        }
        val controller = host() ?: return false
        val picker = UIImagePickerController()
        picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        picker.delegate = pickerHost
        pickerHost.onImage = { data ->
            if (data == null) return@onImage
            scope.launch {
                try {
                    onPicked(data)
                } finally {
                    pickerHost.onImage = null
                }
            }
        }
        controller.presentViewController(picker, animated = true, completion = null)
        return true
    }
}

/**
 * El delegado fuerte de los dos selectores.
 *
 * UIKit los retiene débil, así que este objeto tiene que vivir mientras el selector esté en pantalla;
 * por eso [IosImagePickerFactory] lo guarda en un `remember`.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosPickerHost :
    NSObject(),
    PHPickerViewControllerDelegateProtocol,
    UIImagePickerControllerDelegateProtocol,
    UINavigationControllerDelegateProtocol {

    var onImage: ((NSData?) -> Unit)? = null

    fun release() {
        onImage = null
    }

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: return
        result.itemProvider.loadDataRepresentationForTypeIdentifier(PUBLIC_IMAGE) { data: NSData?, _: NSError? ->
            // La carga termina en una cola de fondo; la UI y el scope de Compose son del hilo principal.
            dispatch_async(dispatch_get_main_queue()) {
                onImage?.invoke(data)
            }
        }
    }

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        val data = image?.let { UIImageJPEGRepresentation(it, JPEG_QUALITY) }
        onImage?.invoke(data)
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onImage = null
    }

    private companion object {
        const val PUBLIC_IMAGE = "public.image"
        const val JPEG_QUALITY = 0.85
    }
}
