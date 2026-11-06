package com.cuentivo.app.ui.product

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember as composeRemember
import androidx.compose.ui.platform.LocalContext
import com.cuentivo.app.platform.ImagePicker
import com.cuentivo.app.platform.ImagePickerFactory
import com.cuentivo.app.platform.PickedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Survives Activity recreation while the camera app is in front.
 *
 * The camera runs in another process, so this one can be destroyed while it is on screen. A plain
 * `remember` would forget where the photo was being written and the returning result would be
 * dropped even though the file landed on disk.
 */
private val CaptureUriSaver = Saver<Uri?, String>(
    save = { it?.toString() },
    restore = { Uri.parse(it) },
)

/**
 * El selector de fotos de Android: galería con `PickVisualMedia` y cámara con `TakePicture`.
 *
 * Vive en `:app` porque depende de `FileProvider` y de `product_camera_paths.xml`. Las pantallas
 * comunes sólo ven `ImagePickerFactory`, que es lo que la raíz común inyectará en cada plataforma.
 *
 * `composeRemember` va con alias a propósito: dentro de este objeto, `remember` es el método de la
 * interfaz y taparía al de Compose; peor aún, como la lambda puede convertirse en
 * `suspend (PickedImage) -> Unit`, un `remember { ... }` sin alias compilaría como una llamada
 * recursiva a sí mismo.
 */
object AndroidImagePickerFactory : ImagePickerFactory {

    @Composable
    override fun remember(onPicked: suspend (PickedImage) -> Unit): ImagePicker {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var pendingCaptureUri by rememberSaveable(stateSaver = CaptureUriSaver) { mutableStateOf<Uri?>(null) }
        val cameraStore = composeRemember(context) { CameraCaptureStore(context) }

        // Captures orphaned by an earlier dialog are swept when this one opens, not in onDispose:
        // leaving composition can mean the Activity was destroyed while the camera app was still
        // writing to that very file, and deleting it there threw away the photo the user just took.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { cameraStore.deleteStale(except = pendingCaptureUri) }
        }

        val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingCaptureUri ?: return@rememberLauncherForActivityResult
            pendingCaptureUri = null
            if (!success) {
                cameraStore.delete(uri)
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                deliverCapture(uri, { cameraStore.delete(it) }) { onPicked(PickedImage(it)) }
            }
        }

        // PickVisualMedia opens the system photo picker, which needs no storage permission at all.
        val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch { onPicked(PickedImage(uri)) }
        }

        return composeRemember(cameraLauncher, galleryLauncher) {
            object : ImagePicker {
                override fun pickFromGallery() {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }

                override fun takePhoto(): Boolean {
                    // createCaptureUri throws if the FileProvider paths ever stop matching, and
                    // launch throws ActivityNotFoundException on a device with no camera app.
                    // Neither is worth closing the app on top of a half-filled product form.
                    val attempt = runCatching {
                        val uri = cameraStore.createCaptureUri()
                        pendingCaptureUri = uri
                        cameraLauncher.launch(uri)
                    }
                    if (attempt.isFailure) {
                        pendingCaptureUri?.let(cameraStore::delete)
                        pendingCaptureUri = null
                        return false
                    }
                    return true
                }
            }
        }
    }
}
