package com.cuentivo.app.ui.product

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.ProductImageStore
import com.cuentivo.app.R
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.dashboard.initialOf
import kotlinx.coroutines.CancellationException
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
 * A product's photo, or a neutral placeholder when it has none.
 *
 * Extracted from the monolith with its behaviour untouched: decoding still happens on
 * `Dispatchers.IO`, because a list of products would otherwise stutter on a cheap phone.
 */
@Composable
internal fun ProductPhoto(
    fileName: String?,
    store: ProductImageStore,
    size: Dp,
    /** Null for the form's empty picker, which deliberately reads as an add-photo affordance. */
    placeholderName: String? = null,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, fileName) {
        value = fileName?.let { name -> withContext(Dispatchers.IO) { store.load(name) } }
    }
    Box(
        modifier = modifier
            .size(size)
            // clip before background: background(shape=) paints a rounded fill but does not clip
            // children, so a real photo drew square corners over it.
            .clip(NeoShapes.row)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = stringResource(R.string.product_photo_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: if (placeholderName == null) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.product_photo_none),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2),
            )
        } else {
            Text(
                text = initialOf(placeholderName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

}

/**
 * The tappable photo box inside the new/edit product dialogs.
 *
 * It edits a [PhotoDraft] and never deletes a photo a product row still points at. The only files
 * it removes are ones it created itself in this same dialog and then replaced.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ProductPhotoPicker(
    draft: PhotoDraft,
    store: ProductImageStore,
    targetId: String,
    onDraftChange: (PhotoDraft) -> Unit,
) {
    val fileName = draft.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var showSourceSelector by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<Int?>(null) }
    var pendingCaptureUri by rememberSaveable(stateSaver = CaptureUriSaver) { mutableStateOf<Uri?>(null) }
    val cameraStore = remember(context) { CameraCaptureStore(context) }

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
        working = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.save(uri, targetId) }
                if (saved != null) {
                    val update = ProductPhotoDraftPolicy.picked(draft, saved)
                    update.deleteNow.forEach(store::delete)
                    onDraftChange(update.draft)
                } else {
                    errorMessage = R.string.product_photo_save_failed
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                cameraStore.delete(uri)
                working = false
            }
        }
    }

    // PickVisualMedia opens the system photo picker, which needs no storage permission at all.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            // A camera photo is several megapixels; decoding it on the main thread freezes the
            // dialog long enough to look broken.
            val saved = withContext(Dispatchers.IO) { store.save(uri, targetId) }
            working = false
            if (saved != null) {
                val update = ProductPhotoDraftPolicy.picked(draft, saved)
                update.deleteNow.forEach(store::delete)
                onDraftChange(update.draft)
            } else {
                errorMessage = R.string.product_photo_save_failed
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProductPhoto(
            fileName = fileName,
            store = store,
            size = 88.dp,
            modifier = Modifier.clickable {
                showSourceSelector = true
            },
        )
        Spacer(Modifier.width(12.dp))
        Column {
            TextButton(
                onClick = {
                    showSourceSelector = true
                },
                enabled = !working,
            ) {
                Text(
                    stringResource(
                        when {
                            working -> R.string.product_photo_saving
                            fileName == null -> R.string.product_photo_add
                            else -> R.string.product_photo_change
                        }
                    )
                )
            }
            if (fileName != null) {
                TextButton(onClick = {
                    val update = ProductPhotoDraftPolicy.cleared(draft)
                    update.deleteNow.forEach(store::delete)
                    onDraftChange(update.draft)
                }) {
                    Text(stringResource(R.string.product_photo_remove), color = MaterialTheme.colorScheme.error)
                }
            }
            errorMessage?.let { message ->
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("product-photo-error"),
                )
            }
        }
    }

    if (showSourceSelector) {
        ModalBottomSheet(onDismissRequest = { showSourceSelector = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .testTag("product-photo-source-sheet"),
            ) {
                Text(
                    text = stringResource(R.string.product_photo_source_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TextButton(
                    onClick = {
                        showSourceSelector = false
                        errorMessage = null
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
                            errorMessage = R.string.product_photo_camera_unavailable
                        }
                    },
                    enabled = !working,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("product-photo-source-camera"),
                ) { Text(stringResource(R.string.product_photo_source_camera)) }
                TextButton(
                    onClick = {
                        showSourceSelector = false
                        errorMessage = null
                        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = !working,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("product-photo-source-gallery"),
                ) { Text(stringResource(R.string.product_photo_source_gallery)) }
                TextButton(
                    onClick = { showSourceSelector = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("product-photo-source-cancel"),
                ) { Text(stringResource(R.string.form_cancel)) }
            }
        }
    }
}
