package com.cuentivo.app.ui.ai

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember as composeRemember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import com.cuentivo.app.ai.capture.AiCameraCaptureStore
import com.cuentivo.app.ai.capture.AiCapturePolicy
import com.cuentivo.app.ai.capture.AiCaptureStore
import com.cuentivo.app.ai.ledger.LedgerImagePreparer
import com.cuentivo.app.ai.ledger.LedgerRegion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidLedgerCapture(context: Context) : LedgerPagePickerFactory, LedgerPageFiles {

    private val captureStore = AiCaptureStore(File(context.cacheDir, "ai-capture"))
    private val cameraStore = AiCameraCaptureStore(context)
    private val contentResolver = context.contentResolver

    @Composable
    override fun remember(
        onPrepared: (PreparedLedgerPage) -> Unit,
        onCancelled: () -> Unit,
        onFailed: () -> Unit,
    ): LedgerPagePicker {
        val scope = rememberCoroutineScope()
        var pendingLedgerUri by rememberSaveable(stateSaver = CaptureUriSaver) { mutableStateOf<Uri?>(null) }

        // Sweeps captures orphaned by an earlier dialog when this one opens, not in onDispose:
        // leaving composition can mean the Activity was destroyed while the camera app was still
        // writing to that very file, and deleting it there threw away the photo the user just took.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { cameraStore.deleteStaleExcept(listOf(pendingLedgerUri)) }
        }

        val ledgerCameraLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val uri = pendingLedgerUri ?: return@rememberLauncherForActivityResult
            pendingLedgerUri = null
            if (!success) {
                cameraStore.delete(uri)
                onCancelled()
                return@rememberLauncherForActivityResult
            }
            val file = cameraStore.fileFor(uri)
            scope.launch {
                // A camera photo at full resolution does not fit, and refusing it would make the main
                // way of using this feature useless. It is scaled and rotated instead.
                val mimeType = withContext(Dispatchers.IO) { LedgerImagePreparer.prepareInPlace(file) }
                if (mimeType == null) {
                    cameraStore.delete(uri)
                    onFailed()
                } else {
                    onPrepared(PreparedLedgerPage(file.absolutePath, mimeType))
                }
            }
        }

        val ledgerPickLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri == null) {
                onCancelled()
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                val prepared = withContext(Dispatchers.IO) {
                    val mimeType = contentResolver.getType(uri)
                    if (!AiCapturePolicy.isAllowedMime(mimeType)) return@withContext null
                    val target = captureStore.createCaptureFile(mimeType!!) ?: return@withContext null
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: return@runCatching null
                        val preparedMime = LedgerImagePreparer.prepareInPlace(target)
                        if (preparedMime == null) {
                            target.delete()
                            null
                        } else {
                            target to preparedMime
                        }
                    }.getOrNull()
                }
                if (prepared == null) {
                    // A copy that failed halfway still left a file behind. And say so: the camera path
                    // already did, and a gallery pick that ends in silence is indistinguishable from
                    // the button doing nothing.
                    onFailed()
                } else {
                    onPrepared(PreparedLedgerPage(prepared.first.absolutePath, prepared.second))
                }
            }
        }

        return composeRemember(ledgerCameraLauncher, ledgerPickLauncher, scope) {
            object : LedgerPagePicker {
                override fun pickFromGallery() {
                    ledgerPickLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }

                override fun takePhoto(): Boolean {
                    cameraStore.deleteStaleExcept(listOf(pendingLedgerUri))
                    val attempt = runCatching {
                        val uri = cameraStore.createCaptureUri()
                        pendingLedgerUri = uri
                        ledgerCameraLauncher.launch(uri)
                    }
                    if (attempt.isFailure) {
                        pendingLedgerUri?.let(cameraStore::delete)
                        pendingLedgerUri = null
                    }
                    return attempt.isSuccess
                }
            }
        }
    }

    override fun readBytes(path: String): ByteArray? =
        runCatching { File(path).readBytes() }.getOrNull()

    override fun delete(path: String) {
        File(path).delete()
    }

    override fun cleanupAll() {
        captureStore.cleanupAll()
        cameraStore.deleteStale(except = null)
    }

    override fun deleteStaleCaptures() {
        cameraStore.deleteStale(except = null)
    }

    override fun decodeCrop(path: String, region: LedgerRegion) =
        LedgerCropHelper.decodeCrop(File(path), region)?.asImageBitmap()
}

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
