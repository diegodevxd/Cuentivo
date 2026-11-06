package com.cuentivo.app.ui.product

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
// Los accesos de Res.string son propiedades de extensión generadas una por clave.
import com.cuentivo.app.platform.ImagePickerFactory
import com.cuentivo.app.platform.ProductPhotoStore
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.dashboard.initialOf
import com.cuentivo.shared.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A product's photo, or a neutral placeholder when it has none.
 *
 * Extracted from the monolith with its behaviour untouched: decoding still happens on
 * `Dispatchers.IO`, because a list of products would otherwise stutter on a cheap phone.
 */
@Composable
fun ProductPhoto(
    fileName: String?,
    store: ProductPhotoStore,
    size: Dp,
    /** Null for the form's empty picker, which deliberately reads as an add-photo affordance. */
    placeholderName: String? = null,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, fileName) {
        value = fileName?.let { name -> withContext(Dispatchers.IO) { store.loadBitmap(name) } }
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
                bitmap = image,
                contentDescription = stringResource(Res.string.product_photo_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: if (placeholderName == null) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(Res.string.product_photo_none),
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
fun ProductPhotoPicker(
    draft: PhotoDraft,
    store: ProductPhotoStore,
    pickers: ImagePickerFactory,
    targetId: String,
    onDraftChange: (PhotoDraft) -> Unit,
) {
    val fileName = draft.current
    var working by remember { mutableStateOf(false) }
    var showSourceSelector by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<StringResource?>(null) }

    val picker = pickers.remember { source ->
        working = true
        try {
            // Una foto de cámara pesa varios megapíxeles: decodificarla en el hilo principal congela el diálogo.
            val saved = withContext(Dispatchers.IO) { store.save(source, targetId) }
            val next = ProductPhotoDraftPolicy.applySaved(draft, saved, store::delete)
            if (next != null) onDraftChange(next) else errorMessage = Res.string.product_photo_save_failed
        } finally {
            working = false
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
                            working -> Res.string.product_photo_saving
                            fileName == null -> Res.string.product_photo_add
                            else -> Res.string.product_photo_change
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
                    Text(
                        stringResource(Res.string.product_photo_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
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
                    text = stringResource(Res.string.product_photo_source_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TextButton(
                    onClick = {
                        showSourceSelector = false
                        errorMessage = null
                        if (!picker.takePhoto()) errorMessage = Res.string.product_photo_camera_unavailable
                    },
                    enabled = !working,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("product-photo-source-camera"),
                ) { Text(stringResource(Res.string.product_photo_source_camera)) }
                TextButton(
                    onClick = {
                        showSourceSelector = false
                        errorMessage = null
                        picker.pickFromGallery()
                    },
                    enabled = !working,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("product-photo-source-gallery"),
                ) { Text(stringResource(Res.string.product_photo_source_gallery)) }
                TextButton(
                    onClick = { showSourceSelector = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("product-photo-source-cancel"),
                ) { Text(stringResource(Res.string.form_cancel)) }
            }
        }
    }
}
